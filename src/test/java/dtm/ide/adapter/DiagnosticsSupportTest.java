package dtm.ide.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsSupportTest {

    @Test
    void unusedMethodsBecomeFadeOnlyHintsOnTheirName() {
        String text = String.join("\n",
                "class Service {",
                "    private int orphan() { return 1; }",
                "    int used() { return 2; }",
                "}");

        List<dtm.stools.component.panels.editor.code.diagnostics.Diagnostic> hints =
                DiagnosticsSupport.unusedMethodDiagnostics(text, List.of(), names -> Set.of("orphan"));

        assertEquals(1, hints.size());
        var hint = hints.getFirst();
        assertEquals(1, hint.startLine());
        assertEquals(text.split("\n")[1].indexOf("orphan"), hint.startCol());
        assertEquals(hint.startCol() + "orphan".length(), hint.endCol());
        assertEquals("unused", hint.source());
        org.junit.jupiter.api.Assertions.assertTrue(hint.isFadeOnly());
    }

    @Test
    void doesNotDuplicateWhatTheServerAlreadyMarkedAsUnnecessary() {
        String text = "class Service {\n    private int orphan() { return 1; }\n}";
        int col = text.split("\n")[1].indexOf("orphan");
        var fromServer = new dtm.stools.component.panels.editor.code.diagnostics.Diagnostic(1, col, 1, col + 6,
                dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.WARNING,
                "never used locally", "Java", null, true);

        assertEquals(List.of(), DiagnosticsSupport.unusedMethodDiagnostics(text, List.of(fromServer),
                names -> Set.of("orphan")));
    }

    @Test
    void unusedFieldHintFadesOnlyTheFieldName() {
        String text = "class Service {\n    private int orphan;\n}";
        var hints = DiagnosticsSupport.unusedFieldDiagnostics(text, List.of(), names -> Set.of("orphan"));

        assertEquals(1, hints.size());
        var hint = hints.getFirst();
        assertEquals(1, hint.startLine());
        assertEquals(text.split("\n")[1].indexOf("orphan"), hint.startCol());
        assertEquals("unused.field", hint.source());
        assertTrue(hint.isFadeOnly());
    }
}
