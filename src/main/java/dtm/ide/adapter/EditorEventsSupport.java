package dtm.ide.adapter;

import dtm.ide.api.project.editor.EditorShortcutScope;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.debug.JavaDebugSession;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.settings.HotReloadMode;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.swingdesigner.SwingDesignerSupport;
import dtm.ide.test.JUnitTestDiscovery;
import dtm.ide.test.JavaTest;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaProjectTreeIcons;
import dtm.ide.ui.JavaTestGutterLayer;
import dtm.stools.component.menu.popup.ActionMenu;
import dtm.stools.component.panels.editor.code.ghost.GhostTextActivationMode;
import dtm.stools.configs.UiTokens;
import lombok.extern.slf4j.Slf4j;

import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class EditorEventsSupport {

    private static final int GHOST_TEXT_IDLE_DELAY_MS = 1_000;

    private final AdapterHost host;

    public EditorEventsSupport(AdapterHost host) {
        this.host = host;
    }

    public void installTestGutter(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        Path file = JavaProjectConventions.normalize(context.filePath());
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return;
        }
        JavaTestGutterLayer layer = context.getGutterLayer(JavaTestGutterLayer.class);
        if (layer == null) {
            layer = new JavaTestGutterLayer(this::showTestGutterMenu);
            if (!context.addGutterLayer(layer)) {
                return;
            }
        }
        layer.setColor(UiTokens.success());
        layer.setTests(JUnitTestDiscovery.discoverInSource(file, context.getText()));
        context.repaintGutter();
    }

    private void showTestGutterMenu(MouseEvent event, JavaTest test) {
        ActionMenu menu = ActionMenu.of(new JMenu());
        menu.item(text("lens.runAction", "Executar"), JavaIcons.test(JavaIcons.SMALL),
                        action -> host.codeLens().runTestFromLens(test, false))
                .item(text("lens.debugAction", "Depurar"), JavaIcons.debug(JavaIcons.SMALL),
                        action -> host.codeLens().runTestFromLens(test, true));
        if (host.coverage().supportedForProject()) {
            menu.item(text("action.runCoverage", "Rodar com cobertura"),
                    JavaIcons.test(JavaIcons.SMALL), action -> runTestWithCoverage(test));
        }
        menu.getMenu().getPopupMenu().show(event.getComponent(), event.getX(), event.getY());
    }

    private void runTestWithCoverage(JavaTest test) {
        SwingUtilities.invokeLater(() -> {
            host.ensureTestPanel();
            if (host.testPanelId() != null) {
                host.requestOpenToolPanel(host.testPanelId());
            }
            if (host.testPanel() != null) {
                host.testPanel().runTestsWithCoverage(List.of(test));
            }
        });
    }

    public void configureGhostText(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        context.setGhostTextEnabled(true);
        context.setGhostTextActivationMode(GhostTextActivationMode.CARET_IDLE);
        context.setGhostTextCaretIdleDelay(GHOST_TEXT_IDLE_DELAY_MS);
    }

    public void onEditorOpen(IdeEditorContext editorContext) {
        configureGhostText(editorContext);
        host.coverage().installGutter(editorContext);
        installTestGutter(editorContext);
        installCodeActionCommandHandler(editorContext);
        installJavaShortcuts(editorContext);
        JavaLanguageServer lsp = host.languageServer();
        if (editorContext == null || !JavaProjectConventions.isJava(editorContext.filePath())) {
            return;
        }
        Path openedPath = JavaProjectConventions.normalize(editorContext.filePath());
        host.javaEditors().put(openedPath, editorContext);
        String openedText = Objects.toString(editorContext.getText(), "");
        host.lastEditorContents().put(openedPath, openedText);
        host.diskBaseline().put(openedPath, FileWatchSupport.diskBaselineFor(openedPath, openedText));
        if (host.activeJavaEditor() == null) {
            host.activeJavaEditor(editorContext);
        }
        if (lsp != null) {
            lsp.openDocument(editorContext.filePath(), openedText);
        }
        host.background().submit(() -> JavaLocalScope.preload(openedText));
        SwingUtilities.invokeLater(editorContext::refreshCodeLenses);
    }

    public void onEditorSelected(IdeEditorContext editorContext) {
        host.autoCompleteIdle().cancel();
        host.activeJavaEditor(editorContext != null && JavaProjectConventions.isJava(editorContext.filePath())
                ? editorContext : null);
        if (host.debugActiveFlag().get()) {
            host.debug().setDebugEditorAssistEnabled(false);
        }
        host.runLauncher().refreshRunButtonsForCurrentFile();
    }

    public void installCodeActionCommandHandler(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        if (!context.setCommandHandler(host.sourceActions()::handleCodeActionCommand)) {
            log.debug("Nao foi possivel registrar as correcoes Java em {}", context.filePath());
        }
    }

    public void installJavaShortcuts(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        context.registerShortcut("java.goToDefinition", "control B",
                () -> host.navigationSupport().onGoToDeclaration(context));
        context.registerShortcut("java.goToImplementation", "control alt B",
                () -> host.navigationSupport().onGoToImplementation(context));
        context.registerShortcut("java.findUsages", "alt F7",
                () -> host.navigationSupport().onFindUsages(context));
        context.registerShortcut("java.generate", "alt INSERT",
                () -> host.sourceActions().showGenerateActions(context));
        context.registerShortcut("java.overrideMethods", "control INSERT",
                () -> host.sourceActions().showOverrideMethods(context, false));
        context.registerShortcut("java.implementMethods", "control I",
                () -> host.sourceActions().showOverrideMethods(context, true));
        context.registerShortcut("java.evaluateExpression", "alt F8",
                () -> host.debug().showEvaluateDialog(context, 0));
        context.registerShortcut("java.pasteImports", "control V", EditorShortcutScope.EDITOR, () -> {
            SwingUtilities.invokeLater(() -> host.sourceActions().onPasted(context));
            return false;
        });
        bindDebugShortcut(context, "F5", "java.debug.continue",
                () -> host.debug().withPausedDebugSession(JavaDebugSession::continueExecution));
        bindDebugShortcut(context, "F6", "java.debug.pause",
                () -> host.debug().withDebugSession(JavaDebugSession::pause));
        bindDebugShortcut(context, "F10", "java.debug.stepOver",
                () -> host.debug().withPausedDebugSession(JavaDebugSession::next));
        bindDebugShortcut(context, "F11", "java.debug.stepInto",
                () -> host.debug().withPausedDebugSession(JavaDebugSession::stepIn));
        bindDebugShortcut(context, "shift F11", "java.debug.stepOut",
                () -> host.debug().withPausedDebugSession(JavaDebugSession::stepOut));
        bindDebugShortcut(context, "shift F5", "java.debug.stop", host.debug()::closeDebugSession);
        bindDebugShortcut(context, "control F5", "java.debug.hotReload", host.debug()::runHotReload);
    }

    private void bindDebugShortcut(IdeEditorContext context, String stroke,
                                   String actionId, Runnable action) {
        context.registerShortcut(actionId, stroke, EditorShortcutScope.WINDOW, () -> {
            if (!host.debugActiveFlag().get()) {
                return false;
            }
            action.run();
            return true;
        });
    }

    public void onCodeEditorInsertText(IdeEditorContext editorContext, int offset, String inserted) {
        if (editorContext == null) {
            host.autoCompleteIdle().cancel();
            return;
        }
        host.autoCompleteIdle().typed(JavaProjectConventions.normalize(editorContext.filePath()),
                offset, inserted);
    }

    public void onCodeEditorDeleteText(IdeEditorContext editorContext, int offset, String removed) {
        host.autoCompleteIdle().cancel();
    }

    public void onCodeEditorTextChanged(IdeEditorContext editorContext) {
        JavaLanguageServer lsp = host.languageServer();
        if (editorContext != null && JavaProjectConventions.isJava(editorContext.filePath())) {
            Path edited = editorContext.filePath().toAbsolutePath().normalize();
            String currentText = Objects.toString(editorContext.getText(), "");
            String previousText = host.lastEditorContents().put(edited, currentText);
            JavaProjectTreeIcons.updateOpenSource(edited, currentText);
            host.requestJavaTreeIconRefresh(edited);
            if (lsp != null) {
                lsp.changeDocument(editorContext.filePath(), currentText);
            }
            if (host.problems().move(edited, previousText, currentText)) host.refreshProblemsPanel();
            host.runLauncher().scheduleRunButtonsRefresh();
        }
    }

    public void onEditorClose(Path filePath) {
        JavaProjectTreeIcons.closeSource(filePath);
        host.requestJavaTreeIconRefresh(filePath);
        host.autoCompleteIdle().cancel();
        IdeEditorContext active = host.activeJavaEditor();
        if (active != null && Objects.equals(JavaProjectConventions.normalize(active.filePath()),
                JavaProjectConventions.normalize(filePath))) {
            host.activeJavaEditor(null);
            host.runLauncher().refreshRunButtonsForCurrentFile();
        }
        JavaLanguageServer lsp = host.languageServer();
        if (JavaProjectConventions.isJava(filePath)) {
            host.coverage().detachGutter(filePath);
            host.javaEditors().remove(JavaProjectConventions.normalize(filePath));
            host.diskBaseline().remove(JavaProjectConventions.normalize(filePath));
            host.lastEditorContents().remove(JavaProjectConventions.normalize(filePath));
            if (lsp != null) {
                lsp.closeDocument(filePath);
            }
        }
    }

    public String onBeforeFileSave(Path filePath, String content) {
        JavaPluginSettings preferences = host.settings();
        if (!preferences.isFormatOnSave() && !preferences.isOrganizeImportsOnSave()) {
            return content;
        }
        JavaLanguageServer lsp = host.runningServerFor(filePath);
        if (lsp == null) {
            return content;
        }
        IdeEditorContext editor = host.getEditor(filePath);
        String prepared = lsp.prepareSave(filePath, content, preferences.isOrganizeImportsOnSave(),
                preferences.isFormatOnSave(), editor == null ? 4 : editor.getTabSize(),
                editor == null || editor.isUseSpacesForTab());
        String latest = editor == null ? content : UiThreads.onUi(editor::getText);
        return latest != null && !Objects.equals(content, latest) ? latest : prepared;
    }

    public void onAfterFileSave(Path filePath, String content) {
        JavaLanguageServer lsp = host.languageServer();
        if (JavaProjectConventions.isJava(filePath)) {
            host.diskBaseline().put(JavaProjectConventions.normalize(filePath), content);
            JavaProjectTreeIcons.invalidate(filePath);
            host.requestJavaTreeIconRefresh(filePath);
        }
        if (lsp != null && JavaProjectConventions.isJava(filePath)) {
            lsp.saveDocument(filePath, content);
        }
        SwingDesignerSupport designer = host.swingDesignerHost().currentSwingDesigner();
        if (designer != null && JavaProjectConventions.isJava(filePath)) {
            designer.onJavaFileSaved(filePath);
        }
        if (JavaProjectConventions.isMavenPom(filePath)
                || JavaProjectConventions.isGradleBuildFile(filePath)) {
            host.onBuildFileChanged(filePath);
        }
        host.todoSupport().refresh(filePath, content);
        host.lexicalIndex().refreshFile(filePath, content);
        JavaProjectDescriptor current = host.descriptor();
        if (current != null && current.spring() && JavaProjectConventions.isJava(filePath)) {
            host.fileWatch().refreshSpringIndexFor(filePath, content);
        }
        if (JavaProjectConventions.isJava(filePath) && host.debug().session() != null
                && host.settings().getHotReloadMode() == HotReloadMode.AUTOMATIC) {
            long ticket = host.debug().hotReloadTicket().incrementAndGet();
            host.background().schedule(() -> {
                if (ticket == host.debug().hotReloadTicket().get() && host.debug().session() != null) {
                    host.debug().runHotReload();
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
    }
}
