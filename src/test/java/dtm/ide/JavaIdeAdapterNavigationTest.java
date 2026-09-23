package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.provider.TokenRenderCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenClassifierProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenColorProvider;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaIdeAdapterNavigationTest {

    @Test
    void aMissingDefinitionDoesNotMeanTheClickWasOnTheDeclaration() {
        assertFalse(JavaIdeAdapter.isOwnDeclaration(java.util.List.of(), click(
                Path.of("Demo.java"), MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void sameLineDeclarationMustStillContainTheClickedWord() {
        var context = click(Path.of("Demo.java"), MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK);
        String uri = context.filePath().toAbsolutePath().toUri().toString();
        assertTrue(JavaIdeAdapter.isOwnDeclaration(java.util.List.of(
                dtm.stools.component.panels.editor.code.api.Location.of(uri,
                        dtm.stools.component.panels.editor.code.api.Range.of(0, 6, 0, 10))), context));
        assertFalse(JavaIdeAdapter.isOwnDeclaration(java.util.List.of(
                dtm.stools.component.panels.editor.code.api.Location.of(uri,
                        dtm.stools.component.panels.editor.code.api.Range.of(0, 20, 0, 24))), context));
    }

    @Test
    void semanticEmptyResultDoesNotFallBackToALocalHomonym() throws Exception {
        JavaIdeAdapter adapter = new JavaIdeAdapter();
        var lsp = new dtm.ide.lsp.JdtLsService(null, null, null, null) {
            @Override public boolean isInteractive() { return true; }
            @Override public dtm.ide.navigation.JavaNavigation.Result navigation(
                    dtm.ide.navigation.JavaNavigation.Kind kind, Path file, String text, int line, int col) {
                return dtm.ide.navigation.JavaNavigation.Result.of(dtm.ide.navigation.JavaNavigation.Status.COMPLETE);
            }
        };
        var field = JavaIdeAdapter.class.getDeclaredField("jdtLs");
        field.setAccessible(true);
        field.set(adapter, lsp);
        String source = "class A { void m(int x) { use(x); } }";
        var result = adapter.resolveNavigation(Path.of("A.java"), source, 0, source.lastIndexOf("x"),
                dtm.ide.navigation.JavaNavigation.Kind.DEFINITION);
        assertEquals(dtm.ide.navigation.JavaNavigation.Status.COMPLETE, result.status());
        assertTrue(result.locations().isEmpty());
    }

    @Test
    void aStaleAnswerIsRetriedWhileTheEditorStillShowsTheSameText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JavaIdeAdapter adapter = adapterAnswering(calls, 1);
        String source = "class A { B b; }";
        var request = new JavaIdeAdapter.NavigationRequest(source, 0, 10);

        var resolved = adapter.resolveCurrent(() -> request, request, Path.of("A.java"),
                dtm.ide.navigation.JavaNavigation.Kind.DEFINITION, false);

        assertEquals(dtm.ide.navigation.JavaNavigation.Status.COMPLETE, resolved.result().status());
        assertEquals(2, calls.get());
    }

    @Test
    void aClickIsNotRetriedAgainstTextTheUserHasSinceChanged() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JavaIdeAdapter adapter = adapterAnswering(calls, 5);
        var request = new JavaIdeAdapter.NavigationRequest("class A { B b; }", 0, 10);
        var edited = new JavaIdeAdapter.NavigationRequest("class A { B bb; }", 0, 10);

        var resolved = adapter.resolveCurrent(() -> edited, request, Path.of("A.java"),
                dtm.ide.navigation.JavaNavigation.Kind.DEFINITION, false);

        assertEquals(dtm.ide.navigation.JavaNavigation.Status.STALE, resolved.result().status());
        assertEquals(1, calls.get());
    }

    @Test
    void caretNavigationFollowsTheLatestEditorText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JavaIdeAdapter adapter = adapterAnswering(calls, 1);
        var request = new JavaIdeAdapter.NavigationRequest("class A { B b; }", 0, 10);
        var edited = new JavaIdeAdapter.NavigationRequest("class A { B bb; }", 0, 10);

        var resolved = adapter.resolveCurrent(() -> edited, request, Path.of("A.java"),
                dtm.ide.navigation.JavaNavigation.Kind.DEFINITION, true);

        assertEquals(dtm.ide.navigation.JavaNavigation.Status.COMPLETE, resolved.result().status());
        assertSame(edited, resolved.request());
    }

    @Test
    void theTargetPositionIsClampedToTheBufferTheEditorHolds() {
        assertArrayEquals(new int[]{1, 3}, JavaIdeAdapter.clampPosition("abc\r\ndef", 7, 40));
        assertArrayEquals(new int[]{0, 0}, JavaIdeAdapter.clampPosition("", 3, 2));
        assertArrayEquals(new int[]{1, 2}, JavaIdeAdapter.clampPosition("abc\ndef", 1, 2));
    }

    private static JavaIdeAdapter adapterAnswering(AtomicInteger calls, int staleAnswers) throws Exception {
        JavaIdeAdapter adapter = new JavaIdeAdapter();
        var lsp = new dtm.ide.lsp.JdtLsService(null, null, null, null) {
            @Override public boolean isInteractive() { return true; }
            @Override public dtm.ide.navigation.JavaNavigation.Result navigation(
                    dtm.ide.navigation.JavaNavigation.Kind kind, Path file, String text, int line, int col) {
                return dtm.ide.navigation.JavaNavigation.Result.of(calls.incrementAndGet() <= staleAnswers
                        ? dtm.ide.navigation.JavaNavigation.Status.STALE
                        : dtm.ide.navigation.JavaNavigation.Status.COMPLETE);
            }
        };
        var field = JavaIdeAdapter.class.getDeclaredField("jdtLs");
        field.setAccessible(true);
        field.set(adapter, lsp);
        return adapter;
    }

    private static final IdeEditorContext EDITOR =(IdeEditorContext) Proxy.newProxyInstance(
            IdeEditorContext.class.getClassLoader(),
            new Class<?>[]{IdeEditorContext.class},
            (proxy, method, args) -> null);

    @Test
    void decompiledClassEditorGetsJavaHighlightAndHover() {
        CodeEditor editor = new CodeEditor();
        editor.setText("class Demo { }");

        JavaIdeAdapter.applyClassFileEditorProviders(editor, Path.of("Demo.java"),
                new JavaEditorRegistry(), context -> new HoverInfo("doc"));

        assertNotNull(editor.getTokenizerProvider(), "faltou o tokenizer java");
        assertNotNull(editor.getTokenClassifierProvider());
        assertNotNull(editor.getTokenColorProvider());
        assertNotNull(editor.getTokenRenderProvider());
        assertTrue(editor.isSyntaxHighlightEnabled());
        assertNotNull(editor.getTextArea().getHoverDocumentationProvider());
    }

    @Test
    void decompiledClassEditorHighlightsAfterTheTabIsOpened() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "o editor precisa de ambiente grafico");
        AtomicInteger renders = new AtomicInteger();
        CodeEditor editor = new CodeEditor();
        editor.addProvider(new DefaultTokenClassifierProvider());
        editor.addProvider(new DefaultTokenColorProvider());
        editor.addProvider((TokenRenderCodeEditorProvider)
                (tokens, colors, area) -> renders.incrementAndGet());

        JFrame frame = new JFrame();
        JPanel host = new JPanel(new BorderLayout());
        frame.setContentPane(host);
        frame.setSize(400, 300);
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                editor.setText("package java.util; public interface List { int size(); }");
                JavaIdeAdapter.applyClassFileEditorProviders(editor, Path.of("List.java"),
                        new JavaEditorRegistry(), context -> null);
                host.add(editor, BorderLayout.CENTER);
                host.revalidate();
                host.remove(editor);
                host.revalidate();
                host.add(editor, BorderLayout.CENTER);
                host.revalidate();
            });
            awaitRender(renders);
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }

        assertTrue(renders.get() > 0,
                "o destaque precisa ser reaplicado depois que a aba abre");
    }

    private static void awaitRender(AtomicInteger renders) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && renders.get() == 0) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(50);
        }
    }

    @Test
    void decompiledClassEditorKeepsTheProvidersTheIdeAlreadyInstalled() {
        CodeEditor editor = new CodeEditor();
        DefaultTokenColorProvider colors = new DefaultTokenColorProvider();
        editor.addProvider(colors);

        JavaIdeAdapter.applyClassFileEditorProviders(editor, Path.of("Demo.java"),
                new JavaEditorRegistry(), context -> null);

        assertSame(colors, editor.getTokenColorProvider());
    }

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

    @Test
    void readsTheIdentifierUnderTheCaretForApproximateNavigation() {
        String source = """
                class Demo {
                    OrderService service;
                }
                """;

        assertEquals("OrderService", JavaIdeAdapter.identifierAt(source, 1, 4));
        assertEquals("OrderService", JavaIdeAdapter.identifierAt(source, 1, 10));
        assertEquals("OrderService", JavaIdeAdapter.identifierAt(source, 1, 16));
        assertEquals("service", JavaIdeAdapter.identifierAt(source, 1, 20));
    }

    @Test
    void reportsNoIdentifierOutsideAWord() {
        String source = """
                class Demo {
                    int a = 1;
                }
                """;

        assertNull(JavaIdeAdapter.identifierAt(source, 1, 0));
        assertNull(JavaIdeAdapter.identifierAt(source, 5, 0));
        assertNull(JavaIdeAdapter.identifierAt(null, 0, 0));
    }

    private static IdeWordClickContext click(Path path, int button, int modifiers) {
        return new IdeWordClickContext(
                "class Demo {}", path, "Demo", 0, 6, 6, 10,
                button, 1, modifiers, EDITOR);
    }
}
