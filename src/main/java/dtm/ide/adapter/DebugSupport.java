package dtm.ide.adapter;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.concurrent.EdtStallWatchdog;
import dtm.ide.debug.JavaAttachTarget;
import dtm.ide.debug.JavaDebugSession;
import dtm.ide.debug.JavaDebugSnapshot;
import dtm.ide.debug.JavaHotReloadService;
import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.DebugAdapterSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.settings.HotReloadMode;
import dtm.ide.ui.JavaDebugPanel;
import dtm.ide.ui.JavaDebugValuePopup;
import dtm.ide.ui.JavaEvaluateDialog;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.CodeEditor;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class DebugSupport {

    private static final long LSP_DEBUG_POLL_MS = 250;
    private static final Color DEBUG_LINE_COLOR = new Color(227, 100, 100, 80);
    private static final String NAVIGATION_PROGRESS_ID = "javaNavigation";
    private final AtomicLong hotReloadTicket = new AtomicLong();
    private final AtomicLong debugHoverTicket = new AtomicLong();
    private final AtomicLong debugLineTicket = new AtomicLong();
    private final Set<Path> debugSteppedFiles = ConcurrentHashMap.newKeySet();
    private final Object debugSessionLock = new Object();
    private volatile JavaDebugSession debugSession;
    private volatile JavaDebugPanel debugPanel;
    private volatile String debugPanelId;
    private volatile JavaHotReloadService hotReloadService;
    private volatile IdeEditorContext debugLineContext;
    private volatile int debugLine = -1;
    private final Map<String, String> debugLibrarySources = new ConcurrentHashMap<>();
    private volatile CodeEditor debugLibraryEditor;
    private volatile String debugLibraryUri;
    private volatile int debugLibraryLine = -1;
    private final EdtStallWatchdog debugEdtWatchdog = new EdtStallWatchdog("depuracao Java");
    private final AtomicLong debugStartGeneration = new AtomicLong();
    private volatile JavaModule debugModule;
    private volatile Runnable debuggeeTerminator;
    private volatile RunProcessHandle debugProcessHandle;
    private final AtomicBoolean naturalDebugEnd = new AtomicBoolean();
    private volatile JavaDebugValuePopup debugValuePopup;

    private final AdapterHost host;

    public DebugSupport(AdapterHost host) {
        this.host = host;
    }

    public JavaDebugSession session() {
        return debugSession;
    }

    public void clearSession() {
        debugSession = null;
    }

    public JavaDebugValuePopup currentValuePopup() {
        return debugValuePopup;
    }

    public RunProcessHandle processHandle() {
        return debugProcessHandle;
    }

    public void processHandle(RunProcessHandle handle) {
        debugProcessHandle = handle;
    }

    public String panelId() {
        return debugPanelId;
    }

    public void clearPanelId() {
        debugPanelId = null;
    }

    public JavaDebugPanel panel() {
        return debugPanel;
    }

    public void clearPanel() {
        debugPanel = null;
    }

    public AtomicBoolean naturalDebugEnd() {
        return naturalDebugEnd;
    }

    public AtomicLong hotReloadTicket() {
        return hotReloadTicket;
    }

    public AtomicLong hoverTicket() {
        return debugHoverTicket;
    }

    public AtomicLong startGeneration() {
        return debugStartGeneration;
    }

    public EdtStallWatchdog edtWatchdog() {
        return debugEdtWatchdog;
    }

    public JavaModule module() {
        return debugModule;
    }

    public void module(JavaModule module) {
        debugModule = module;
    }

    public Set<Path> steppedFiles() {
        return debugSteppedFiles;
    }

    public void onHover(IdeHoverContext context) {
        if (!isDebugPaused()) {
            debugHoverTicket.incrementAndGet();
            hideDebugValuePopup();
            return;
        }
        String expression = context == null || context.text() == null
                || !JavaProjectConventions.isJava(context.filePath())
                ? null : safeDebugExpression(context.text(), context.offset());
        JavaDebugValuePopup popup = debugValuePopup();
        if (expression != null && popup.isShowing(expression)) {
            return;
        }
        long ticket = debugHoverTicket.incrementAndGet();
        if (expression == null) {
            popup.requestHide();
            return;
        }
        Point location = pointerLocation();
        host.background().submit(() -> {
            try {
                JavaDebugSession session = debugSession;
                JavaDebugSnapshot.Variable value = session == null ? null
                        : session.evaluate(expression, 0);
                if (ticket == debugHoverTicket.get() && isDebugPaused() && value != null) {
                    popup.show(value, location, expression);
                }
            } catch (Exception error) {
                log.debug("Valor sob o cursor indisponivel para '{}': {}", expression,
                        AdapterFailures.rootMessage(error));
                if (ticket == debugHoverTicket.get()) {
                    popup.requestHide();
                }
            }
        });
    }

    public JavaDebugValuePopup debugValuePopup() {
        synchronized (host.monitor()) {
        if (debugValuePopup == null) {
            debugValuePopup = new JavaDebugValuePopup(host.background());
        }
        return debugValuePopup;
        }
    }

    public void hideDebugValuePopup() {
        JavaDebugValuePopup popup = debugValuePopup;
        if (popup != null) popup.hide();
    }

    public static String debugProjectName(JavaModule module) {
        return module == null || module.isAggregator() ? null : module.artifactId();
    }

    public void startDebugSession(int jdwpPort, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule) {
        startDebugSession(JavaAttachTarget.local(jdwpPort), context, terminateDebuggee,
                targetModule, null);
    }

    public void startDebugSession(int jdwpPort, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule,
                                   RunProcessHandle processHandle) {
        startDebugSession(JavaAttachTarget.local(jdwpPort), context, terminateDebuggee,
                targetModule, processHandle);
    }

    boolean awaitLanguageServerForDebug(JavaLanguageServer lsp, long generation)
            throws InterruptedException {
        boolean announced = false;
        while (!lsp.isWorkspaceSettled()) {
            if (generation != debugStartGeneration.get()) {
                return false;
            }
            if (lsp.getState() == LanguageServerState.ERROR) {
                throw new IllegalStateException(text("debug.lspFailed",
                        "O IntelliSense Java falhou; reinicie-o para depurar."));
            }
            if (!announced) {
                announced = true;
                publishDebugSnapshot(JavaDebugSnapshot.starting(text("debug.waitingLsp",
                        "Aguardando o IntelliSense Java terminar de carregar o projeto...")));
            }
            Thread.sleep(LSP_DEBUG_POLL_MS);
        }
        return generation == debugStartGeneration.get();
    }

    public void warmUpDebugAdapter() {
        JavaLanguageServer lsp = host.languageServer();
        DebugAdapterSupport debugAdapter = lsp == null ? null : lsp.extension(DebugAdapterSupport.class);
        if (debugAdapter != null) {
            host.background().submit(debugAdapter::prepareDebugAdapter);
        }
    }

    public void startDebugSession(JavaAttachTarget attachTarget, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule,
                                   RunProcessHandle processHandle) {
        closeDebugSession();
        host.debugActiveFlag().set(true);
        host.requestSetRunButtonRunning(true);
        debugEdtWatchdog.start();
        setDebuggeeTerminator(terminateDebuggee);
        debugProcessHandle = processHandle;
        debugSteppedFiles.clear();
        setDebugEditorAssistEnabled(false);
        debugModule = targetModule;
        JavaDebugPanel panel = ensureDebugPanel();
        panel.update(JavaDebugSnapshot.starting("Preparando depurador Java..."));
        if (debugPanelId != null) {
            host.requestOpenToolPanel(debugPanelId);
        }
        host.requestSetHotReloadButtonVisible(true);
        host.requestSetHotReloadButtonEnabled(false);
        long generation = debugStartGeneration.get();
        host.background().submit(() -> {
            try {
                JavaLanguageServer lsp = host.ensureLanguageServer();
                if (!awaitLanguageServerForDebug(lsp, generation)) {
                    return;
                }
                DebugAdapterSupport debugAdapter = lsp.extension(DebugAdapterSupport.class);
                if (debugAdapter == null || !debugAdapter.isDebugAdapterAvailable()) {
                    throw new IllegalStateException("O servidor de debug Java nao esta disponivel. Reinicie o IntelliSense Java.");
                }
                int adapterPort = debugAdapter.startDebugSession();
                if (adapterPort <= 0) {
                    throw new IllegalStateException("O JDT LS nao abriu uma sessao de debug.");
                }
                JavaDebugSession session = new JavaDebugSession(adapterPort, attachTarget,
                        host.projectRoot(), context.getBreakpoints(), this::publishDebugSnapshot,
                        host.background())
                        .projectName(debugProjectName(targetModule))
                        .breakOnCaughtExceptions(host.settings().isBreakOnCaughtExceptions());
                boolean installed;
                synchronized (debugSessionLock) {
                    installed = generation == debugStartGeneration.get();
                    if (installed) {
                        debugSession = session;
                    }
                }
                if (!installed) {
                    log.info("Sessao de debug descartada: o debug foi encerrado durante a inicializacao");
                    closeQuietly(session);
                    return;
                }
                session.start();
            } catch (Exception error) {
                log.warn("Falha ao iniciar a sessao de debug Java", error);
                terminateDebuggee();
                publishDebugSnapshot(new JavaDebugSnapshot(JavaDebugSnapshot.State.ERROR,
                        AdapterFailures.rootMessage(error), 0, List.of(), List.of(), List.of()));
            }
        });
    }

    public JavaDebugPanel ensureDebugPanel() {
        JavaDebugPanel existing = debugPanel;
        if (existing != null) {
            return existing;
        }
        JavaDebugPanel created = new JavaDebugPanel(new DebugPanelHost(), host.background());
        debugValuePopup().bindChildrenProvider(reference -> {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variables(reference);
        });
        debugPanel = created;
        debugPanelId = host.registerToolPanel(DockRegion.BOTTOM, "Debug", ToolIconType.DEBUG, created,
                new Dimension(920, 350));
        return created;
    }

    public void publishDebugSnapshot(JavaDebugSnapshot snapshot) {
        JavaDebugPanel panel = debugPanel;
        if (panel != null) {
            panel.update(snapshot);
        }
        boolean active = snapshot.state() != JavaDebugSnapshot.State.TERMINATED
                && snapshot.state() != JavaDebugSnapshot.State.ERROR;
        if (!active) {
            host.debugActiveFlag().set(false);
            debugEdtWatchdog.stop();
            debugSession = null;
            debugProcessHandle = null;
            host.closeDebugRelay();
            host.requestSetRunButtonRunning(host.hasRunningProcess());
            setDebugEditorAssistEnabled(true);
        }
        if (snapshot.state() != JavaDebugSnapshot.State.PAUSED) {
            clearDebugPosition();
            debugHoverTicket.incrementAndGet();
            hideDebugValuePopup();
        }
        if (!active) {
            repaintDebugBreakpointLines();
            forgetDebugLibrarySources();
        }
        boolean hotReloadable = active && host.supportsHotReloadForSelection();
        host.requestSetHotReloadButtonVisible(hotReloadable);
        host.requestSetHotReloadButtonEnabled(hotReloadable);
        if (!snapshot.message().isBlank()) {
            host.setStatusBarText("Java Debug: " + snapshot.message().trim());
        }
        if (!active) {
            naturalDebugEnd.set(snapshot.state() == JavaDebugSnapshot.State.TERMINATED);
            terminateDebuggee();
            naturalDebugEnd.set(false);
        }
    }

    private final class DebugPanelHost implements JavaDebugPanel.Host {
        @Override
        public void resume() {
            withDebugSession(JavaDebugSession::continueExecution);
        }

        @Override
        public void pause() {
            withDebugSession(JavaDebugSession::pause);
        }

        @Override
        public void next() {
            withDebugSession(JavaDebugSession::next);
        }

        @Override
        public void stepIn() {
            withDebugSession(JavaDebugSession::stepIn);
        }

        @Override
        public void stepOut() {
            withDebugSession(JavaDebugSession::stepOut);
        }

        @Override
        public void stop() {
            closeDebugSession();
        }

        @Override
        public void hotReload() {
            runHotReload();
        }

        @Override
        public void openFile(Path file, int line) {
            highlightDebugLine(file, line);
        }

        @Override
        public void openLibrarySource(String uri, int line) {
            highlightDebugLibraryLine(uri, line);
        }

        @Override
        public JavaDebugSnapshot.Variable evaluate(String expression, int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? null : session.evaluate(expression, frameId);
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variables(int reference) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variables(reference);
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variablesForFrame(int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variablesForFrame(frameId);
        }

        @Override
        public List<JavaDebugSnapshot.Scope> scopesForFrame(int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.scopesForFrame(frameId);
        }

        @Override
        public void showEvaluate(int frameId) {
            showEvaluateDialog(host.activeJavaEditor(), frameId);
        }

        @Override
        public void selectThread(int threadId) {
            withDebugSession(session -> session.selectThread(threadId));
        }
    }

    public void withDebugSession(java.util.function.Consumer<JavaDebugSession> action) {
        JavaDebugSession session = debugSession;
        if (session != null) {
            host.background().submit(() -> action.accept(session));
        }
    }

    public void withPausedDebugSession(java.util.function.Consumer<JavaDebugSession> action) {
        if (isDebugPaused()) {
            withDebugSession(action);
        }
    }

    public void closeDebugSession() {
        JavaDebugSession session;
        synchronized (debugSessionLock) {
            debugStartGeneration.incrementAndGet();
            session = debugSession;
            debugSession = null;
        }
        host.closeDebugRelay();
        host.debugActiveFlag().set(false);
        debugEdtWatchdog.stop();
        debugModule = null;
        debugProcessHandle = null;
        debugHoverTicket.incrementAndGet();
        hideDebugValuePopup();
        if (session != null) {
            host.background().submit(session::close);
        }
        Runnable terminator = takeDebuggeeTerminator();
        if (terminator != null) {
            host.background().submit(() -> runDebuggeeTerminator(terminator));
        }
        host.requestSetRunButtonRunning(host.hasRunningProcess());
        host.requestSetHotReloadButtonEnabled(false);
        host.requestSetHotReloadButtonVisible(false);
        setDebugEditorAssistEnabled(true);
        clearDebugPosition();
        repaintDebugBreakpointLines();
    }

    public void terminateDebuggee() {
        runDebuggeeTerminator(takeDebuggeeTerminator());
    }

    private static void closeQuietly(JavaDebugSession session) {
        try {
            session.close();
        } catch (RuntimeException error) {
            log.debug("Falha ao fechar sessao de debug descartada: {}", error.getMessage());
        }
    }

    public void setDebuggeeTerminator(Runnable terminator) {
        synchronized (host.monitor()) {
            debuggeeTerminator = terminator;
        }
    }

    public Runnable takeDebuggeeTerminator() {
        synchronized (host.monitor()) {
            Runnable terminator = debuggeeTerminator;
            debuggeeTerminator = null;
            return terminator;
        }
    }

    public void runDebuggeeTerminator(Runnable terminator) {
        if (terminator == null) {
            return;
        }
        try {
            terminator.run();
        } catch (RuntimeException error) {
            log.debug("Falha ao finalizar processo Java: {}", error.getMessage());
        }
    }

    public void setDebugEditorAssistEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = host.activeJavaEditor();
            if (editor == null) {
                return;
            }
            editor.setAutoCompleteOnTyping(enabled);
            if (!enabled) {
                editor.clearGhostText();
                editor.hideAutoCompletePopup();
            }
            host.requestRepaintCodeEditor(editor.filePath());
        });
    }

    public boolean isDebugPaused() {
        JavaDebugSession session = debugSession;
        return session != null && session.snapshot().state() == JavaDebugSnapshot.State.PAUSED;
    }

    public void showEvaluateDialog(IdeEditorContext editor, int frameId) {
        if (!isDebugPaused()) {
            host.setStatusBarText(text("debug.evaluate.paused", "Pause o programa para avaliar expressoes."));
            return;
        }
        JavaDebugSession session = debugSession;
        if (session == null) {
            return;
        }
        String initial = selectedDebugExpression(editor);
        JavaEvaluateDialog content = new JavaEvaluateDialog(initial,
                expression -> session.evaluate(expression, frameId), session::variables,
                this::addDebugWatch, host.background());
        editor.openEditorDialog(text("debug.evaluate.title", "Evaluate Expression"),
                content, false, null);
        content.open();
    }

    public void addDebugWatch(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        ensureDebugPanel().addWatch(expression.trim());
        if (debugPanelId != null) {
            host.requestOpenToolPanel(debugPanelId);
        }
    }

    public static String selectedDebugExpression(IdeEditorContext editor) {
        if (editor == null) {
            return null;
        }
        String selected = editor.getSelectedTextOrEmpty();
        if (selected != null && !selected.isBlank()) {
            String candidate = selected.trim();
            return candidate.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")
                    ? candidate : null;
        }
        return safeDebugExpression(editor.getText(), editor.getCaretOffset());
    }

    public static String safeDebugExpression(String source, int offset) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        int at = Math.max(0, Math.min(offset, source.length() - 1));
        if (!Character.isJavaIdentifierPart(source.charAt(at)) && at > 0
                && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
            at--;
        }
        if (!Character.isJavaIdentifierPart(source.charAt(at))) {
            return null;
        }
        int start = at;
        int end = at + 1;
        while (start > 0 && debugExpressionCharacter(source.charAt(start - 1))) {
            start--;
        }
        while (end < source.length() && debugExpressionCharacter(source.charAt(end))) {
            end++;
        }
        String value = source.substring(start, end).replaceAll("^\\.+|\\.+$", "");
        int following = end;
        while (following < source.length() && Character.isWhitespace(source.charAt(following))) {
            following++;
        }
        if (following < source.length() && source.charAt(following) == '(') {
            return null;
        }
        return value.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*") ? value : null;
    }

    private static Point pointerLocation() {
        try {
            return MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean debugExpressionCharacter(char value) {
        return Character.isJavaIdentifierPart(value) || value == '.';
    }

    public void clearDebugPosition() {
        debugLineTicket.incrementAndGet();
        if (SwingUtilities.isEventDispatchThread()) {
            clearDebugPositionNow();
        } else {
            SwingUtilities.invokeLater(this::clearDebugPositionNow);
        }
    }

    public void highlightDebugLine(Path file, int line) {
        if (file == null) {
            return;
        }
        debugSteppedFiles.add(JavaProjectConventions.normalize(file));
        long ticket = debugLineTicket.incrementAndGet();
        int editorLine = Math.max(0, line - 1);
        SwingUtilities.invokeLater(() -> {
            clearDebugPositionNow();
            host.requestOpenFile(file);
            applyDebugLine(file, editorLine, ticket, 0);
        });
    }

    void applyDebugLine(Path file, int line, long ticket, int attempt) {
        if (ticket != debugLineTicket.get()) {
            return;
        }
        IdeEditorContext editor = host.getEditor(file, true);
        if (editor == null) {
            if (attempt < 10) {
                host.background().schedule(() -> SwingUtilities.invokeLater(
                        () -> applyDebugLine(file, line, ticket, attempt + 1)),
                        60, TimeUnit.MILLISECONDS);
            }
            return;
        }
        clearDebugPositionNow();
        editor.setCaretPosition(line, 0);
        editor.setPriorityLineColor(line, DEBUG_LINE_COLOR);
        debugLineContext = editor;
        debugLine = line;
        host.requestRepaintCodeEditor(file);
    }

    void clearDebugPositionNow() {
        IdeEditorContext editor = debugLineContext;
        int line = debugLine;
        debugLineContext = null;
        debugLine = -1;
        if (editor != null && line >= 0) {
            editor.removeLineColor(line);
            if (editor.filePath() != null) {
                host.requestRepaintCodeEditor(editor.filePath());
            }
        }
        CodeEditor library = debugLibraryEditor;
        int libraryLine = debugLibraryLine;
        debugLibraryLine = -1;
        if (library != null && libraryLine >= 0) {
            library.removeLineColor(libraryLine);
            library.repaint();
        }
    }

    public void highlightDebugLibraryLine(String uri, int line) {
        JavaLanguageServer lsp = host.languageServer();
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (uri == null || classFiles == null) {
            return;
        }
        long ticket = debugLineTicket.incrementAndGet();
        int editorLine = Math.max(0, line - 1);
        String cached = debugLibrarySources.get(uri);
        if (cached != null) {
            SwingUtilities.invokeLater(() -> showDebugLibraryLine(uri, cached, editorLine, ticket));
            return;
        }
        SwingUtilities.invokeLater(() -> host.showProgress(NAVIGATION_PROGRESS_ID,
                text("progress.decompiling", "Java: abrindo fonte da dependencia...")));
        host.background().submit(() -> {
            String source = classFiles.classFileContents(uri);
            if (source != null && !source.isBlank()) {
                debugLibrarySources.put(uri, source);
            }
            SwingUtilities.invokeLater(() -> {
                host.hideProgress(NAVIGATION_PROGRESS_ID);
                if (source == null || source.isBlank()) {
                    host.setStatusBarText(text("status.decompileFailed",
                            "Java: nao foi possivel obter a fonte da dependencia"));
                    return;
                }
                showDebugLibraryLine(uri, source, editorLine, ticket);
            });
        });
    }

    void showDebugLibraryLine(String uri, String source, int line, long ticket) {
        if (ticket != debugLineTicket.get() || !isDebugPaused()) {
            return;
        }
        clearDebugPositionNow();
        CodeEditor editor = debugLibraryEditor;
        int[] target = NavigationViews.clampPosition(source, line, 0);
        if (editor == null || !uri.equals(debugLibraryUri) || !editor.isShowing()) {
            editor = host.navigationViews().openClassFileEditor(uri, source, target[0], 0);
            if (editor == null) {
                return;
            }
            debugLibraryEditor = editor;
            debugLibraryUri = uri;
        }
        editor.setCaretPosition(target[0], 0);
        editor.setPriorityLineColor(target[0], DEBUG_LINE_COLOR);
        editor.repaint();
        debugLibraryLine = target[0];
    }

    public void forgetDebugLibrarySources() {
        debugLibrarySources.clear();
        SwingUtilities.invokeLater(() -> {
            clearDebugPositionNow();
            debugLibraryEditor = null;
            debugLibraryUri = null;
        });
    }

    public void repaintDebugBreakpointLines() {
        Set<Path> files = Set.copyOf(debugSteppedFiles);
        debugSteppedFiles.removeAll(files);
        files.forEach(host::requestRepaintCodeEditorBreakpointLine);
    }

    public void runHotReload() {
        if (!host.supportsHotReloadForSelection()) {
            host.setStatusBarText("Java: hot reload nao esta disponivel para este tipo de execucao.");
            return;
        }
        if (host.settings().getHotReloadMode() == HotReloadMode.NEVER) {
            host.setStatusBarText("Java: hot reload esta desativado nas preferencias.");
            return;
        }
        host.requestSetHotReloadButtonEnabled(false);
        host.background().submit(() -> {
            OutputPanelHandle output = host.requestOutputPanel("Run", OutputPanelOptions.interactive(null));
            JavaHotReloadService.Result result = hotReloadService().reload(
                    line -> host.writeOutput(output, line));
            String message = switch (result) {
                case APPLIED -> "Hot reload aplicado.";
                case NO_SESSION -> "Nenhuma sessao de debug ativa.";
                case BUILD_FAILED -> "Hot reload cancelado: o build falhou.";
                case STRUCTURAL_CHANGE -> "A JVM nao aceita esta alteracao estrutural; reinicie a sessao.";
                case FAILED -> "Nao foi possivel aplicar o hot reload.";
            };
            host.setStatusBarText("Java: " + message);
            host.requestSetHotReloadButtonEnabled(debugSession != null);
        });
    }

    public JavaHotReloadService hotReloadService() {
        synchronized (host.monitor()) {
            if (hotReloadService == null) {
                hotReloadService = new JavaHotReloadService(host::descriptor,
                        host::buildSystem, () -> debugSession, () -> debugModule);
            }
            return hotReloadService;
        }
    }
}
