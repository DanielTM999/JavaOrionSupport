package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.tree.PathTransfer;
import dtm.ide.api.project.tree.PathTransferDecision;
import dtm.ide.api.project.tree.PathTransferKind;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.api.project.tree.ProjectTreeDragOrigin;
import dtm.ide.lsp.JdtLsService;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.ui.JavaCopyDialogPanel;
import dtm.ide.ui.JavaMoveDialogPanel;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPathTransferRefactoringTest {

    @TempDir
    Path project;

    private Path sources;
    private FakeHost host;
    private JavaPathTransferRefactoring refactoring;

    @BeforeEach
    void setUp() throws Exception {
        sources = Files.createDirectories(project.resolve("src/main/java"));
        host = new FakeHost(new JavaProjectDescriptor(project, JavaProjectKind.MAVEN, List.of(new JavaModule(project,
                "demo", "demo", "demo", "jar", List.of(sources), List.of(), project.resolve("target/classes"))),
                false, false, 21, null));
        refactoring = new JavaPathTransferRefactoring(host);
    }

    @Test
    void cancellingTheMoveDialogCancelsTheTransfer() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\npublic class Foo {}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));
        host.moveChoice = JavaMoveDialogPanel.Choice.CANCEL;

        PathTransferDecision decision = refactoring.before(move(target, foo, target.resolve("Foo.java")));

        assertTrue(decision.cancelled());
    }

    @Test
    void movingWithoutAServerFallsBackToTextualAdjustments() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\n\npublic class Foo {\n    Sibling sibling;\n}\n");
        Path sibling = write("demo/a/Sibling.java", "package demo.a;\n\npublic class Sibling {\n    Foo foo;\n}\n");
        Path user = write("demo/b/User.java", "package demo.b;\n\nimport demo.a.Foo;\n\npublic class User {\n    Foo foo;\n}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));
        Path movedFoo = target.resolve("Foo.java");
        PathTransferRequest request = move(target, foo, movedFoo);

        PathTransferDecision decision = refactoring.before(request);
        Files.move(foo, movedFoo);
        apply(refactoring.after(request));

        assertTrue(!decision.cancelled());
        String fooAfter = host.contentOf(movedFoo);
        assertTrue(fooAfter.startsWith("package demo.c;"), fooAfter);
        assertTrue(fooAfter.contains("import demo.a.Sibling;"), fooAfter);
        assertTrue(host.contentOf(sibling).contains("import demo.c.Foo;"), host.contentOf(sibling));
        assertTrue(host.contentOf(user).contains("import demo.c.Foo;"), host.contentOf(user));
        assertEquals(1, host.warnings.size());
    }

    @Test
    void movingWithoutUpdatingReferencesLeavesFilesUntouched() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\npublic class Foo {}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));
        host.moveChoice = JavaMoveDialogPanel.Choice.MOVE_ONLY;
        PathTransferRequest request = move(target, foo, target.resolve("Foo.java"));

        refactoring.before(request);
        Files.move(foo, target.resolve("Foo.java"));

        assertTrue(refactoring.after(request).isEmpty());
    }

    @Test
    void copyingInTheSamePackageAsksForANameAndRenamesTheType() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\n\npublic class Foo {\n    public Foo() {}\n}\n");
        Path directory = foo.getParent();
        PathTransferRequest request = new PathTransferRequest(project, PathTransferKind.COPY, directory,
                List.of(new PathTransfer(foo, directory.resolve("Foo - Copy 1.java"))), ProjectTreeDragOrigin.INTERNAL);

        PathTransferDecision decision = refactoring.before(request);

        assertEquals(Map.of(foo, "FooCopy"), host.offeredNames);
        PathTransferDecision.Retarget retarget = assertInstanceOf(PathTransferDecision.Retarget.class, decision);
        Path copy = directory.resolve("FooCopy.java");
        assertEquals(copy, retarget.newTargets().get(foo));

        Files.copy(foo, copy);
        apply(refactoring.after(request.retarget(retarget.newTargets())));

        String copied = host.contentOf(copy);
        assertTrue(copied.contains("public class FooCopy {"), copied);
        assertTrue(copied.contains("public FooCopy() {}"), copied);
        assertTrue(copied.startsWith("package demo.a;"), copied);
    }

    @Test
    void copyingToAnotherPackageKeepsTheNameAndFixesPackageAndImports() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\n\npublic class Foo {\n    Sibling s;\n}\n");
        write("demo/a/Sibling.java", "package demo.a;\npublic class Sibling {}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));
        PathTransferRequest request = new PathTransferRequest(project, PathTransferKind.COPY, target,
                List.of(new PathTransfer(foo, target.resolve("Foo.java"))), ProjectTreeDragOrigin.INTERNAL);

        PathTransferDecision decision = refactoring.before(request);
        PathTransferDecision.Retarget retarget = assertInstanceOf(PathTransferDecision.Retarget.class, decision);
        Path copy = retarget.newTargets().get(foo);
        Files.copy(foo, copy);
        apply(refactoring.after(request.retarget(retarget.newTargets())));

        String copied = host.contentOf(copy);
        assertEquals(target.resolve("Foo.java"), copy);
        assertTrue(copied.startsWith("package demo.c;"), copied);
        assertTrue(copied.contains("import demo.a.Sibling;"), copied);
        assertTrue(copied.contains("public class Foo {"), copied);
    }

    private static void apply(IdeWorkspaceEdit edit) throws Exception {
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits) {
                String text = Files.readString(textEdits.file());
                for (TextEdit textEdit : textEdits.edits()) {
                    text = textEdit.newText();
                }
                Files.writeString(textEdits.file(), text);
            }
        }
    }

    private PathTransferRequest move(Path targetDirectory, Path source, Path target) {
        return new PathTransferRequest(project, PathTransferKind.MOVE, targetDirectory,
                List.of(new PathTransfer(source, target)), ProjectTreeDragOrigin.INTERNAL);
    }

    private Path write(String relative, String content) throws Exception {
        Path file = sources.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static final class FakeHost implements JavaPathTransferRefactoring.Host {

        private final JavaProjectDescriptor descriptor;
        private final List<String> warnings = new ArrayList<>();
        private JavaMoveDialogPanel.Choice moveChoice = JavaMoveDialogPanel.Choice.REFACTOR;
        private Map<Path, String> offeredNames;

        private FakeHost(JavaProjectDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public JavaProjectDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public JdtLsService readyServer() {
            return null;
        }

        @Override
        public JavaMoveDialogPanel.Choice askMove(JavaPathTransferPlan plan) {
            return moveChoice;
        }

        @Override
        public JavaCopyDialogPanel.Result askCopy(JavaPathTransferPlan plan, Map<Path, String> defaultNames) {
            offeredNames = defaultNames;
            return new JavaCopyDialogPanel.Result(true, defaultNames);
        }

        @Override
        public String readText(Path file) {
            try {
                return Files.readString(file);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        private String contentOf(Path file) throws Exception {
            return Files.readString(file);
        }
    }
}
