package dtm.ide.adapter;

import dtm.ide.api.project.editor.IdeGhostTextContext;
import dtm.ide.editor.JavaSnippetCompletionProvider;
import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.ghost.GhostTextSuggestion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class GhostTextSupport {
    public interface Host {
        boolean debugActive();
        JavaLanguageServer interactiveServerFor(Path file);
        JavaProjectDescriptor descriptor();
        JavaSnippetCompletionProvider snippets();
    }

    private final Host host;

    public GhostTextSupport(Host host) {
        this.host = host;
    }

    private static final Pattern SNIPPET_DEFAULT = Pattern.compile("\\$\\{\\d+:([^}]*)}");
    private static final Pattern SNIPPET_PLACEHOLDER = Pattern.compile("\\$\\{?\\d+}?");
    private static final List<String> JAVA_GHOST_KEYWORDS = List.of(
            "this", "throw", "throws", "true", "try", "return", "public", "private",
            "protected", "static", "final", "class", "interface", "record", "extends",
            "implements", "new", "null", "super", "switch", "synchronized", "instanceof",
            "import", "package", "void", "boolean");

    private static boolean blockedByFollowingText(String line, int caretCol) {
        if (line == null || caretCol < 0 || caretCol >= line.length()) {
            return false;
        }
        char next = line.charAt(caretCol);
        return Character.isJavaIdentifierPart(next)
                || next == '"'
                || next == '\''
                || next == '('
                || next == '.'
                || next == '@';
    }

    public GhostTextSuggestion getGhostSuggestion(IdeGhostTextContext context) {
        if (host.debugActive() || context == null) {
            return null;
        }
        if (blockedByFollowingText(context.currentLine(), context.caretCol())) {
            return null;
        }
        String prefix = identifierPrefix(context.currentLine(), context.caretCol());
        boolean memberAccess = isMemberAccess(context.currentLine(), context.caretCol(), prefix);
        if (prefix.isEmpty() && !memberAccess) {
            return null;
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context.filePath());
        List<AutoCompleteItem> contextual = List.of();
        if (lsp != null) {
            contextual = lsp.reusableCompletions(context.filePath(), context.text(),
                    context.caretLine(), context.caretCol());
            if (contextual.isEmpty()) {
                contextual = lsp.complete(context.filePath(), context.text(),
                        context.caretLine(), context.caretCol(),
                        CompletionTrigger.INVOKED, null,
                        lsp.documentVersion(context.filePath()));
            }
        }
        JavaProjectDescriptor current = host.descriptor();
        List<AutoCompleteItem> local = memberAccess ? List.of() : host.snippets().suggestions(prefix,
                current != null && current.spring());
        List<AutoCompleteItem> ghostCandidates = new ArrayList<>(contextual);
        ghostCandidates.addAll(local);
        GhostChoice choice = ghostTextChoice(ghostCandidates, prefix, memberAccess);
        if (choice != null) {
            return new GhostTextSuggestion(indentMultilineGhostText(choice.suffix(), context.currentLine()),
                    choice.item().additionalTextEdits());
        }
        return GhostTextSuggestion.of(lexicalGhostTextSuffix(context.text(), prefix));
    }

    record GhostChoice(String suffix, AutoCompleteItem item) {
    }

    static String ghostTextSuffix(List<AutoCompleteItem> items, String prefix) {
        return ghostTextSuffix(items, prefix, false);
    }

    static String ghostTextSuffix(List<AutoCompleteItem> items, String prefix,
                                  boolean allowEmptyPrefix) {
        GhostChoice choice = ghostTextChoice(items, prefix, allowEmptyPrefix);
        return choice == null ? null : choice.suffix();
    }

    static GhostChoice ghostTextChoice(List<AutoCompleteItem> items, String prefix,
                                       boolean allowEmptyPrefix) {
        if (items == null || prefix == null || (prefix.isEmpty() && !allowEmptyPrefix)) {
            return null;
        }
        GhostChoice insensitive = null;
        for (AutoCompleteItem item : items) {
            if (item == null) {
                continue;
            }
            String insert = sanitizeSnippetForGhostText(item.insertText());
            if (insert == null || insert.isBlank() || insert.length() <= prefix.length()) {
                continue;
            }
            insert = limitGhostText(insert);
            if (insert.startsWith(prefix)) {
                return new GhostChoice(insert.substring(prefix.length()), item);
            }
            if (insensitive == null && insert.regionMatches(true, 0, prefix, 0,
                    prefix.length())) {
                insensitive = new GhostChoice(insert.substring(prefix.length()), item);
            }
        }
        return insensitive;
    }

    private static boolean isMemberAccess(String line, int col, String prefix) {
        if (line == null || col <= 0 || col > line.length() || !prefix.isEmpty()) {
            return false;
        }
        int index = col - 1;
        while (index >= 0 && Character.isWhitespace(line.charAt(index))) {
            index--;
        }
        return index >= 0 && line.charAt(index) == '.';
    }

    static String indentMultilineGhostText(String suffix, String currentLine) {
        if (suffix == null || suffix.indexOf('\n') < 0) {
            return suffix;
        }
        String line = currentLine == null ? "" : currentLine;
        int indentEnd = 0;
        while (indentEnd < line.length()) {
            char value = line.charAt(indentEnd);
            if (value != ' ' && value != '\t') {
                break;
            }
            indentEnd++;
        }
        String indent = line.substring(0, indentEnd);
        return suffix.replace("\r\n", "\n").replace("\n", "\n" + indent);
    }

    private static String limitGhostText(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int lines = 1;
        int end = Math.min(normalized.length(), 2_000);
        for (int index = 0; index < end; index++) {
            if (normalized.charAt(index) == '\n' && ++lines > 24) {
                end = index;
                break;
            }
        }
        return normalized.substring(0, end);
    }

    static String lexicalGhostTextSuffix(String text, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        String exact = shortestIdentifierSuffix(text, prefix, false);
        if (exact != null) {
            return exact;
        }
        for (String keyword : JAVA_GHOST_KEYWORDS) {
            if (keyword.startsWith(prefix) && keyword.length() > prefix.length()) {
                return keyword.substring(prefix.length());
            }
        }
        String insensitive = shortestIdentifierSuffix(text, prefix, true);
        if (insensitive != null) {
            return insensitive;
        }
        for (String keyword : JAVA_GHOST_KEYWORDS) {
            if (keyword.length() > prefix.length()
                    && keyword.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return keyword.substring(prefix.length());
            }
        }
        return null;
    }

    private static String shortestIdentifierSuffix(String text, String prefix,
                                                   boolean ignoreCase) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String best = null;
        int identifiers = 0;
        for (int index = 0; index < text.length() && identifiers < 512;) {
            if (!Character.isJavaIdentifierStart(text.charAt(index))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                end++;
            }
            identifiers++;
            String candidate = text.substring(index, end);
            boolean matches = ignoreCase
                    ? candidate.regionMatches(true, 0, prefix, 0, prefix.length())
                    : candidate.startsWith(prefix);
            if (matches && candidate.length() > prefix.length()) {
                String suffix = candidate.substring(prefix.length());
                if (best == null || suffix.length() < best.length()) {
                    best = suffix;
                }
            }
            index = end;
        }
        return best;
    }

    static String sanitizeSnippetForGhostText(String value) {
        if (value == null) {
            return null;
        }
        String expanded = SNIPPET_DEFAULT.matcher(value).replaceAll("$1");
        return SNIPPET_PLACEHOLDER.matcher(expanded).replaceAll("");
    }

    private static String identifierPrefix(String line, int col) {
        if (line == null || col <= 0 || col > line.length()) {
            return "";
        }
        int start = col;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, col);
    }

}
