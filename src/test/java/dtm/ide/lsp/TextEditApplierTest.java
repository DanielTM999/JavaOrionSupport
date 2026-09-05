package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextEditApplierTest {

    @Test
    void replacesASingleRange() {
        String text = "int valor = 1;";

        String result = TextEditApplier.apply(text,
                new TextEdit(Range.of(0, 4, 0, 9), "total"));

        assertEquals("int total = 1;", result);
    }

    @Test
    void appliesMultipleEditsWithoutShiftingEachOther() {
        String text = "a b c";

        String result = TextEditApplier.apply(text, List.of(
                new TextEdit(Range.of(0, 0, 0, 1), "primeiro"),
                new TextEdit(Range.of(0, 2, 0, 3), "segundo"),
                new TextEdit(Range.of(0, 4, 0, 5), "terceiro")));

        assertEquals("primeiro segundo terceiro", result);
    }

    @Test
    void editsArrivingOutOfOrderStillLandInTheRightPlaces() {
        String text = "a b c";

        String result = TextEditApplier.apply(text, List.of(
                new TextEdit(Range.of(0, 4, 0, 5), "terceiro"),
                new TextEdit(Range.of(0, 0, 0, 1), "primeiro"),
                new TextEdit(Range.of(0, 2, 0, 3), "segundo")));

        assertEquals("primeiro segundo terceiro", result);
    }

    @Test
    void appliesEditsAcrossLines() {
        String text = "linha um\nlinha dois\nlinha tres";

        String result = TextEditApplier.apply(text, List.of(
                new TextEdit(Range.of(0, 6, 0, 8), "1"),
                new TextEdit(Range.of(2, 6, 2, 10), "3")));

        assertEquals("linha 1\nlinha dois\nlinha 3", result);
    }

    @Test
    void replacesAMultiLineRange() {
        String text = "inicio\nmeio\nfim";

        String result = TextEditApplier.apply(text,
                new TextEdit(Range.of(0, 6, 2, 0), "\n"));

        assertEquals("inicio\nfim", result);
    }

    @Test
    void insertsAtAnEmptyRange() {
        String text = "public class A {}";

        String result = TextEditApplier.apply(text,
                new TextEdit(Range.point(0, 0), "import java.util.List;\n\n"));

        assertEquals("import java.util.List;\n\npublic class A {}", result);
    }

    @Test
    void deletesWhenTheNewTextIsEmpty() {
        String text = "int naoUsado = 1;\nint usado = 2;";

        String result = TextEditApplier.apply(text,
                new TextEdit(Range.of(0, 0, 1, 0), ""));

        assertEquals("int usado = 2;", result);
    }

    @Test
    void skipsOverlappingEditsInsteadOfCorruptingTheText() {
        String text = "abcdef";

        String result = TextEditApplier.apply(text, List.of(
                new TextEdit(Range.of(0, 0, 0, 4), "X"),
                new TextEdit(Range.of(0, 2, 0, 6), "Y")));

        assertEquals("Xef", result, "a edicao sobreposta deve ser descartada, nao aplicada por cima");
    }

    @Test
    void clampsPositionsBeyondTheEndOfTheDocument() {
        String text = "curto";

        String result = TextEditApplier.apply(text,
                new TextEdit(new Range(new Position(0, 0), new Position(99, 0)), "novo"));

        assertEquals("novo", result);
    }

    @Test
    void returnsTheOriginalWhenThereIsNothingToApply() {
        assertEquals("igual", TextEditApplier.apply("igual", List.of()));
        assertEquals("igual", TextEditApplier.apply("igual", (List<TextEdit>) null));
    }

    @Test
    void handlesNullNewTextAsDeletion() {
        String result = TextEditApplier.apply("abc",
                new TextEdit(Range.of(0, 0, 0, 1), null));

        assertEquals("bc", result);
    }
}
