package dtm.ide.run.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunChainStepTest {

    @Test
    void aStepSurvivesTheRoundTrip() {
        RunChainStep step = new RunChainStep("abc-123", true, false, "Minha API Local");

        List<RunChainStep> decoded = RunChainStep.decodeAll(RunChainStep.encodeAll(List.of(step)));

        assertEquals(1, decoded.size());
        assertEquals(step, decoded.getFirst());
    }

    @Test
    void theOrderIsPreserved() {
        List<RunChainStep> steps = List.of(
                RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("b", false, "JAR"),
                RunChainStep.of("c", true, "Remote"));

        assertEquals(List.of("a", "b", "c"),
                RunChainStep.decodeAll(RunChainStep.encodeAll(steps)).stream()
                        .map(RunChainStep::configurationId).toList());
    }

    @Test
    void waitForExitDefaultsToTrue() {
        assertTrue(RunChainStep.of("a", false, "Maven").waitForExit());
        assertTrue(RunChainStep.decode("a").orElseThrow().waitForExit());
        assertFalse(RunChainStep.decode("a|run|false|Maven").orElseThrow().waitForExit());
    }

    @Test
    void separatorsInsideTheLabelDoNotBreakTheEncoding() {
        RunChainStep step = new RunChainStep("id", false, true, "API | producao\ncom quebra");

        RunChainStep decoded = RunChainStep.decodeAll(
                RunChainStep.encodeAll(List.of(step))).getFirst();

        assertEquals("id", decoded.configurationId());
        assertFalse(decoded.label().contains("|"));
        assertFalse(decoded.label().contains("\n"));
    }

    @Test
    void anEmptyChainEncodesToNothingAndBack() {
        assertEquals("", RunChainStep.encodeAll(List.of()));
        assertEquals("", RunChainStep.encodeAll(null));
        assertTrue(RunChainStep.decodeAll("").isEmpty());
        assertTrue(RunChainStep.decodeAll(null).isEmpty());
    }

    @Test
    void stepsWithoutAConfigurationAreDropped() {
        assertTrue(RunChainStep.decodeAll("|run|true|Sem id").isEmpty());
        assertTrue(RunChainStep.decode("   ").isEmpty());
        assertFalse(RunChainStep.of("", false, "x").isValid());
    }

    @Test
    void theModeRoundTripsInBothDirections() {
        assertTrue(RunChainStep.decode("a|debug|true|X").orElseThrow().debug());
        assertFalse(RunChainStep.decode("a|run|true|X").orElseThrow().debug());
        assertTrue(RunChainStep.decodeAll(
                RunChainStep.encodeAll(List.of(RunChainStep.of("a", true, "X"))))
                .getFirst().debug());
    }

    @Test
    void displayFallsBackToTheIdWhenTheLabelIsGone() {
        assertEquals("Minha API", RunChainStep.of("id", false, "Minha API").display());
        assertEquals("id", RunChainStep.of("id", false, "").display());
    }

    @Test
    void legacyLinesWithoutAllFieldsStillParse() {
        RunChainStep step = RunChainStep.decode("apenas-id").orElseThrow();

        assertEquals("apenas-id", step.configurationId());
        assertFalse(step.debug());
        assertTrue(step.waitForExit());
        assertEquals("", step.label());
    }
}
