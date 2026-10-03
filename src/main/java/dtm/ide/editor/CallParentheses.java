package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CallParentheses {

    private static final String CARET_MARKER = "${0}";
    private static final Pattern NEW_BEFORE = Pattern.compile("(?:^|[^\\w$.])new\\s+$");
    private static final Pattern CALL_NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z_$][\\w$.]*(?:<[^()]*>)?");
    private static final boolean CARET_MARKER_SUPPORTED = detectCaretMarker();

    private CallParentheses() {
    }

    public static List<AutoCompleteItem> apply(List<AutoCompleteItem> items, String text,
                                               int prefixOffset, int caretOffset) {
        return apply(items, text, prefixOffset, caretOffset, CARET_MARKER_SUPPORTED);
    }

    static List<AutoCompleteItem> apply(List<AutoCompleteItem> items, String text,
                                        int prefixOffset, int caretOffset, boolean caretMarker) {
        if (items == null || items.isEmpty() || text == null) {
            return items;
        }
        int prefix = Math.max(0, Math.min(prefixOffset, text.length()));
        int caret = Math.max(prefix, Math.min(caretOffset, text.length()));
        if (!allowsCalls(text, prefix, caret)) {
            return items;
        }
        boolean afterNew = NEW_BEFORE.matcher(text.substring(lineStart(text, prefix), prefix)).find();
        List<AutoCompleteItem> result = new ArrayList<>(items.size());
        boolean changed = false;
        for (AutoCompleteItem item : items) {
            AutoCompleteItem call = withParentheses(item, afterNew, caretMarker);
            changed |= call != item;
            result.add(call);
        }
        return changed ? result : items;
    }

    private static AutoCompleteItem withParentheses(AutoCompleteItem item, boolean afterNew,
                                                    boolean caretMarker) {
        if (item == null || item.insertText() == null || item.kind() == null) {
            return item;
        }
        String insert = item.insertText();
        int paren = insert.indexOf('(');
        String name = (paren < 0 ? insert : insert.substring(0, paren)).trim();
        boolean call = switch (item.kind()) {
            case METHOD, FUNCTION, CONSTRUCTOR -> CALL_NAME.matcher(name).matches();
            case CLASS -> afterNew && paren < 0 && TYPE_NAME.matcher(name).matches();
            default -> false;
        };
        if (!call) {
            return item;
        }
        Boolean parameters = parametersOf(item.label());
        if (parameters == null) {
            parameters = parametersOf(insert);
        }
        boolean inside = (parameters == null || parameters) && caretMarker;
        String replacement = inside
                ? escape(name) + "(" + CARET_MARKER + ")"
                : name + "()";
        if (replacement.equals(insert)) {
            return item;
        }
        return new AutoCompleteItem(replacement, item.label(), item.detail(), item.description(),
                item.icon(), item.kind(), item.additionalTextEdits(), item.unused(), item.data());
    }

    private static Boolean parametersOf(String value) {
        if (value == null) {
            return null;
        }
        int open = value.indexOf('(');
        int close = open < 0 ? -1 : value.indexOf(')', open);
        if (open < 0 || close < 0) {
            return null;
        }
        return !value.substring(open + 1, close).isBlank();
    }

    private static boolean allowsCalls(String text, int prefix, int caret) {
        if (!JavaTypingContext.inCode(text, prefix)) {
            return false;
        }
        int after = caret;
        while (after < text.length() && Character.isJavaIdentifierPart(text.charAt(after))) {
            after++;
        }
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
            after++;
        }
        if (after < text.length() && text.charAt(after) == '(') {
            return false;
        }
        int before = prefix;
        while (before > 0 && Character.isWhitespace(text.charAt(before - 1))) {
            before--;
        }
        if (before > 0 && text.charAt(before - 1) == '@') {
            return false;
        }
        if (before > 1 && text.charAt(before - 1) == ':' && text.charAt(before - 2) == ':') {
            return false;
        }
        String line = text.substring(lineStart(text, prefix), prefix).stripLeading();
        return !line.startsWith("import ") && !line.startsWith("package ");
    }

    private static int lineStart(String text, int offset) {
        if (offset <= 0) {
            return 0;
        }
        int newline = text.lastIndexOf('\n', offset - 1);
        return newline < 0 ? 0 : newline + 1;
    }

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("$", "\\$");
    }

    private static boolean detectCaretMarker() {
        try {
            return (Boolean) AutoCompleteItem.class.getMethod("supportsCaretMarker").invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return false;
        }
    }
}
