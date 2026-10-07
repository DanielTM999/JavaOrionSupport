package dtm.ide.adapter;

import dtm.ide.api.extension.editor.EmbeddedCodeEditorSettings;
import dtm.ide.api.project.editor.ConditionalBreakpointContext;
import dtm.ide.api.project.editor.ConditionalBreakpointDialogView;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.debug.ConditionCompletionProvider;
import dtm.ide.debug.ConditionEditorSession;
import dtm.ide.debug.ConditionLanguageService;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.ui.ConditionalBreakpointHints;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class ConditionalBreakpointSupport {

    private final AdapterHost host;
    private final Map<Path, ConditionEditorSession> conditionSessions = new ConcurrentHashMap<>();
    private final AtomicReference<ConditionEditorSession> activeConditionSession = new AtomicReference<>();

    public ConditionalBreakpointSupport(AdapterHost host) {
        this.host = host;
    }

    public Map<Path, ConditionEditorSession> sessions() {
        return conditionSessions;
    }

    public boolean isConditionalBreakpointEnabled(Path fileOpen) {
        return fileOpen != null && fileOpen.getFileName() != null
                && fileOpen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    public CodeEditor createConditionalBreakpointEditor(ConditionalBreakpointContext context) {
        if (context == null || !isConditionalBreakpointEnabled(context.file())) {
            return null;
        }
        String condition = context.currentCondition() == null ? "" : context.currentCondition();
        CodeEditor editor;
        try {
            editor = host.requestEmbeddedCodeEditor("breakpoint-condition.java", condition,
                    EmbeddedCodeEditorSettings.highlighted());
        } catch (RuntimeException | LinkageError error) {
            log.debug("IDE sem editor embutido para a condicao: {}", error.getMessage());
            return null;
        }
        if (editor == null) {
            return null;
        }
        AtomicReference<ConditionEditorSession> created = new AtomicReference<>();
        ConditionEditorSession session = new ConditionEditorSession(context.file(), context.line(), condition,
                () -> sourceTextOf(context), ConditionLanguageService.of(host::languageServer), host::debugSession,
                () -> {
                    ConditionEditorSession closed = created.get();
                    if (closed != null) {
                        conditionSessions.remove(closed.syntheticPath(), closed);
                        activeConditionSession.compareAndSet(closed, null);
                    }
                });
        created.set(session);
        editor.enableBookmark(false);
        editor.enableBreakpoint(false);
        editor.setFoldingEnabled(false);
        editor.getGutter().enableLineNumber(true);
        editor.addProvider(session.completionProvider());
        editor.addProvider(session.diagnosticsProvider());
        if (editor.getTextArea() != null) {
            editor.getTextArea().setAutoCompleteOnTyping(true);
            editor.getTextArea().setAutoCompleteTypingTrigger(c -> Character.isJavaIdentifierPart(c) || c == '.');
        }
        ConditionEditorSession previous = activeConditionSession.getAndSet(session);
        if (previous != null && previous != session) {
            previous.close();
        }
        conditionSessions.put(session.syntheticPath(), session);
        session.bind(editor);
        return editor;
    }

    public void configureConditionalBreakpointEditor(IdeEditorContext editorContext,
                                                     ConditionalBreakpointContext context) {
        if (editorContext == null || context == null || context.file() == null) {
            return;
        }
        if (sessionFor(context) != null) {
            return;
        }
        try {
            TokenizerCodeEditorProvider tokenizer = host.editors().tokenizerFor(context.file());
            if (tokenizer != null) {
                editorContext.addProvider(tokenizer);
            }
            editorContext.addProvider(new ConditionCompletionProvider(context.file(), context.line(),
                    () -> sourceTextOf(context), host::debugSession));
            editorContext.setAutoCompleteOnTyping(true);
        } catch (RuntimeException | LinkageError error) {
            log.debug("Editor de condicao sem recursos Java: {}", error.getMessage());
        }
    }

    public void configureConditionalBreakpointDialog(ConditionalBreakpointDialogView dialogView) {
        if (dialogView == null) {
            return;
        }
        ConditionalBreakpointContext context = dialogView.getBreakpointContext();
        try {
            dialogView.setHitConditionSupported(true);
            dialogView.setLogMessageSupported(true);
        } catch (LinkageError olderIde) {
            log.debug("IDE sem suporte a hit count/logpoint: {}", olderIde.getMessage());
        }
        if (context == null || context.file() == null) {
            return;
        }
        dialogView.setDescription(text("breakpoint.description",
                "Expressao Java avaliada antes desta linha. O debugger so para quando ela for true."));
        ConditionEditorSession session = sessionFor(context);
        List<String> visible = JavaLocalScope.visibleAt(sourceTextOf(context), context.line()).stream()
                .map(JavaLocalScope.Visible::name).toList();
        if (!visible.isEmpty()) {
            dialogView.addComponent(ConditionalBreakpointHints.chips(
                    text("breakpoint.visible", "Nesta linha:"), visible,
                    session == null ? null : session::insertAtCaret));
        }
        if (session != null) {
            try {
                session.attach(dialogView);
                dialogView.addCloseListener(session::close);
            } catch (LinkageError olderIde) {
                log.debug("IDE sem status de condicao: {}", olderIde.getMessage());
            }
        }
    }

    private ConditionEditorSession sessionFor(ConditionalBreakpointContext context) {
        ConditionEditorSession session = activeConditionSession.get();
        if (session == null || session.isClosed() || context == null || context.file() == null) {
            return null;
        }
        boolean same = session.file().equals(context.file().toAbsolutePath().normalize())
                && session.line() == context.line();
        return same ? session : null;
    }

    private String sourceTextOf(ConditionalBreakpointContext context) {
        IdeEditorContext source = context.sourceEditorContext();
        String text = source == null ? null : source.getText();
        if (text != null) {
            return text;
        }
        try {
            return Files.readString(context.file());
        } catch (Exception error) {
            return "";
        }
    }
}
