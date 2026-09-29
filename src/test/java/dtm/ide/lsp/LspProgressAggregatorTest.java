package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspProgressAggregatorTest {

    @Test
    void interleavedTasksNeverMakeTheBarGoBackwards() {
        LspProgressAggregator progress = new LspProgressAggregator();
        int last = progress.initialized("indexando projeto...").percent();

        int[] observed = {
                progress.begin("1", "Importing Maven project(s)", "", 0).percent(),
                progress.report("1", "", "3/7 modulos", 60).percent(),
                progress.begin("2", "Synchronizing projects", "", 0).percent(),
                progress.report("2", "", "", 10).percent(),
                progress.end("1").percent(),
                progress.begin("3", "Building", "compilando", 0).percent(),
                progress.report("3", "", "", 50).percent(),
                progress.end("2").percent(),
                progress.end("3").percent()
        };

        for (int percent : observed) {
            assertTrue(percent >= last, "regrediu de " + last + " para " + percent);
            assertTrue(percent <= LspProgressAggregator.MAX_BEFORE_READY);
            last = percent;
        }
        assertEquals(LspProgressAggregator.MAX_BEFORE_READY, last);
    }

    @Test
    void theLabelShowsTheMostRecentTaskAndHowManyOthersAreRunning() {
        LspProgressAggregator progress = new LspProgressAggregator();
        progress.begin("1", "Importing Maven project(s)", "", -1);

        LspProgressAggregator.Snapshot snapshot =
                progress.report("2", "Building", "modulo-web", 40);

        assertEquals("Building - modulo-web (40%) +1", snapshot.label());
        assertEquals(2, snapshot.active());
    }

    @Test
    void backgroundWorkReportsTheRawPercentageAndBecomesIdle() {
        LspProgressAggregator progress = new LspProgressAggregator();
        progress.restartBackgroundWork();

        assertEquals(30, progress.begin("x", "Building", "", 30).workPercent());
        assertTrue(progress.end("x").idle());
        assertEquals(-1, progress.restartBackgroundWork().workPercent());
    }

    @Test
    void phasesAreRecognisedFromTheServerTitles() {
        assertEquals(LspProgressAggregator.Phase.IMPORT,
                LspProgressAggregator.phaseOf("Importing Maven project(s)"));
        assertEquals(LspProgressAggregator.Phase.BUILD,
                LspProgressAggregator.phaseOf("Building workspace"));
        assertEquals(LspProgressAggregator.Phase.OTHER,
                LspProgressAggregator.phaseOf("Computing hover"));
        assertEquals(LspProgressAggregator.Phase.OTHER,
                LspProgressAggregator.phaseOf("Publishing diagnostics"));
    }

    @Test
    void routineDiagnosticsDoNotShowWorkProgressButBuildDoes() {
        LspProgressAggregator progress = new LspProgressAggregator();
        assertTrue(!progress.begin("1", "Publishing diagnostics", "", -1).visibleWork());
        assertTrue(progress.begin("2", "Building workspace", "", 10).visibleWork());
        assertTrue(!progress.end("2").visibleWork());
    }
}
