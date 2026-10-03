package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenModuleRenameTest {

    private static final String ROOT_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.acme</groupId>
                <artifactId>shop</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <modules>
                    <module>core</module>
                    <module>apps/web</module>
                </modules>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>${project.groupId}</groupId>
                            <artifactId>core</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    private static final String CORE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>core</artifactId>
            </project>
            """;

    private static final String WEB_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../../pom.xml</relativePath>
                </parent>
                <artifactId>web</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>core</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.other</groupId>
                        <artifactId>core</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @TempDir
    Path project;

    private JavaProjectDescriptor descriptor;

    @BeforeEach
    void createProject() throws IOException {
        write(project.resolve("pom.xml"), ROOT_POM);
        write(project.resolve("core/pom.xml"), CORE_POM);
        write(project.resolve("apps/web/pom.xml"), WEB_POM);
        descriptor = JavaProjectConventions.describe(project);
    }

    @Test
    void moduleFolderOfAMultiModuleProjectIsATarget() {
        Optional<MavenModuleRename.Target> target = MavenModuleRename.of(descriptor, project.resolve("apps/web"), null);

        assertTrue(target.isPresent());
        assertEquals(norm(project.resolve("pom.xml")), target.get().parentPom());
        assertEquals("web", target.get().artifactId());
    }

    @Test
    void regularFoldersAndTheReactorRootAreNotTargets() throws IOException {
        Files.createDirectories(project.resolve("core/src/main/java"));

        assertTrue(MavenModuleRename.of(descriptor, project, null).isEmpty());
        assertTrue(MavenModuleRename.of(descriptor, project.resolve("apps"), null).isEmpty());
        assertTrue(MavenModuleRename.of(descriptor, project.resolve("core/src/main/java"), null).isEmpty());
    }

    @Test
    void singleModuleProjectsAreNotTargets(@TempDir Path single) throws IOException {
        write(single.resolve("pom.xml"), CORE_POM);
        JavaProjectDescriptor singleDescriptor = JavaProjectConventions.describe(single);

        assertTrue(MavenModuleRename.of(singleDescriptor, single, null).isEmpty());
    }

    @Test
    void renamingTheDirectoryOnlyUpdatesTheModuleEntryAndMovesTheFolder() throws IOException {
        MavenModuleRename.Target target = target("core");

        IdeWorkspaceEdit edit = MavenModuleRename.plan(target, MavenModuleRename.Scope.DIRECTORY, "core-lib", null);

        assertEquals(ROOT_POM.replace("<module>core</module>", "<module>core-lib</module>"),
                apply(edit, project.resolve("pom.xml")));
        assertEquals(CORE_POM, apply(edit, project.resolve("core/pom.xml")));
        assertEquals(WEB_POM, apply(edit, project.resolve("apps/web/pom.xml")));
        IdeWorkspaceEdit.Operation last = edit.operations().getLast();
        assertEquals(new IdeWorkspaceEdit.RenameFile(norm(project.resolve("core")), norm(project.resolve("core-lib"))),
                last);
    }

    @Test
    void renamingTheModuleOnlyChangesArtifactIdAndReferencesButNotTheFolder() throws IOException {
        MavenModuleRename.Target target = target("core");

        IdeWorkspaceEdit edit = MavenModuleRename.plan(target, MavenModuleRename.Scope.MODULE, "shop-core", null);

        String rootPom = apply(edit, project.resolve("pom.xml"));
        assertTrue(rootPom.contains("<module>core</module>"));
        assertTrue(rootPom.contains("<artifactId>shop-core</artifactId>"));
        assertEquals(CORE_POM.replace("<artifactId>core</artifactId>", "<artifactId>shop-core</artifactId>"),
                apply(edit, project.resolve("core/pom.xml")));
        assertEquals(WEB_POM.replaceFirst("(<groupId>com\\.acme</groupId>\\s*)<artifactId>core</artifactId>",
                        "$1<artifactId>shop-core</artifactId>"),
                apply(edit, project.resolve("apps/web/pom.xml")));
        assertFalse(edit.operations().stream().anyMatch(op -> op instanceof IdeWorkspaceEdit.RenameFile));
    }

    @Test
    void renamingBothKeepsTheParentPrefixOfNestedModuleEntries() throws IOException {
        MavenModuleRename.Target target = target("apps/web");

        IdeWorkspaceEdit edit = MavenModuleRename.plan(target, MavenModuleRename.Scope.BOTH, "storefront", null);

        assertTrue(apply(edit, project.resolve("pom.xml")).contains("<module>apps/storefront</module>"));
        String webPom = apply(edit, project.resolve("apps/web/pom.xml"));
        assertTrue(webPom.contains("<artifactId>storefront</artifactId>"));
        assertTrue(webPom.contains("<artifactId>shop</artifactId>"));
        assertEquals(new IdeWorkspaceEdit.RenameFile(norm(project.resolve("apps/web")),
                        norm(project.resolve("apps/storefront"))),
                edit.operations().getLast());
    }

    @Test
    void renamingAnAggregatorUpdatesTheParentOfItsChildren(@TempDir Path nested) throws IOException {
        write(nested.resolve("pom.xml"), """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>shop</artifactId>
                    <packaging>pom</packaging>
                    <modules>
                        <module>services</module>
                    </modules>
                </project>
                """);
        write(nested.resolve("services/pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.acme</groupId>
                        <artifactId>shop</artifactId>
                    </parent>
                    <artifactId>services</artifactId>
                    <packaging>pom</packaging>
                    <modules>
                        <module>billing</module>
                    </modules>
                </project>
                """);
        write(nested.resolve("services/billing/pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.acme</groupId>
                        <artifactId>services</artifactId>
                    </parent>
                    <artifactId>billing</artifactId>
                </project>
                """);
        JavaProjectDescriptor nestedDescriptor = JavaProjectConventions.describe(nested);
        MavenModuleRename.Target target = MavenModuleRename.of(nestedDescriptor, nested.resolve("services"), null)
                .orElseThrow();

        IdeWorkspaceEdit edit = MavenModuleRename.plan(target, MavenModuleRename.Scope.MODULE, "backend", null);

        String billing = apply(edit, nested.resolve("services/billing/pom.xml"));
        assertTrue(billing.contains("<artifactId>backend</artifactId>"));
        assertTrue(billing.contains("<artifactId>billing</artifactId>"));
    }

    @Test
    void validationRejectsDuplicatesExistingFoldersAndUnchangedNames() throws IOException {
        MavenModuleRename.Target target = target("core");
        Files.createDirectories(project.resolve("taken"));

        assertEquals(Optional.of(MavenModuleRename.Problem.ARTIFACT_IN_USE),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.MODULE, "web"));
        assertEquals(Optional.of(MavenModuleRename.Problem.EXISTS),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.DIRECTORY, "taken"));
        assertEquals(Optional.of(MavenModuleRename.Problem.SAME),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.BOTH, "core"));
        assertEquals(Optional.of(MavenModuleRename.Problem.SEPARATORS),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.BOTH, "a/b"));
        assertEquals(Optional.of(MavenModuleRename.Problem.INVALID),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.MODULE, "core lib"));
        assertEquals(Optional.of(MavenModuleRename.Problem.EMPTY),
                MavenModuleRename.validate(target, MavenModuleRename.Scope.DIRECTORY, "  "));
        assertTrue(MavenModuleRename.validate(target, MavenModuleRename.Scope.BOTH, "core-lib").isEmpty());
    }

    @Test
    void openEditorTextAndCrlfFilesProduceEditsOnNormalizedPositions() throws IOException {
        write(project.resolve("pom.xml"), "﻿" + ROOT_POM.replace("\n", "\r\n"));
        MavenModuleRename.Target target = target("core");

        IdeWorkspaceEdit edit = MavenModuleRename.plan(target, MavenModuleRename.Scope.DIRECTORY, "kernel", null);

        assertEquals(ROOT_POM.replace("<module>core</module>", "<module>kernel</module>"),
                apply(edit, project.resolve("pom.xml"), ROOT_POM));
    }

    @Test
    void moduleEntryRenameKeepsPrefixAndTrailingSlash() {
        assertEquals("apps/api/", MavenModuleRename.renamedModuleEntry("apps/web/", "api"));
        assertEquals("api", MavenModuleRename.renamedModuleEntry("web", "api"));
        assertEquals("..\\api", MavenModuleRename.renamedModuleEntry("..\\web", "api"));
    }

    private MavenModuleRename.Target target(String relative) {
        return MavenModuleRename.of(descriptor, project.resolve(relative), null).orElseThrow();
    }

    private String apply(IdeWorkspaceEdit edit, Path file) throws IOException {
        return apply(edit, file, Files.readString(file));
    }

    private static String apply(IdeWorkspaceEdit edit, Path file, String text) {
        List<TextEdit> edits = new ArrayList<>(edit.editsFor(file));
        edits.sort(Comparator.comparing((TextEdit e) -> e.range().start().line())
                .thenComparing(e -> e.range().start().col()).reversed());
        StringBuilder builder = new StringBuilder(text);
        for (TextEdit textEdit : edits) {
            builder.replace(offset(text, textEdit.range().start()), offset(text, textEdit.range().end()),
                    textEdit.newText());
        }
        return builder.toString();
    }

    private static int offset(String text, Position position) {
        int line = 0;
        int index = 0;
        while (line < position.line()) {
            index = text.indexOf('\n', index) + 1;
            line++;
        }
        return index + position.col();
    }

    private static Path norm(Path path) {
        return JavaProjectConventions.normalize(path);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
