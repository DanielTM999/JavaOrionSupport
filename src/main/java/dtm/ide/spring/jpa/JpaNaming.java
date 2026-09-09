package dtm.ide.spring.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JpaNaming {

    private JpaNaming() {
    }

    public static String toSnakeCase(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    public static String uncapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    public static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public static List<String> camelSegments(String value) {
        List<String> segments = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return segments;
        }
        int start = 0;
        for (int i = 1; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                segments.add(value.substring(start, i));
                start = i;
            }
        }
        segments.add(value.substring(start));
        return segments;
    }

    public static int editDistance(String left, String right) {
        String a = left == null ? "" : left.toLowerCase(Locale.ROOT);
        String b = right == null ? "" : right.toLowerCase(Locale.ROOT);
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    public static String closest(String target, List<String> candidates) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return "";
        }
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        int limit = Math.max(2, target.length() / 2);
        for (String candidate : candidates) {
            int distance = editDistance(target, candidate);
            if (distance < bestDistance && distance <= limit) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
