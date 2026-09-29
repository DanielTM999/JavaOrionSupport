package dtm.ide;

import dtm.ide.build.BuildDiagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProblemsCoordinatorTest {

    @TempDir
    Path root;

    @Test
    void replacingAReportReturnsOldAndNewPathsAndDropsStaleDiagnostics() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path oldFile = root.resolve("Old.java");
        Path newFile = root.resolve("New.java");
        coordinator.replaceBuild(List.of(problem(oldFile, "old")));

        var affected = coordinator.replaceBuild(List.of(problem(newFile, "new")));

        assertTrue(affected.containsAll(List.of(oldFile, newFile)));
        assertTrue(coordinator.diagnostics(oldFile).isEmpty());
        assertEquals(1, coordinator.diagnostics(newFile).size());
    }

    @Test
    void deletingAFolderRemovesBuildAndLiveProblemsBelowIt() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path deleted = root.resolve("module");
        Path file = deleted.resolve("App.java");
        coordinator.replaceBuild(List.of(problem(file, "build")));
        coordinator.publishLive(file, List.of(problem(file, "lsp")));

        coordinator.removeBelow(deleted);

        assertTrue(coordinator.buildProblems().isEmpty());
        assertTrue(coordinator.liveProblems().isEmpty());
        assertTrue(coordinator.diagnostics(file).isEmpty());
    }

    @Test
    void aTestRunDoesNotEraseTheErrorsOfTheLastBuild() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path app = root.resolve("App.java");
        Path test = root.resolve("AppTest.java");
        coordinator.replaceBuild(List.of(new BuildDiagnostic(app, 3, 1, DiagnosticSeverity.ERROR,
                "cannot find symbol", "javac")));

        coordinator.replace(BuildProblemsCoordinator.Channel.TEST, List.of(
                new BuildDiagnostic(test, 9, 1, DiagnosticSeverity.ERROR, "falhou", "JUnit")));

        assertEquals(1, coordinator.diagnostics(app).size());
        assertEquals(1, coordinator.diagnostics(test).size());
        assertEquals(2, coordinator.buildProblems().size());
    }

    @Test
    void compilerErrorsExpireWhenTheLanguageServerPublishesForTheFile() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path example = root.resolve("ActivityExample.java");
        coordinator.replaceBuild(List.of(
                new BuildDiagnostic(example, 29, 1, DiagnosticSeverity.ERROR,
                        "cannot find symbol: class Activity", "javac"),
                new BuildDiagnostic(example, 5, 1, DiagnosticSeverity.WARNING, "regra", "checkstyle")));

        assertTrue(coordinator.supersedeCompilerProblems(example));

        assertEquals(1, coordinator.diagnostics(example).size());
        assertEquals("regra", coordinator.diagnostics(example).getFirst().message());
        assertFalse(coordinator.supersedeCompilerProblems(example));
    }

    @Test
    void editingAFileDropsEveryStaleBuildAndTestMarkerOnIt() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path test = root.resolve("AppTest.java");
        Path other = root.resolve("Other.java");
        coordinator.replace(BuildProblemsCoordinator.Channel.TEST, List.of(
                new BuildDiagnostic(test, 9, 1, DiagnosticSeverity.ERROR, "falhou", "JUnit"),
                problem(other, "outro")));

        assertTrue(coordinator.supersedeAll(test));

        assertTrue(coordinator.diagnostics(test).isEmpty());
        assertEquals(1, coordinator.diagnostics(other).size());
    }

    @Test
    void formattingMovesBuildAndLiveProblemsWithoutClearingThem() {
        BuildProblemsCoordinator coordinator = new BuildProblemsCoordinator();
        Path file = root.resolve("App.java");
        BuildDiagnostic issue = new BuildDiagnostic(file, 2, 9, DiagnosticSeverity.ERROR,
                "issue", "javac");
        coordinator.replaceBuild(List.of(issue));
        coordinator.publishLive(file, List.of(issue));

        assertTrue(coordinator.move(file, "class App {\n    int field;\n}",
                "class App {\n\n    int field;\n}"));

        assertEquals(3, coordinator.buildProblems().getFirst().line());
        assertEquals(3, coordinator.liveProblems().getFirst().line());
        assertEquals(2, coordinator.diagnostics(file).getFirst().startLine());
    }

    private static BuildDiagnostic problem(Path file, String message) {
        return new BuildDiagnostic(file, 1, 1, DiagnosticSeverity.ERROR, message, "test");
    }
}
