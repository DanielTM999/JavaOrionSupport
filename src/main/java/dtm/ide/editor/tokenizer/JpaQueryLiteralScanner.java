package dtm.ide.editor.tokenizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class JpaQueryLiteralScanner {

    private static final Pattern NATIVE_QUERY = Pattern.compile(
            "(?i)\\bnativeQuery\\s*=\\s*true\\b");

    public record QueryLiteral(int start, int contentStart, int contentEnd, int end,
                               int expressionStart, int expressionEnd,
                               boolean textBlock, boolean nativeSql) {

        public boolean contains(int offset) {
            return offset >= contentStart && offset <= contentEnd;
        }
    }

    public record QueryAnnotation(int start, int end) {

        boolean intersects(int from, int to) {
            return from <= end && to >= start;
        }
    }

    public record Scan(Map<Integer, QueryLiteral> literals, List<QueryAnnotation> annotations) {

        public Scan {
            literals = literals == null ? Map.of() : Map.copyOf(literals);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        public QueryLiteral literalAt(int offset) {
            return literals.get(offset);
        }

        public QueryLiteral literalContaining(int offset) {
            return literals.values().stream()
                    .filter(literal -> literal.contains(offset))
                    .findFirst()
                    .orElse(null);
        }

        boolean intersects(int from, int to) {
            return annotations.stream().anyMatch(annotation -> annotation.intersects(from, to));
        }
    }

    public static final Scan EMPTY = new Scan(Map.of(), List.of());

    private JpaQueryLiteralScanner() {
    }

    public static Scan scan(String rawSource) {
        String source = rawSource == null ? "" : rawSource;
        Map<Integer, QueryLiteral> literals = new LinkedHashMap<>();
        List<QueryAnnotation> annotations = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            if (source.startsWith("//", cursor)) {
                cursor = lineEnd(source, cursor);
                continue;
            }
            if (source.startsWith("/*", cursor)) {
                cursor = blockCommentEnd(source, cursor);
                continue;
            }
            char c = source.charAt(cursor);
            if (c == '"' || c == '\'') {
                cursor = literalEnd(source, cursor);
                continue;
            }
            if (c != '@') {
                cursor++;
                continue;
            }

            int nameStart = cursor + 1;
            int nameEnd = nameStart;
            while (nameEnd < source.length()) {
                char nameChar = source.charAt(nameEnd);
                if (!Character.isJavaIdentifierPart(nameChar) && nameChar != '.') {
                    break;
                }
                nameEnd++;
            }
            if (nameEnd == nameStart || !"Query".equals(simpleName(source, nameStart, nameEnd))) {
                cursor = Math.max(cursor + 1, nameEnd);
                continue;
            }
            int open = skipWhitespace(source, nameEnd);
            if (open >= source.length() || source.charAt(open) != '(') {
                cursor = nameEnd;
                continue;
            }
            int close = annotationClose(source, open);
            int argumentsEnd = close < source.length() ? close : source.length();
            String arguments = source.substring(open + 1, argumentsEnd);
            boolean nativeSql = NATIVE_QUERY.matcher(maskNonCode(arguments)).find();
            collectQueryLiterals(source, open + 1, argumentsEnd, nativeSql, literals);
            int annotationEnd = close < source.length() ? close + 1 : source.length();
            annotations.add(new QueryAnnotation(cursor, annotationEnd));
            cursor = Math.max(cursor + 1, annotationEnd);
        }
        return new Scan(literals, annotations);
    }

    private static void collectQueryLiterals(String source, int from, int to, boolean nativeSql,
                                             Map<Integer, QueryLiteral> result) {
        String arguments = source.substring(from, to);
        String masked = maskNonCode(arguments);
        int segmentStart = 0;
        int depth = 0;
        boolean unnamedSeen = false;
        for (int i = 0; i <= masked.length(); i++) {
            char c = i < masked.length() ? masked.charAt(i) : ',';
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (c != ',' || depth != 0) {
                continue;
            }
            int equals = topLevelEquals(masked, segmentStart, i);
            String attribute = equals < 0 ? ""
                    : masked.substring(segmentStart, equals).trim();
            boolean selected = equals < 0
                    ? !unnamedSeen
                    : "value".equals(attribute) || "countQuery".equals(attribute);
            if (equals < 0) {
                unnamedSeen = true;
            }
            if (selected) {
                int expressionStart = equals < 0 ? segmentStart : equals + 1;
                collectStringLiterals(source, from + expressionStart, from + i, nativeSql, result);
            }
            segmentStart = i + 1;
        }
    }

    private static int topLevelEquals(String masked, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == '=' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static void collectStringLiterals(String source, int from, int to, boolean nativeSql,
                                              Map<Integer, QueryLiteral> result) {
        int cursor = from;
        while (cursor < to) {
            char c = source.charAt(cursor);
            if (c != '"') {
                cursor++;
                continue;
            }
            boolean textBlock = source.startsWith("\"\"\"", cursor);
            int delimiter = textBlock ? 3 : 1;
            int end = Math.min(to, literalEnd(source, cursor));
            boolean closed = end - delimiter >= cursor + delimiter
                    && end <= source.length()
                    && source.substring(Math.max(cursor + delimiter, end - delimiter), end)
                    .equals(textBlock ? "\"\"\"" : "\"");
            int contentEnd = closed ? end - delimiter : end;
            result.put(cursor, new QueryLiteral(cursor, cursor + delimiter, contentEnd, end,
                    from, to, textBlock, nativeSql));
            cursor = Math.max(cursor + delimiter, end);
        }
    }

    private static int annotationClose(String source, int open) {
        int depth = 0;
        int cursor = open;
        while (cursor < source.length()) {
            if (source.startsWith("//", cursor)) {
                cursor = lineEnd(source, cursor);
                continue;
            }
            if (source.startsWith("/*", cursor)) {
                cursor = blockCommentEnd(source, cursor);
                continue;
            }
            char c = source.charAt(cursor);
            if (c == '"' || c == '\'') {
                cursor = literalEnd(source, cursor);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return cursor;
            }
            cursor++;
        }
        return source.length();
    }

    private static int literalEnd(String source, int at) {
        if (source.startsWith("\"\"\"", at)) {
            int cursor = at + 3;
            while (cursor < source.length()) {
                if (source.charAt(cursor) == '\\' && cursor + 1 < source.length()) {
                    cursor += 2;
                } else if (source.startsWith("\"\"\"", cursor)) {
                    return cursor + 3;
                } else {
                    cursor++;
                }
            }
            return source.length();
        }
        char quote = source.charAt(at);
        int cursor = at + 1;
        while (cursor < source.length()) {
            char c = source.charAt(cursor);
            if (c == '\\' && cursor + 1 < source.length()) {
                cursor += 2;
            } else if (c == quote) {
                return cursor + 1;
            } else if (c == '\n' || c == '\r') {
                return cursor;
            } else {
                cursor++;
            }
        }
        return cursor;
    }

    private static String maskNonCode(String source) {
        char[] masked = source.toCharArray();
        int cursor = 0;
        while (cursor < source.length()) {
            int end;
            if (source.startsWith("//", cursor)) {
                end = lineEnd(source, cursor);
            } else if (source.startsWith("/*", cursor)) {
                end = blockCommentEnd(source, cursor);
            } else {
                char c = source.charAt(cursor);
                if (c != '"' && c != '\'') {
                    cursor++;
                    continue;
                }
                end = literalEnd(source, cursor);
            }
            for (int i = cursor; i < end; i++) {
                if (masked[i] != '\n' && masked[i] != '\r') {
                    masked[i] = ' ';
                }
            }
            cursor = Math.max(cursor + 1, end);
        }
        return new String(masked);
    }

    private static String simpleName(String source, int from, int to) {
        int dot = source.lastIndexOf('.', to - 1);
        return source.substring(Math.max(from, dot + 1), to);
    }

    private static int skipWhitespace(String source, int at) {
        int cursor = at;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int lineEnd(String source, int at) {
        int end = source.indexOf('\n', at + 2);
        return end < 0 ? source.length() : end;
    }

    private static int blockCommentEnd(String source, int at) {
        int end = source.indexOf("*/", at + 2);
        return end < 0 ? source.length() : end + 2;
    }
}
