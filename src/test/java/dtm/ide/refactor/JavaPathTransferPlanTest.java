package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.tree.PathTransfer;
import dtm.ide.api.project.tree.PathTransferKind;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.api.project.tree.ProjectTreeDragOrigin;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPathTransferPlanTest {

    @TempDir
    Path project;

    private Path sources;
    private JavaProjectDescriptor descriptor;

    @BeforeEach
    void setUp() throws Exception {
        sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve("src/main/resources"));
        descriptor = new JavaProjectDescriptor(project, JavaProjectKind.MAVEN, List.of(new JavaModule(project, "demo",
                "demo", "demo", "jar", List.of(sources), List.of(project.resolve("src/test/java")),
                project.resolve("target/classes"))), false, false, 21, null);
    }

    @Test
    void movingAClassToAnotherPackageIsRelevant() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\nclass Foo {}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));

        JavaPathTransferPlan plan = JavaPathTransferPlan.of(move(target, foo, target.resolve("Foo.java")), descriptor);

        assertEquals(1, plan.files().size());
        JavaPathTransferPlan.FileTransfer file = plan.files().getFirst();
        assertEquals("demo.a", file.oldPackage());
        assertEquals("demo.c", file.newPackage());
        assertEquals("demo.c", plan.targetPackage());
        assertFalse(plan.hasConflicts());
    }

    @Test
    void movingOutsideTheSourceRootsIsIgnored() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\nclass Foo {}\n");
        Path resources = project.resolve("src/main/resources");

        JavaPathTransferPlan plan = JavaPathTransferPlan.of(move(resources, foo, resources.resolve("Foo.java")), descriptor);

        assertTrue(plan.isEmpty());
    }

    @Test
    void aRenamedMoveTargetIsAConflict() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\nclass Foo {}\n");
        Path target = Files.createDirectories(sources.resolve("demo/c"));

        JavaPathTransferPlan plan = JavaPathTransferPlan.of(
                move(target, foo, target.resolve("Foo - Copy 1.java")), descriptor);

        assertTrue(plan.hasConflicts());
    }

    @Test
    void movingAPackageFolderComputesBothPackages() throws Exception {
        write("demo/a/sub/Bar.java", "package demo.a.sub;\nclass Bar {}\n");
        Path folder = sources.resolve("demo/a/sub");
        Path target = Files.createDirectories(sources.resolve("demo/x"));

        JavaPathTransferPlan plan = JavaPathTransferPlan.of(move(target, folder, target.resolve("sub")), descriptor);

        assertEquals(1, plan.folders().size());
        assertEquals("demo.a.sub", plan.folders().getFirst().oldPackage());
        assertEquals("demo.x.sub", plan.folders().getFirst().newPackage());
    }

    @Test
    void relocateMergesEditsOfTheMovedFileUnderTheNewPathAndDropsResourceOperations() {
        Path oldFoo = sources.resolve("demo/a/Foo.java");
        Path newFoo = sources.resolve("demo/c/Foo.java");
        Path user = sources.resolve("demo/b/User.java");
        TextEdit imports = new TextEdit(new Range(Position.of(0, 15), Position.of(2, 0)), "\n\nimport demo.a.Sibling;\n\n");
        TextEdit packageName = new TextEdit(new Range(Position.of(0, 8), Position.of(0, 14)), "demo.c");
        TextEdit userImport = new TextEdit(new Range(Position.of(2, 0), Position.of(2, 18)), "import demo.c.Foo;");
        IdeWorkspaceEdit edit = new IdeWorkspaceEdit(List.of(
                new IdeWorkspaceEdit.TextEdits(oldFoo, List.of(imports)),
                new IdeWorkspaceEdit.TextEdits(user, List.of(userImport)),
                new IdeWorkspaceEdit.TextEdits(oldFoo, List.of(packageName)),
                new IdeWorkspaceEdit.RenameFile(oldFoo, newFoo)));

        IdeWorkspaceEdit relocated = JavaPathTransferPlan.relocate(edit, Map.of(oldFoo, newFoo));

        assertEquals(2, relocated.operations().size());
        assertTrue(relocated.operations().stream().noneMatch(IdeWorkspaceEdit.RenameFile.class::isInstance));
        assertEquals(List.of(imports, packageName), relocated.editsFor(newFoo));
        assertEquals(List.of(userImport), relocated.editsFor(user));
        assertTrue(relocated.editsFor(oldFoo).isEmpty());
    }

    @Test
    void relocatePathMapsFilesInsideAMovedFolder() {
        Path folder = sources.resolve("demo/a/sub");
        Path target = sources.resolve("demo/x/sub");

        assertEquals(target.resolve("inner/Deep.java"),
                JavaPathTransferPlan.relocatePath(folder.resolve("inner/Deep.java"), Map.of(folder, target)));
        assertEquals(sources.resolve("demo/b/User.java"),
                JavaPathTransferPlan.relocatePath(sources.resolve("demo/b/User.java"), Map.of(folder, target)));
    }

    @Test
    void restrictToKeepsOnlyCompletedTransfersWithTheirFinalNames() throws Exception {
        Path foo = write("demo/a/Foo.java", "package demo.a;\nclass Foo {}\n");
        Path bar = write("demo/a/Bar.java", "package demo.a;\nclass Bar {}\n");
        Path target = sources.resolve("demo/a");
        PathTransferRequest request = new PathTransferRequest(project, PathTransferKind.COPY, target, List.of(
                new PathTransfer(foo, target.resolve("FooCopy.java")),
                new PathTransfer(bar, target.resolve("BarCopy.java"))), ProjectTreeDragOrigin.INTERNAL);

        JavaPathTransferPlan plan = JavaPathTransferPlan.of(request, descriptor)
                .restrictTo(List.of(new PathTransfer(foo, target.resolve("FooCopy2.java"))));

        assertEquals(1, plan.files().size());
        assertEquals("FooCopy2", plan.files().getFirst().newType());
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
}
