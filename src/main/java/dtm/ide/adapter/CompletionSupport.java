package dtm.ide.adapter;

import dtm.ide.editor.CompletionRanking;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Slf4j
public final class CompletionSupport {
    private CompletionSupport() {
    }

    public static List<AutoCompleteItem> markUnusedMethods(List<AutoCompleteItem> items,
                                                    Function<Set<String>, Set<String>> unusedLookup) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        Set<String> methods = new LinkedHashSet<>();
        for (AutoCompleteItem item : items) {
            if (isMethodCompletion(item)) {
                methods.add(CompletionRanking.name(item));
            }
        }
        if (methods.isEmpty()) {
            return items;
        }
        try {
            Set<String> unused = unusedLookup.apply(methods);
            if (unused == null || unused.isEmpty()) {
                return items;
            }
            List<AutoCompleteItem> marked = new ArrayList<>(items.size());
            for (AutoCompleteItem item : items) {
                marked.add(isMethodCompletion(item) && unused.contains(CompletionRanking.name(item))
                        ? item.withUnused(true)
                        : item);
            }
            return List.copyOf(marked);
        } catch (LinkageError | RuntimeException e) {
            log.debug("Marcacao de metodos sem uso indisponivel: {}", e.toString());
            return items;
        }
    }

    private static boolean isMethodCompletion(AutoCompleteItem item) {
        return item != null && (item.kind() == AutoCompleteItem.Kind.METHOD
                || item.kind() == AutoCompleteItem.Kind.FUNCTION);
    }

    public static Character completionTriggerCharacter(String line, int col, Set<Character> triggers) {
        if (line == null || col <= 0 || col > line.length() || triggers == null) {
            return null;
        }
        char previous = line.charAt(col - 1);
        return triggers.contains(previous) ? previous : null;
    }

    public static List<AutoCompleteItem> filterCompletionSuggestions(List<AutoCompleteItem> source,
                                                               String prefix) {
        if (source == null || source.isEmpty()) return List.of();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.label() != null
                        && (needle.isEmpty()
                        || item.label().toLowerCase(Locale.ROOT).startsWith(needle)))
                .toList();
    }

    public static List<AutoCompleteItem> mergeCompletionSuggestions(List<AutoCompleteItem> contextual,
                                                              List<AutoCompleteItem> snippets) {
        List<AutoCompleteItem> merged = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        Set<String> labels = new HashSet<>();
        appendBySignature(contextual, merged, signatures, labels);
        appendByLabel(snippets, merged, labels);
        return List.copyOf(merged);
    }

    private static void appendBySignature(List<AutoCompleteItem> source,
                                          List<AutoCompleteItem> target,
                                          Set<String> signatures, Set<String> labels) {
        if (source == null) {
            return;
        }
        for (AutoCompleteItem item : source) {
            if (item == null || item.label() == null
                    || !signatures.add(completionSignature(item))) {
                continue;
            }
            labels.add(item.label().toLowerCase(Locale.ROOT));
            target.add(item);
        }
    }

    private static void appendByLabel(List<AutoCompleteItem> source,
                                      List<AutoCompleteItem> target, Set<String> labels) {
        if (source == null) {
            return;
        }
        for (AutoCompleteItem item : source) {
            if (item != null && item.label() != null
                    && labels.add(item.label().toLowerCase(Locale.ROOT))) {
                target.add(item);
            }
        }
    }

    static String completionSignature(AutoCompleteItem item) {
        return item.label().toLowerCase(Locale.ROOT)
                + "|" + (item.insertText() == null ? "" : item.insertText())
                + "|" + (item.detail() == null ? "" : item.detail())
                + "|" + item.kind();
    }

}
