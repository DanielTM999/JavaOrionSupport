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
    void compactsAMultilineDiagnosticToItsFirstMeaningfulLine() {
        Diagnostic broad = new Diagnostic(1, 0, 6, 1, DiagnosticSeverity.ERROR,
                "Syntax error on token(s), misplaced construct(s)", "Java", null);

        Diagnostic compact = DiagnosticRanges.compactMultiline(List.of(broad), SOURCE).getFirst();

        assertEquals(2, compact.startLine());
        assertEquals(2, compact.endLine());
        assertEquals(0, compact.startCol());
        assertEquals("@Entity".length(), compact.endCol());
        assertEquals(broad.message(), compact.message());
        assertEquals(broad.severity(), compact.severity());
        assertEquals(broad.source(), compact.source());
    }

    @Test
    void keepsThePreciseRangeOfASingleLineDiagnostic() {
        Diagnostic exact = new Diagnostic(5, 4, 5, 11, DiagnosticSeverity.ERROR,
                "unknown symbol", "Java", null);

        assertEquals(exact, DiagnosticRanges.compactMultiline(List.of(exact), SOURCE).getFirst());
    }

    @Test
    void excludesTheCarriageReturnFromAWindowsLineLength() {
        String windows = "class Demo {\r\n    missing();\r\n}\r\n";
        Diagnostic broad = new Diagnostic(1, 4, 2, 1, DiagnosticSeverity.ERROR,
                "error", "Java", null);

        Diagnostic compact = DiagnosticRanges.compactMultiline(List.of(broad), windows).getFirst();

        assertEquals(1, compact.startLine());
        assertEquals(1, compact.endLine());
        assertEquals("    missing();".length(), compact.endCol());
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
