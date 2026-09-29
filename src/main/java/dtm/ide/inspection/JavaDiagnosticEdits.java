package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps diagnostic ranges aligned with edits that leave Java tokens unchanged. */
public final class JavaDiagnosticEdits {

    private record Token(String value, int start, int end) {
    }

    private JavaDiagnosticEdits() {
    }

    public static boolean sameCode(String before, String after) {
        if (before == null || after == null) return false;
        if (before.equals(after)) return true;
        List<Token> oldTokens = tokens(before);
        List<Token> newTokens = tokens(after);
        if (oldTokens.size() != newTokens.size()) return false;
        for (int i = 0; i < oldTokens.size(); i++) {
            if (!oldTokens.get(i).value().equals(newTokens.get(i).value())) return false;
        }
        return true;
    }

    private static List<Token> tokens(String source) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = source.indexOf('\n', i + 2);
                if (i < 0) i = source.length();
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                int start = i;
                boolean block = c == '"' && source.startsWith("\"\"\"", i);
                i += block ? 3 : 1;
                while (i < source.length()) {
                    if (source.charAt(i) == '\\') {
                        i = Math.min(source.length(), i + 2);
                    } else if (block && source.startsWith("\"\"\"", i)) {
                        i += 3;
                        break;
                    } else if (!block && source.charAt(i) == c) {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                result.add(new Token(source.substring(start, i), start, i));
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                if (i < source.length() && source.charAt(i) == '.'
                        && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1))) {
                    i++;
                    while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                }
                result.add(new Token(source.substring(start, i), start, i));
            } else if (Character.isJavaIdentifierPart(c)) {
                int start = i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                result.add(new Token(source.substring(start, i), start, i));
            } else {
                int start = i++;
                while (i < source.length() && isOperator(source.charAt(i)) && isOperator(c)) i++;
                result.add(new Token(source.substring(start, i), start, i));
            }
        }
        return result;
    }

    private static boolean isOperator(char c) {
        return "+-*/%=&|^!<>?:~.".indexOf(c) >= 0;
    }

    public static List<Diagnostic> move(Collection<Diagnostic> previous, String before, String after) {
        if (previous == null || previous.isEmpty()) return List.of();
        if (before == null || after == null) return List.copyOf(previous);
        int prefix = 0;
        int bound = Math.min(before.length(), after.length());
        while (prefix < bound && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int oldEnd = before.length();
        int newEnd = after.length();
        while (oldEnd > prefix && newEnd > prefix
                && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        List<Token> oldTokens = tokens(before);
        List<Token> newTokens = tokens(after);
        boolean aligned = oldTokens.size() == newTokens.size();
        List<Diagnostic> moved = new ArrayList<>(previous.size());
        for (Diagnostic diagnostic : previous) {
            int start = offset(before, diagnostic.startLine(), diagnostic.startCol());
            int end = offset(before, diagnostic.endLine(), diagnostic.endCol());
            int mappedStart = aligned ? tokenOffset(start, false, oldTokens, newTokens) : -1;
            int mappedEnd = aligned ? tokenOffset(end, true, oldTokens, newTokens) : -1;
            if (mappedStart < 0) mappedStart = map(start, prefix, oldEnd, newEnd, false);
            if (mappedEnd < 0) mappedEnd = map(end, prefix, oldEnd, newEnd, true);
            int[] startPosition = position(after, mappedStart);
            int[] endPosition = position(after, Math.max(mappedStart, mappedEnd));
            moved.add(new Diagnostic(startPosition[0], startPosition[1], endPosition[0],
                    endPosition[1], diagnostic.severity(), diagnostic.message(), diagnostic.source(),
                    diagnostic.overrideColor(), DiagnosticTags.isUnnecessary(diagnostic)));
        }
        return List.copyOf(moved);
    }

    private static int tokenOffset(int offset, boolean end, List<Token> before, List<Token> after) {
        for (int i = 0; i < before.size(); i++) {
            Token oldToken = before.get(i);
            if (end ? oldToken.start() < offset && offset <= oldToken.end()
                    : oldToken.start() <= offset && offset < oldToken.end()) {
                Token newToken = after.get(i);
                return newToken.start() + Math.min(offset - oldToken.start(),
                        newToken.end() - newToken.start());
            }
        }
        return -1;
    }

    private static int map(int offset, int prefix, int oldEnd, int newEnd, boolean end) {
        if (oldEnd == prefix) {
            return offset < prefix || (end && offset == prefix)
                    ? offset : offset + newEnd - oldEnd;
        }
        if (offset < prefix) return offset;
        if (offset > oldEnd || (offset == oldEnd && oldEnd > prefix)) return offset + newEnd - oldEnd;
        return end ? newEnd : prefix;
    }

    private static int offset(String source, int line, int col) {
        int start = 0;
        for (int current = 0; current < line; current++) {
            int next = source.indexOf('\n', start);
            if (next < 0) return source.length();
            start = next + 1;
        }
        return Math.min(source.length(), Math.max(0, start + col));
    }

    private static int[] position(String source, int offset) {
        int bounded = Math.max(0, Math.min(source.length(), offset));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < bounded; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, bounded - lineStart};
    }
}
