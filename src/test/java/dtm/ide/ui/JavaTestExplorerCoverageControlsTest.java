package dtm.ide.ui;

import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.FileCoverage;
import dtm.ide.coverage.LineStatus;
import dtm.ide.test.JavaTest;
import dtm.ide.test.JavaTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JavaTestExplorerCoverageControlsTest {

    private static final String RUN_COVERAGE = "Rodar com cobertura";
    private static final String CLEAR_COVERAGE = "Limpar cobertura";

    private static final class LateHost implements JavaTestExplorerPanel.Host {

        boolean coverageSupported;
        int clearCalls;

        @Override
        public List<JavaTest> discover() {
            return List.of();
        }

        @Override
        public void discoverSemantic(List<JavaTest> provisional,
                                     Consumer<List<JavaTest>> onFinished) {
            onFinished.accept(provisional);
        }

        @Override
        public void run(List<JavaTest> tests, Consumer<JavaTestRunner.TestRun> onFinished) {
        }

        @Override
        public void debug(List<JavaTest> tests, Consumer<JavaTestRunner.TestRun> onFinished) {
        }

        @Override
        public boolean supportsCoverage() {
            return coverageSupported;
        }

        @Override
        public void clearCoverage() {
            clearCalls++;
        }

        @Override
        public void cancel() {
        }

        @Override
        public void openFile(Path file, int line) {
        }
    }

    @BeforeEach
    void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "o painel precisa de ambiente grafico");
    }

    private static void onEdt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        RuntimeException[] failure = new RuntimeException[1];
        Error[] error = new Error[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                failure[0] = e;
            } catch (Error e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw error[0];
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static List<AbstractButton> buttonsOf(Container root) {
        List<AbstractButton> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button) {
                found.add(button);
            }
            if (child instanceof Container container) {
                found.addAll(buttonsOf(container));
            }
        }
        return found;
    }

    private static JButton buttonNamed(Container root, String label) {
        return buttonsOf(root).stream()
                .filter(JButton.class::isInstance)
                .map(JButton.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElse(null);
    }

    private static CoverageReport reportWithData() {
        FileCoverage file = new FileCoverage(
                Path.of("Foo.java"), Map.of(1, LineStatus.COVERED), 0, 0);
        return new CoverageReport(Map.of(), Map.of("com.app.Foo", file));
    }

    @Test
    void coverageButtonsAppearWhenSupportArrivesAfterThePanelWasBuilt() throws Exception {
        LateHost host = new LateHost();

        onEdt(() -> {
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(host);
            JButton runCoverage = buttonNamed(panel, RUN_COVERAGE);
            JButton clearCoverage = buttonNamed(panel, CLEAR_COVERAGE);

            assertNotNull(runCoverage, "o botao precisa existir mesmo sem suporte");
            assertNotNull(clearCoverage, "o botao precisa existir mesmo sem suporte");
            assertFalse(runCoverage.isVisible());

            host.coverageSupported = true;
            panel.reload();

            assertTrue(runCoverage.isVisible(),
                    "o botao some para sempre se a capacidade so for lida na construcao");
            assertTrue(clearCoverage.isVisible());
        });
    }

    @Test
    void coverageButtonsDisappearAgainWhenSupportGoesAway() throws Exception {
        LateHost host = new LateHost();
        host.coverageSupported = true;

        onEdt(() -> {
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(host);
            JButton runCoverage = buttonNamed(panel, RUN_COVERAGE);
            assertTrue(runCoverage.isVisible());

            host.coverageSupported = false;
            panel.reload();

            assertFalse(runCoverage.isVisible());
        });
    }

    @Test
    void clearIsOnlyEnabledWhenThereIsCoverageToClear() throws Exception {
        LateHost host = new LateHost();
        host.coverageSupported = true;

        onEdt(() -> {
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(host);
            JButton clearCoverage = buttonNamed(panel, CLEAR_COVERAGE);
            assertFalse(clearCoverage.isEnabled());

            panel.setCoverage(reportWithData());
            assertTrue(clearCoverage.isEnabled());

            panel.setCoverage(null);
            assertFalse(clearCoverage.isEnabled());
        });
    }

    @Test
    void clearButtonDelegatesToTheHost() throws Exception {
        LateHost host = new LateHost();
        host.coverageSupported = true;

        onEdt(() -> {
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(host);
            panel.setCoverage(reportWithData());

            buttonNamed(panel, CLEAR_COVERAGE).doClick();

            assertEquals(1, host.clearCalls);
        });
    }
}
