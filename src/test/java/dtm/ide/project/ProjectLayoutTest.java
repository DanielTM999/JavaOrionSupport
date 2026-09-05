package dtm.ide.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLayoutTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
    }

    @Test
    void aProjectWithoutChoicesKeepsTheConvention() {
        assertTrue(ProjectLayout.of(root).isEmpty());

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        JavaModule module = descriptor.rootModule();

        assertTrue(module.existingSourceRoots().contains(root.resolve("src/main/java")));
        assertTrue(module.existingTestRoots().contains(root.resolve("src/test/java")));
    }

    @Test
    void anExtraFolderMarkedAsSourceEntersTheModule() throws IOException {
        Path generated = root.resolve("src/generated");
        Files.createDirectories(generated);

        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(generated, ProjectLayout.Role.SOURCE);
        layout.save();

        JavaModule module = JavaProjectConventions.describe(root).rootModule();
        assertTrue(module.existingSourceRoots().contains(generated));
        assertTrue(module.existingSourceRoots().contains(root.resolve("src/main/java")));
    }

    @Test
    void anExcludedFolderLeavesTheModule() {
        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(root.resolve("src/test/java"), ProjectLayout.Role.EXCLUDED);
        layout.save();

        JavaModule module = JavaProjectConventions.describe(root).rootModule();
        assertFalse(module.existingTestRoots().contains(root.resolve("src/test/java")));
    }

    @Test
    void aFolderCanBeMovedFromSourcesToTests() throws IOException {
        Path extra = root.resolve("src/it/java");
        Files.createDirectories(extra);

        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(extra, ProjectLayout.Role.TEST);
        layout.save();

        JavaModule module = JavaProjectConventions.describe(root).rootModule();
        assertTrue(module.existingTestRoots().contains(extra));
        assertFalse(module.existingSourceRoots().contains(extra));
    }

    @Test
    void theChoicesSurviveAReload() {
        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(root.resolve("src/main/java"), ProjectLayout.Role.SOURCE);
        layout.save();

        assertEquals(ProjectLayout.Role.SOURCE,
                ProjectLayout.of(root).roleOf(root.resolve("src/main/java")));
    }

    @Test
    void aFolderOutsideTheProjectIsIgnored() {
        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(root.getParent().resolve("outro"), ProjectLayout.Role.SOURCE);

        assertTrue(layout.isEmpty());
    }

    @Test
    void clearingARoleGoesBackToTheConvention() {
        ProjectLayout layout = ProjectLayout.of(root);
        layout.setRole(root.resolve("src/test/java"), ProjectLayout.Role.EXCLUDED);
        layout.setRole(root.resolve("src/test/java"), null);
        layout.save();

        JavaModule module = JavaProjectConventions.describe(root).rootModule();
        assertTrue(module.existingTestRoots().contains(root.resolve("src/test/java")));
    }
}
