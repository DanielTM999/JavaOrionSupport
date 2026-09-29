package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallParenthesesTest {

    private static final String BODY = "class A {\n  void run() {\n    ";

    @Test
    void methodWithParametersKeepsTheCaretInside() {
        assertEquals("add(${0})", insert(BODY + "lista.ad", "", item("add", "add(E e) : boolean", AutoCompleteItem.Kind.METHOD)));
    }

    @Test
    void methodWithoutParametersPutsTheCaretAfter() {
        assertEquals("size()", insert(BODY + "lista.si", "", item("size", "size() : int", AutoCompleteItem.Kind.METHOD)));
    }

    @Test
    void guessedArgumentsBecomeEmptyParentheses() {
        assertEquals("salvar(${0})", insert(BODY + "sal", "",
                item("salvar(cliente, true)", "salvar(Cliente c, boolean f) : void", AutoCompleteItem.Kind.METHOD)));
    }

    @Test
    void unknownSignatureAssumesParameters() {
        assertEquals("processar(${0})", insert(BODY + "proc", "", item("processar", "processar", AutoCompleteItem.Kind.METHOD)));
    }

    @Test
    void classAfterNewBecomesAConstructorCall() {
        assertEquals("Teste(${0})", insert(BODY + "Teste t = new Tes", "",
                item("Teste", "Teste - com.demo", AutoCompleteItem.Kind.CLASS)));
        assertEquals("ArrayList<>(${0})", insert(BODY + "var l = new Arr", "",
                item("ArrayList<>", "ArrayList<E> - java.util", AutoCompleteItem.Kind.CLASS)));
        assertEquals("Teste", insert(BODY + "Tes", "", item("Teste", "Teste - com.demo", AutoCompleteItem.Kind.CLASS)));
        assertEquals("Renovar", insert(BODY + "Object o = renew Ren", "",
                item("Renovar", "Renovar", AutoCompleteItem.Kind.CLASS)));
    }

    @Test
    void constructorItemsFollowTheirSignature() {
        assertEquals("Teste(${0})", insert(BODY + "new Tes", "",
                item("Teste(nome)", "Teste(String nome)", AutoCompleteItem.Kind.CONSTRUCTOR)));
        assertEquals("Teste()", insert(BODY + "new Tes", "",
                item("Teste()", "Teste()", AutoCompleteItem.Kind.CONSTRUCTOR)));
    }

    @Test
    void contextsThatAlreadyHaveOrForbidParenthesesAreLeftAlone() {
        AutoCompleteItem add = item("add", "add(E e) : boolean", AutoCompleteItem.Kind.METHOD);
        assertEquals("add", insert(BODY + "lista.ad", "d(x);", add));
        assertEquals("add", insert(BODY + "lista.ad", " (x);", add));
        assertEquals("add", insert(BODY + "lista.forEach(List::ad", "", add));
        assertEquals("add", insert(BODY + "// lista.ad", "", add));
        assertEquals("add", insert("import static java.util.Collections.ad", "", add));
        assertEquals("Override", insert("class A {\n  @Over", "",
                item("Override", "Override", AutoCompleteItem.Kind.CLASS)));
    }

    @Test
    void otherKindsAndMetadataAreKept() {
        AutoCompleteItem field = item("valor", "valor : int", AutoCompleteItem.Kind.FIELD);
        String text = BODY + "val";
        List<AutoCompleteItem> items = List.of(field);
        assertSame(items, CallParentheses.apply(items, text, text.length() - 3, text.length(), true));

        AutoCompleteItem unused = item("calcular", "calcular(int x) : int", AutoCompleteItem.Kind.METHOD).withUnused(true);
        AutoCompleteItem result = CallParentheses.apply(List.of(unused), text, text.length() - 3, text.length(), true).getFirst();
        assertTrue(result.unused());
        assertEquals("calcular(int x) : int", result.label());
        assertEquals(AutoCompleteItem.Kind.METHOD, result.kind());
    }

    @Test
    void withoutCaretMarkerSupportTheCallIsPlain() {
        String text = BODY + "lista.ad";
        AutoCompleteItem add = item("add", "add(E e) : boolean", AutoCompleteItem.Kind.METHOD);
        assertEquals("add()", CallParentheses.apply(List.of(add), text, text.length() - 2, text.length(), false)
                .getFirst().insertText());
    }

    @Test
    void dollarInNamesIsEscapedForTheSnippet() {
        assertEquals("a\\$1(${0})", insert(BODY + "a", "", item("a$1", "a$1(int x)", AutoCompleteItem.Kind.METHOD)));
    }

    private static String insert(String before, String after, AutoCompleteItem item) {
        String text = before + after;
        int caret = before.length();
        int prefix = caret;
        while (prefix > 0 && Character.isJavaIdentifierPart(text.charAt(prefix - 1))) {
            prefix--;
        }
        return CallParentheses.apply(List.of(item), text, prefix, caret, true).getFirst().insertText();
    }

    private static AutoCompleteItem item(String insert, String label, AutoCompleteItem.Kind kind) {
        return new AutoCompleteItem(insert, label, null, null, null, kind, List.of());
    }
}
