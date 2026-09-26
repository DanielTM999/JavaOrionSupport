package dtm.ide.debug;

import dtm.ide.api.project.editor.ConditionStatus;
import dtm.ide.api.project.editor.ConditionalBreakpointDialogView;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteProvider;
import dtm.stools.component.panels.editor.code.autocomplete.CompletionContext;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticsProvider;
import dtm.stools.component.panels.editor.code.listeners.DocumentEditListener;

import javax.swing.SwingUtilities;
import java.awt.event.HierarchyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class ConditionEditorSession implements AutoCloseable {

    private static final long SYNC_DELAY_MS = 300;

    private final Path file;
    private final int line;
    private final Supplier<String> source;
    private final ConditionLanguageService language;
    private final ConditionCompletionProvider fallback;
    private final Runnable onClosed;
    private final Path syntheticPath;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orion-condition-sync");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String conditionText;
    private volatile ConditionSyntheticSource lastSent;
    private volatile ConditionalBreakpointDialogView view;
    private volatile ConditionStatus status = ConditionStatus.NONE;
    private volatile String statusMessage;
    private volatile Runnable diagnosticsRefresher = () -> {
    };
    private volatile CodeEditor editor;
    private ScheduledFuture<?> pending;

    public ConditionEditorSession(Path file, int line, String initialCondition, Supplier<String> source,
                                  ConditionLanguageService language,
                                  Supplier<? extends DebuggerCompletionSource> debugger, Runnable onClosed) {
        this.file = file.toAbsolutePath().normalize();
        this.line = line;
        this.source = source == null ? () -> "" : source;
        this.language = language;
        this.fallback = new ConditionCompletionProvider(this.file, line, this.source, debugger);
        this.onClosed = onClosed == null ? () -> {
        } : onClosed;
        this.conditionText = initialCondition == null ? "" : initialCondition;
        this.syntheticPath = ConditionSyntheticSource.build(this.file, "", line, "").path();
    }

    public Path file() {
        return file;
    }

    public int line() {
        return line;
    }

    public Path syntheticPath() {
        return syntheticPath;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public AutoCompleteProvider completionProvider() {
        return new AutoCompleteProvider() {
            @Override
            public List<AutoCompleteItem> getSuggestions(CompletionContext context) {
                if (context == null) {
                    return List.of();
                }
                return suggestions(context.buffer().getText(), context.caretLine(), context.caretCol(),
                        context.prefix(), context.currentLine());
            }

            @Override
            public boolean shouldAutoTrigger(CompletionContext context) {
                return context != null && context.triggerKind() == CompletionContext.TriggerKind.TYPING;
            }
        };
    }

    public DiagnosticsProvider diagnosticsProvider() {
        return context -> mappedDiagnostics();
    }

    public List<AutoCompleteItem> suggestions(String condition, int caretLine, int caretCol, String prefix,
                                              String lineText) {
        if (closed.get()) {
            return List.of();
        }
        List<AutoCompleteItem> semantic = List.of();
        if (language.available()) {
            ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(file, source.get(), line,
                    condition == null ? "" : condition);
            semantic = language.complete(synthetic.path(), synthetic.text(),
                    synthetic.toSyntheticLine(caretLine), synthetic.toSyntheticCol(caretLine, caretCol));
        }
        if (semantic.isEmpty()) {
            return fallback.suggestions(lineText, caretCol, prefix);
        }
        Map<String, AutoCompleteItem> items = new LinkedHashMap<>();
        for (AutoCompleteItem item : semantic) {
            items.putIfAbsent(item.label(), new AutoCompleteItem(item.insertText(), item.label(), item.detail(),
                    item.description(), item.icon(), item.kind(), List.of()));
        }
        return List.copyOf(items.values());
    }

    public void bind(CodeEditor editor) {
        if (editor == null) {
            return;
        }
        this.editor = editor;
        diagnosticsRefresher = () -> SwingUtilities.invokeLater(editor::refreshDiagnostics);
        editor.addDocumentEditListener(new DocumentEditListener() {
            @Override
            public void onTextChanged() {
                textChanged(editor.getText());
            }
        });
        AtomicBoolean shown = new AtomicBoolean();
        editor.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            if (editor.isDisplayable()) {
                shown.set(true);
            } else if (shown.get()) {
                close();
            }
        });
        textChanged(editor.getText());
    }

    public void insertAtCaret(String text) {
        CodeEditor current = editor;
        if (current == null || text == null || text.isEmpty() || closed.get()) {
            return;
        }
        Runnable insert = () -> {
            Position at = Position.of(current.getCaretLine(), current.getCaretCol());
            current.applyEdits(List.of(TextEdit.insert(at, text)));
            current.setCaretPosition(at.line(), at.col() + text.length());
            current.requestFocusInWindow();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            insert.run();
        } else {
            SwingUtilities.invokeLater(insert);
        }
    }

    public void attach(ConditionalBreakpointDialogView dialogView) {
        this.view = dialogView;
        publishStatus();
    }

    public synchronized void textChanged(String text) {
        if (closed.get()) {
            return;
        }
        conditionText = text == null ? "" : text;
        if (pending != null) {
            pending.cancel(false);
        }
        if (conditionText.isBlank()) {
            updateStatus(ConditionStatus.NONE, null);
            return;
        }
        updateStatus(ConditionStatus.CHECKING, null);
        pending = scheduler.schedule(this::syncNow, SYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    void syncNow() {
        if (closed.get()) {
            return;
        }
        String text = conditionText;
        if (text.isBlank()) {
            updateStatus(ConditionStatus.NONE, null);
            return;
        }
        if (!language.available()) {
            updateStatus(ConditionStatus.NONE, null);
            return;
        }
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(file, source.get(), line, text);
        lastSent = synthetic;
        language.changeDocument(synthetic.path(), synthetic.text());
        evaluate();
    }

    public void diagnosticsPublished() {
        if (closed.get()) {
            return;
        }
        diagnosticsRefresher.run();
        evaluate();
    }

    public List<Diagnostic> mappedDiagnostics() {
        ConditionSyntheticSource synthetic = lastSent;
        if (synthetic == null || closed.get()) {
            return List.of();
        }
        return map(synthetic, conditionText, language.diagnostics(synthetic.path()));
    }

    static List<Diagnostic> map(ConditionSyntheticSource synthetic, String condition,
                                Collection<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        String[] lines = (condition == null ? "" : condition).split("\\R", -1);
        List<Diagnostic> mapped = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || !synthetic.coversSyntheticLine(diagnostic.startLine())
                    || diagnostic.severity() == DiagnosticSeverity.HINT) {
                continue;
            }
            int startLine = synthetic.toConditionLine(diagnostic.startLine());
            int startCol = clamp(synthetic.toConditionCol(diagnostic.startLine(), diagnostic.startCol()),
                    lines, startLine);
            int endLine;
            int endCol;
            if (synthetic.coversSyntheticLine(diagnostic.endLine())) {
                endLine = synthetic.toConditionLine(diagnostic.endLine());
                endCol = clamp(synthetic.toConditionCol(diagnostic.endLine(), diagnostic.endCol()), lines, endLine);
            } else {
                endLine = lines.length - 1;
                endCol = lines[endLine].length();
            }
            if (endLine == startLine && endCol <= startCol) {
                startCol = Math.max(0, Math.min(startCol, lines[startLine].length() - 1));
                endCol = Math.max(startCol + 1, lines[startLine].length());
            }
            mapped.add(new Diagnostic(startLine, startCol, endLine, endCol, diagnostic.severity(),
                    diagnostic.message(), diagnostic.source(), null));
        }
        return List.copyOf(mapped);
    }

    private void evaluate() {
        ConditionSyntheticSource synthetic = lastSent;
        String text = conditionText;
        if (text.isBlank()) {
            updateStatus(ConditionStatus.NONE, null);
            return;
        }
        if (synthetic == null || !synthetic.text().equals(
                ConditionSyntheticSource.build(file, source.get(), line, text).text())) {
            return;
        }
        List<Diagnostic> errors = mappedDiagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            updateStatus(ConditionStatus.VALID, null);
        } else {
            updateStatus(ConditionStatus.INVALID, errors.getFirst().message());
        }
    }

    public ConditionStatus status() {
        return status;
    }

    public String statusMessage() {
        return statusMessage;
    }

    private void updateStatus(ConditionStatus next, String message) {
        status = next;
        statusMessage = message;
        publishStatus();
    }

    private void publishStatus() {
        ConditionalBreakpointDialogView current = view;
        if (current == null) {
            return;
        }
        try {
            current.setConditionStatus(status, statusMessage);
        } catch (LinkageError olderIde) {
            view = null;
        }
    }

    private static int clamp(int col, String[] lines, int line) {
        int length = line >= 0 && line < lines.length ? lines[line].length() : 0;
        return Math.max(0, Math.min(col, length));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            if (pending != null) {
                pending.cancel(false);
            }
        }
        scheduler.shutdownNow();
        try {
            language.closeDocument(syntheticPath);
        } finally {
            onClosed.run();
        }
    }
}
