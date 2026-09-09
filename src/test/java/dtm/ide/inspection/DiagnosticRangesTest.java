package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticRangesTest {

    private static final String SOURCE = """
            package com.example;

            @Entity
            public class Pedido {
                @ManyToOne
                private Cliente cliente;
            }
            """;

    private static Diagnostic unbounded(int line) {
        return new Diagnostic(line, 0, line, Integer.MAX_VALUE, DiagnosticSeverity.HINT,
                "eager", JavaInspection.JPA_EAGER_RELATION.id(), null);
    }

    @Test
    void replacesTheUnboundedEndColumnWithTheRealLineLength() {
        Diagnostic clamped = DiagnosticRanges.clamp(List.of(unbounded(5)), SOURCE).getFirst();

        assertEquals("    private Cliente cliente;".length(), clamped.endCol());
        assertEquals(5, clamped.startLine());
        assertEquals(5, clamped.endLine());
    }

    @Test
    void keepsTheEndOffsetPositiveSoTheEditorCanIntersectIt() {
        Diagnostic clamped = DiagnosticRanges.clamp(List.of(unbounded(5)), SOURCE).getFirst();
        int offsetOfLine = SOURCE.indexOf("    private Cliente cliente;");

        assertTrue(offsetOfLine + clamped.endCol() > 0);
        assertTrue(offsetOfLine + clamped.endCol() > offsetOfLine + clamped.startCol());
    }

    @Test
    void overflowsWithoutTheClamp() {
        int offsetOfLine = SOURCE.indexOf("    private Cliente cliente;");

        assertTrue(offsetOfLine + Integer.MAX_VALUE < 0);
    }

    @Test
    void keepsADiagnosticThatIsAlreadyInsideTheLine() {
        Diagnostic exact = new Diagnostic(3, 0, 3, 5, DiagnosticSeverity.WARNING,
                "ok", JavaInspection.SPRING_UNSATISFIED.id(), null);

        assertEquals(exact, DiagnosticRanges.clamp(List.of(exact), SOURCE).getFirst());
    }

    @Test
    void clampsALineBeyondTheEndOfTheFile() {
        Diagnostic beyond = unbounded(500);

        Diagnostic clamped = DiagnosticRanges.clamp(List.of(beyond), SOURCE).getFirst();

        assertTrue(clamped.startLine() < SOURCE.split("\n", -1).length);
    }

    @Test
    void survivesAnEmptySource() {
        assertEquals(1, DiagnosticRanges.clamp(List.of(unbounded(0)), "").size());
    }

    @Test
    void returnsEmptyForNoDiagnostics() {
        assertTrue(DiagnosticRanges.clamp(List.of(), SOURCE).isEmpty());
    }
}
