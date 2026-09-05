package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaIdeAdapterNavigationTest {

    private static final IdeEditorContext EDITOR = (IdeEditorContext) Proxy.newProxyInstance(
            IdeEditorContext.class.getClassLoader(),
            new Class<?>[]{IdeEditorContext.class},
            (proxy, method, args) -> null);

    @Test
    void ctrlLeftClickOnAJavaWordRequestsDefinitionNavigation() {
        assertTrue(JavaIdeAdapter.isCtrlDefinitionClick(click(
                Path.of("Demo.java"), MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void ordinaryOrRightClicksDoNotNavigate() {
        assertFalse(JavaIdeAdapter.isCtrlDefinitionClick(click(
                Path.of("Demo.java"), MouseEvent.BUTTON1, 0)));
        assertFalse(JavaIdeAdapter.isCtrlDefinitionClick(click(
                Path.of("Demo.java"), MouseEvent.BUTTON3, InputEvent.CTRL_DOWN_MASK)));
        assertFalse(JavaIdeAdapter.isCtrlDefinitionClick(click(
                Path.of("pom.xml"), MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void extractsSafeFieldChainForDebugHover() {
        String source = "var total = pedido.cliente.endereco;";

        assertEquals("pedido.cliente.endereco",
                JavaIdeAdapter.safeDebugExpression(source, source.indexOf("cliente") + 2));
        assertEquals("pedido.cliente.endereco",
                JavaIdeAdapter.safeDebugExpression(source, source.indexOf(';')));
    }

    @Test
    void rejectsMethodCallsForAutomaticDebugEvaluation() {
        String source = "var total = pedido.calcularTotal();";

        assertNull(JavaIdeAdapter.safeDebugExpression(source, source.indexOf('(')));
    }

    private static IdeWordClickContext click(Path path, int button, int modifiers) {
        return new IdeWordClickContext(
                "class Demo {}", path, "Demo", 0, 6, 6, 10,
                button, 1, modifiers, EDITOR);
    }
}
