package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LampSeverityTest {

    private static Diagnostic of(DiagnosticSeverity severity, String source) {
        return new Diagnostic(4, 0, 4, Integer.MAX_VALUE, severity, "mensagem", source, null);
    }

    @Test
    void aHintOfOursOffersActionsAndDeservesTheLamp() {
        assertTrue(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.HINT, JavaInspection.JPA_EAGER_RELATION.id())));
    }

    @Test
    void aWarningOfOursOffersActionsAndDeservesTheLamp() {
        assertTrue(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.WARNING, JavaInspection.SPRING_UNSATISFIED.id())));
        assertTrue(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.WARNING, JavaInspection.VALUE_UNKNOWN_KEY.id())));
        assertTrue(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.WARNING, JavaInspection.CONFIG_UNKNOWN_KEY.id())));
    }

    @Test
    void anInfoOfOursOffersActionsAndDeservesTheLamp() {
        assertTrue(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.INFO, JavaInspection.INFRA_CACHE_WITHOUT_NAME.id())));
    }

    @Test
    void anErrorOfOursOffersNoSuppressionAndNoLampOfItsOwn() {
        assertFalse(InspectionSuppressions.suppressible(
                of(DiagnosticSeverity.ERROR, JavaInspection.JPA_EAGER_RELATION.id())));
    }

    @Test
    void everyInspectionInTheCatalogueIsSuppressibleWhenNotAnError() {
        for (JavaInspection inspection : JavaInspection.all()) {
            assertTrue(InspectionSuppressions.suppressible(
                            of(DiagnosticSeverity.WARNING, inspection.id())),
                    "deveria oferecer acoes: " + inspection.id());
        }
    }
}
