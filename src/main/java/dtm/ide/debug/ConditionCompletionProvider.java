package dtm.ide.debug;

import dtm.ide.index.JavaLocalScope;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteProvider;
import dtm.stools.component.panels.editor.code.autocomplete.CompletionContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public final class ConditionCompletionProvider implements AutoCompleteProvider {

    private static final List<String> KEYWORDS = List.of("this", "true", "false", "null", "instanceof");

    private final Path file;
    private final int line;
    private final Supplier<String> source;
    private final Supplier<? extends DebuggerCompletionSource> session;

    public ConditionCompletionProvider(Path file, int line, Supplier<String> source,
                                       Supplier<? extends DebuggerCompletionSource> session) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.line = line;
        this.source = source == null ? () -> "" : source;
        this.session = session == null ? () -> null : session;
    }

    @Override
    public List<AutoCompleteItem> getSuggestions(CompletionContext context) {
        if (context == null) {
            return List.of();
        }
        return suggestions(context.currentLine(), context.caretCol(), context.prefix());
    }

    @Override
    public boolean shouldAutoTrigger(CompletionContext context) {
        return context != null && context.triggerKind() == CompletionContext.TriggerKind.TYPING;
    }

    public List<AutoCompleteItem> suggestions(String lineText, int caretCol, String prefix) {
        String text = lineText == null ? "" : lineText;
        String typed = prefix == null ? "" : prefix;
        int caret = Math.max(0, Math.min(caretCol, text.length()));
        Map<String, AutoCompleteItem> items = new LinkedHashMap<>();
        if (!memberAccess(text, caret - typed.length())) {
            for (JavaLocalScope.Visible visible : JavaLocalScope.visibleAt(source.get(), line)) {
                if (matches(visible.name(), typed)) {
                    items.putIfAbsent(visible.name(), new AutoCompleteItem(visible.name(),
                            visible.name(), detailOf(visible.kind()), "", null, kindOf(visible.kind())));
                }
            }
            for (String keyword : KEYWORDS) {
                if (matches(keyword, typed)) {
                    items.putIfAbsent(keyword, new AutoCompleteItem(keyword, keyword, "keyword", "",
                            null, AutoCompleteItem.Kind.KEYWORD));
                }
            }
        }
        for (JavaDebugSession.Completion completion : debuggerCompletions(text, caret)) {
            if (matches(completion.label(), typed)) {
                items.putIfAbsent(completion.label(), new AutoCompleteItem(completion.insertText(),
                        completion.label(), completion.type(), "", null, kindOf(completion.type())));
            }
        }
        return List.copyOf(items.values());
    }

    private List<JavaDebugSession.Completion> debuggerCompletions(String text, int caret) {
        DebuggerCompletionSource current = session.get();
        if (current == null || file == null || !current.isCompletionsSupported()) {
            return List.of();
        }
        Path paused = current.pausedSource();
        if (paused == null || !paused.toAbsolutePath().normalize().equals(file)) {
            return List.of();
        }
        return current.completions(text, caret + 1);
    }

    private static boolean memberAccess(String text, int prefixStart) {
        int index = prefixStart - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        return index >= 0 && text.charAt(index) == '.';
    }

    private static boolean matches(String candidate, String prefix) {
        return candidate != null && (prefix.isEmpty()
                || candidate.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)));
    }

    private static String detailOf(JavaLocalScope.Kind kind) {
        return switch (kind) {
            case LOCAL -> "local";
            case PARAMETER -> "parameter";
            case FIELD -> "field";
        };
    }

    private static AutoCompleteItem.Kind kindOf(JavaLocalScope.Kind kind) {
        return switch (kind) {
            case LOCAL -> AutoCompleteItem.Kind.VARIABLE;
            case PARAMETER -> AutoCompleteItem.Kind.PARAMETER;
            case FIELD -> AutoCompleteItem.Kind.FIELD;
        };
    }

    private static AutoCompleteItem.Kind kindOf(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "method", "function" -> AutoCompleteItem.Kind.METHOD;
            case "field" -> AutoCompleteItem.Kind.FIELD;
            case "property" -> AutoCompleteItem.Kind.PROPERTY;
            case "variable" -> AutoCompleteItem.Kind.VARIABLE;
            case "class" -> AutoCompleteItem.Kind.CLASS;
            case "keyword" -> AutoCompleteItem.Kind.KEYWORD;
            default -> AutoCompleteItem.Kind.TEXT;
        };
    }
}
