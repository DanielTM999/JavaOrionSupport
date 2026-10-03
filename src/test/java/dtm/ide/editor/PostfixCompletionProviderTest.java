package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostfixCompletionProviderTest {

    @Test
    void wrapsTheExpressionBeforeTheDotAndRemovesIt() {
        String text = "class A {\n    void m() {\n        names.fo\n    }\n}";
        int caret = text.indexOf("names.fo") + "names.fo".length();

        List<AutoCompleteItem> items = PostfixCompletionProvider.suggestions(text, caret);

        assertEquals(List.of("for", "fori"), items.stream().map(AutoCompleteItem::label).toList());
        AutoCompleteItem forEach = items.getFirst();
        assertEquals("for (var ${1:item} : names) {\n    $0\n}", forEach.insertText());
        assertEquals(AutoCompleteItem.Kind.SNIPPET, forEach.kind());
        TextEdit removal = forEach.additionalTextEdits().getFirst();
        assertEquals(2, removal.range().start().line());
        assertEquals(8, removal.range().start().col());
        assertEquals(14, removal.range().end().col());
    }

    @Test
    void understandsCallsArrayAccessAndChainedMembers() {
        String text = "        service.find(id, \"a(b\").items[0].nn";
        List<AutoCompleteItem> items = PostfixCompletionProvider.suggestions(text, text.length());

        assertEquals(List.of("nn"), items.stream().map(AutoCompleteItem::label).toList());
        assertEquals("if (service.find(id, \"a(b\").items[0] != null) {\n    $0\n}", items.getFirst().insertText());
    }

    @Test
    void escapesDollarSignsOfTheExpression() {
        String text = "x = a$b.sout";
        AutoCompleteItem item = PostfixCompletionProvider.suggestions(text, text.length()).getFirst();

        assertEquals("System.out.println(a\\$b);$0", item.insertText());
    }

    @Test
    void ignoresNumbersAndMissingExpressions() {
        assertTrue(PostfixCompletionProvider.suggestions("double d = 1.v", 14).isEmpty());
        assertTrue(PostfixCompletionProvider.suggestions("    .var", 8).isEmpty());
        assertTrue(PostfixCompletionProvider.suggestions("value var", 9).isEmpty());
    }

    @Test
    void offersEveryTemplateRightAfterTheDot() {
        String text = "value.";
        assertEquals(11, PostfixCompletionProvider.suggestions(text, text.length()).size());
    }
}
