package dtm.ide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTypingContextTest {

    private static final String BODY = "class A {\n  void run() {\n    ";

    @Test
    void spaceStarAndHashNeverTrigger() {
        assertFalse(trigger(BODY + "int x = ", ' '));
        assertFalse(trigger(BODY + "/**", '*'));
        assertFalse(trigger(BODY + "a#", '#'));
    }

    @Test
    void parenthesisOnlyTriggersAfterAnAnnotationName() {
        assertFalse(trigger(BODY + "salvar(", '('));
        assertTrue(trigger("class A {\n  @GetMapping(", '('));
        assertTrue(trigger("class A {\n  @org.demo.Marca (", '('));
    }

    @Test
    void colonOnlyTriggersForMethodReferences() {
        assertFalse(trigger(BODY + "int x = a ? b :", ':'));
        assertFalse(trigger(BODY + "case A:", ':'));
        assertTrue(trigger(BODY + "lista.forEach(System.out::", ':'));
    }

    @Test
    void dotTriggersOnlyInCodeAndNotAfterNumbers() {
        assertTrue(trigger(BODY + "lista.", '.'));
        assertTrue(trigger(BODY + "lista2.", '.'));
        assertFalse(trigger(BODY + "double x = 1.", '.'));
        assertFalse(trigger(BODY + "// veja lista.", '.'));
        assertFalse(trigger(BODY + "/* veja lista.", '.'));
        assertFalse(trigger(BODY + "String s = \"a.", '.'));
        assertFalse(trigger(BODY + "String s = \"\"\"\n  lista.", '.'));
        assertTrue(trigger(BODY + "String s = \"a\\\"b\"; lista.", '.'));
    }

    @Test
    void dollarOnlyTriggersInsideStrings() {
        assertTrue(trigger("class A {\n  @Value(\"$", '$'));
        assertFalse(trigger(BODY + "a$", '$'));
    }

    @Test
    void idleCompletionIsRefusedOutsideCode() {
        assertFalse(idle(BODY + "// tex"));
        assertFalse(idle(BODY + "String s = \"abc"));
        assertFalse(idle(BODY + "char c = 'a"));
        assertFalse(idle(BODY + "int x = 123"));
    }

    @Test
    void idleCompletionIsRefusedOnDeclarationNames() {
        assertFalse(idle(BODY + "String nom"));
        assertFalse(idle(BODY + "List<String> ite"));
        assertFalse(idle(BODY + "int[] val"));
        assertFalse(idle(BODY + "int tot"));
        assertFalse(idle("class Fo"));
        assertFalse(idle("public void exec"));
        assertFalse(idle(BODY + "} catch (Exception e"));
    }

    @Test
    void idleCompletionIsAllowedWhileTypingExpressions() {
        assertTrue(idle(BODY + "nom"));
        assertTrue(idle(BODY + "return val"));
        assertTrue(idle(BODY + "Object o = new Tes"));
        assertTrue(idle(BODY + "x = lis"));
        assertTrue(idle(BODY + "if (a > lim"));
        assertTrue(idle("class A extends Bas"));
        assertTrue(idle("class A {\n  private Str"));
        assertTrue(idle("class A {\n  @Override\n  pub"));
        assertTrue(idle(BODY + "lista.ad"));
        assertTrue(idle("import java.ut"));
    }

    private static boolean trigger(String text, char trigger) {
        return JavaTypingContext.allowsTriggerCharacter(text, text.length(), trigger);
    }

    private static boolean idle(String text) {
        return JavaTypingContext.allowsIdleCompletion(text, text.length());
    }
}
