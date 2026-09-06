package dtm.ide.index;

import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLexicalSourceTest {

    private static final String SOURCE = """
            package demo;

            public class OrderService {
                private final String label = "class Ghost";

                public int total(int amount) {
                    return amount;
                }
            }
            """;

    @Test
    void locatesDeclarationsWithTheirLineAndColumn() {
        List<JavaLexicalSource.Declared> declared = JavaLexicalSource.declarations(SOURCE);

        JavaLexicalSource.Declared type = named(declared, "OrderService");
        assertEquals(SymbolKind.CLASS, type.kind());
        assertEquals(2, type.range().start().line());
        assertEquals("public class ".length(), type.range().start().col());

        JavaLexicalSource.Declared method = named(declared, "total");
        assertEquals(SymbolKind.METHOD, method.kind());
        assertEquals(5, method.range().start().line());

        assertEquals(SymbolKind.FIELD, named(declared, "label").kind());
    }

    @Test
    void nestsMembersUnderTheEnclosingType() {
        List<DocumentSymbol> outline = JavaLexicalSource.outline(SOURCE);

        assertEquals(1, outline.size());
        DocumentSymbol type = outline.getFirst();
        assertEquals("OrderService", type.name());
        assertEquals(List.of("label", "total"),
                type.children().stream().map(DocumentSymbol::name).sorted().toList());
    }

    @Test
    void nestsInnerTypesUnderTheOuterType() {
        String source = """
                class Outer {
                    void outerMethod() {}
                    static class Inner {
                        void innerMethod() {}
                    }
                }
                """;

        List<DocumentSymbol> outline = JavaLexicalSource.outline(source);

        assertEquals(1, outline.size());
        DocumentSymbol outer = outline.getFirst();
        DocumentSymbol inner = outer.children().stream()
                .filter(child -> "Inner".equals(child.name())).findFirst().orElseThrow();
        assertEquals(List.of("innerMethod"),
                inner.children().stream().map(DocumentSymbol::name).toList());
    }

    @Test
    void ignoresDeclarationsWrittenInsideCommentsAndStrings() {
        String source = """
                // class CommentGhost
                class Real {
                    String text = "class StringGhost";
                }
                """;

        List<String> names = JavaLexicalSource.declarations(source).stream()
                .map(JavaLexicalSource.Declared::name).toList();

        assertTrue(names.contains("Real"));
        assertFalse(names.contains("CommentGhost"), names.toString());
        assertFalse(names.contains("StringGhost"), names.toString());
    }

    @Test
    void reportsOccurrencePositionsOutsideCommentsAndStrings() {
        String source = "class A {\n  Target one;\n  // Target\n  String s = \"Target\";\n}\n";
        String masked = JavaLexicalSource.mask(source);
        int[] lineStarts = JavaLexicalSource.lineStarts(masked);

        List<int[]> spans = JavaLexicalSource.occurrences(masked, Set.of("Target"));

        assertEquals(1, spans.size());
        assertEquals(1, JavaLexicalSource.rangeOf(lineStarts, spans.getFirst()[0],
                spans.getFirst()[1]).start().line());
        assertEquals(2, JavaLexicalSource.rangeOf(lineStarts, spans.getFirst()[0],
                spans.getFirst()[1]).start().col());
    }

    @Test
    void maskingKeepsOffsetsAlignedWithTheOriginalSource() {
        String masked = JavaLexicalSource.mask(SOURCE);

        assertEquals(SOURCE.length(), masked.length());
        assertEquals(JavaLexicalSource.lineStarts(SOURCE).length,
                JavaLexicalSource.lineStarts(masked).length);
    }

    private static JavaLexicalSource.Declared named(List<JavaLexicalSource.Declared> declared,
                                                    String name) {
        return declared.stream().filter(entry -> name.equals(entry.name()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        name + " nao encontrado em " + declared.stream()
                                .map(JavaLexicalSource.Declared::name).toList()));
    }
}
