package dtm.ide.coverage;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.stools.component.panels.editor.code.gutter.layer.GutterLayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageGutterDetachTest {

    private static final class FakeEditor implements InvocationHandler {

        GutterLayer layer;
        boolean acceptsLayers = true;
        int repaints;
        int removals;

        IdeEditorContext context() {
            return (IdeEditorContext) Proxy.newProxyInstance(
                    IdeEditorContext.class.getClassLoader(),
                    new Class<?>[] {IdeEditorContext.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "addGutterLayer" -> {
                    if (!acceptsLayers) {
                        return false;
                    }
                    layer = (GutterLayer) args[0];
                    return true;
                }
                case "getGutterLayer" -> {
                    Class<?> type = (Class<?>) args[0];
                    return layer != null && type.isInstance(layer) ? type.cast(layer) : null;
                }
                case "removeGutterLayer" -> {
                    if (layer != args[0]) {
                        return false;
                    }
                    layer = null;
                    removals++;
                    return true;
                }
                case "repaintGutter" -> {
                    repaints++;
                    return null;
                }
                case "filePath" -> {
                    return Path.of("Foo.java");
                }
                default -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return method.getReturnType().isPrimitive() ? false : null;
                }
            }
        }
    }

    private static void paint(CoverageGutterLayer layer) {
        CoverageGutter.apply(layer, new FileCoverage(
                Path.of("Foo.java"), Map.of(1, LineStatus.COVERED), 0, 0));
    }

    @Test
    void attachAddsOneLayerAndReusesItAfterwards() {
        FakeEditor editor = new FakeEditor();
        IdeEditorContext context = editor.context();

        CoverageGutterLayer first = CoverageGutter.attach(context);
        CoverageGutterLayer second = CoverageGutter.attach(context);

        assertNotNull(first);
        assertSame(first, second, "attach nao pode empilhar uma layer nova a cada chamada");
        assertSame(first, editor.layer);
    }

    @Test
    void attachUsesTheStripeDefaultsInsteadOfLombokZeroes() {
        CoverageGutterLayer layer = CoverageGutter.attach(new FakeEditor().context());

        assertEquals(CoverageGutter.STRIPE_WIDTH, layer.getStripeWidth());
        assertEquals(CoverageGutterLayer.Side.RIGHT, layer.getSide());
    }

    @Test
    void attachGivesUpWhenTheHostRefusesTheLayer() {
        FakeEditor editor = new FakeEditor();
        editor.acceptsLayers = false;

        assertNull(CoverageGutter.attach(editor.context()));
    }

    @Test
    void detachRemovesTheLayerFromTheEditor() {
        FakeEditor editor = new FakeEditor();
        IdeEditorContext context = editor.context();
        paint(CoverageGutter.attach(context));

        assertTrue(CoverageGutter.detach(context));

        assertEquals(1, editor.removals);
        assertNull(editor.layer);
    }

    @Test
    void detachClearsTheStripesBeforeHandingTheLayerBack() {
        FakeEditor editor = new FakeEditor();
        IdeEditorContext context = editor.context();
        CoverageGutterLayer layer = CoverageGutter.attach(context);
        paint(layer);
        assertTrue(layer.hasLineColor(1));

        CoverageGutter.detach(context);

        assertFalse(layer.hasLineColor(1));
    }

    @Test
    void detachOnAnEditorWithoutCoverageIsANoOp() {
        FakeEditor editor = new FakeEditor();

        assertFalse(CoverageGutter.detach(editor.context()));
        assertEquals(0, editor.removals);
    }

    @Test
    void missingContextIsIgnored() {
        assertNull(CoverageGutter.attach(null));
        assertFalse(CoverageGutter.detach(null));
    }

    @Test
    void detachingTwiceOnlyRemovesOnce() {
        FakeEditor editor = new FakeEditor();
        IdeEditorContext context = editor.context();
        CoverageGutter.attach(context);

        assertTrue(CoverageGutter.detach(context));
        assertFalse(CoverageGutter.detach(context));

        assertEquals(1, editor.removals);
    }
}
