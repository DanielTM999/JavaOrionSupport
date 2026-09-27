package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CompletionRanking {

    static final int EXACT = 0;
    static final int PREFIX = 1;
    static final int PREFIX_IGNORING_CASE = 2;
    static final int CAMEL_HUMPS = 3;
    static final int CONTAINS = 4;
    static final int OTHER = 5;

    private CompletionRanking() {
    }

    public static List<AutoCompleteItem> rank(List<AutoCompleteItem> items, String prefix) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (prefix == null || prefix.isEmpty()) {
            return items;
        }
        List<Ranked> ranked = new ArrayList<>(items.size());
        for (AutoCompleteItem item : items) {
            if (item != null) {
                ranked.add(new Ranked(tier(name(item), prefix), item));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::tier));
        return ranked.stream().map(Ranked::item).toList();
    }

    public static String name(AutoCompleteItem item) {
        String label = item.label() != null ? item.label() : item.insertText();
        if (label == null) {
            return "";
        }
        int end = label.length();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '(' || Character.isWhitespace(c)) {
                end = i;
                break;
            }
        }
        return label.substring(0, end);
    }

    static int tier(String name, String prefix) {
        if (name.equals(prefix)) {
            return EXACT;
        }
        if (name.startsWith(prefix)) {
            return PREFIX;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        if (lowerName.startsWith(lowerPrefix)) {
            return PREFIX_IGNORING_CASE;
        }
        if (matchesCamelHumps(name, prefix, 0, 0)) {
            return CAMEL_HUMPS;
        }
        if (lowerName.contains(lowerPrefix)) {
            return CONTAINS;
        }
        return OTHER;
    }

    private static boolean matchesCamelHumps(String name, String prefix, int nameIndex, int prefixIndex) {
        if (prefixIndex == prefix.length()) {
            return true;
        }
        if (nameIndex >= name.length()) {
            return false;
        }
        char wanted = Character.toLowerCase(prefix.charAt(prefixIndex));
        if (Character.toLowerCase(name.charAt(nameIndex)) == wanted
                && matchesCamelHumps(name, prefix, nameIndex + 1, prefixIndex + 1)) {
            return true;
        }
        if (prefixIndex == 0) {
            return false;
        }
        for (int next = nameIndex + 1; next < name.length(); next++) {
            if (isHumpStart(name, next) && Character.toLowerCase(name.charAt(next)) == wanted
                    && matchesCamelHumps(name, prefix, next + 1, prefixIndex + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHumpStart(String name, int index) {
        char c = name.charAt(index);
        char previous = name.charAt(index - 1);
        return Character.isUpperCase(c) && !Character.isUpperCase(previous)
                || previous == '_' && c != '_'
                || Character.isDigit(c) && !Character.isDigit(previous);
    }

    private record Ranked(int tier, AutoCompleteItem item) {
    }
}
