package dtm.ide;

import dtm.ide.build.BuildDiagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static BuildDiagnostic problem(Path file, String message) {
        return new BuildDiagnostic(file, 1, 1, DiagnosticSeverity.ERROR, message, "test");
    }
}
