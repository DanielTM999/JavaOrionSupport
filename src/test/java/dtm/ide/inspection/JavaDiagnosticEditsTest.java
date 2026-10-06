package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaDiagnosticEditsTest {
    @org.junit.jupiter.api.Test void editingOneErrorKeepsAnother() {
        var first = new dtm.stools.component.panels.editor.code.diagnostics.Diagnostic(0, 0, 3,
                dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.ERROR, "first");
        var second = new dtm.stools.component.panels.editor.code.diagnostics.Diagnostic(1, 0, 3,
                dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.ERROR, "second");
        var result = JavaDiagnosticEdits.retainUnaffected(java.util.List.of(first, second), "bad\nbad", "good\nbad");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(second), result);
    }


    @Test
    void formattingAndCommentsPreserveCodeButChangedTokensDoNot() {
        assertTrue(JavaDiagnosticEdits.sameCode("class A { int x; }",
                "class A {\n // note\n int x; }"));
        assertFalse(JavaDiagnosticEdits.sameCode("int a;", "int b;"));
        assertFalse(JavaDiagnosticEdits.sameCode("int a = 1;", "int a = 2;"));
        assertFalse(JavaDiagnosticEdits.sameCode("String s = \"old\";", "String s = \"new\";"));
        assertFalse(JavaDiagnosticEdits.sameCode("a + +b;", "a++b;"));
        assertFalse(JavaDiagnosticEdits.sameCode("double x = 1.0;", "double x = 1 . 0;"));
    }

    @Test
    void insertedNewlineMovesDiagnosticAndRetainsItsMetadata() {
        String before = "class A {\n    int field;\n}";
        String after = "class A {\n\n    int field;\n}";
        Diagnostic previous = new Diagnostic(1, 8, 1, 13, DiagnosticSeverity.HINT,
                "unused", "unused.field", null, true);

        Diagnostic moved = JavaDiagnosticEdits.move(List.of(previous), before, after).getFirst();

        assertEquals(2, moved.startLine());
        assertEquals(8, moved.startCol());
        assertEquals(13, moved.endCol());
        assertTrue(moved.unnecessary());
        assertEquals(previous.message(), moved.message());
    }

    @Test
    void insertionAtDiagnosticStartMovesTheWholeRange() {
        Diagnostic previous = new Diagnostic(0, 4, 0, 9, DiagnosticSeverity.ERROR, "error");
        Diagnostic moved = JavaDiagnosticEdits.move(List.of(previous), "int field;", "int  field;").getFirst();
        assertEquals(5, moved.startCol());
        assertEquals(10, moved.endCol());
    }

    @Test
    void multipleFormattingEditsKeepTokenRangesAligned() {
        String before = "class A {\n int first;\n int second;\n}";
        String after = "class A {\n\n int first;\n\n int second;\n}";
        Diagnostic previous = new Diagnostic(2, 5, 2, 11, DiagnosticSeverity.HINT, "unused");

        Diagnostic moved = JavaDiagnosticEdits.move(List.of(previous), before, after).getFirst();

        assertEquals(4, moved.startLine());
        assertEquals(5, moved.startCol());
        assertEquals(11, moved.endCol());
    }
}
