package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class JavaDiagnosticEdits {

    private static final int STRADDLE_MARGIN = 4096;

    private JavaDiagnosticEdits() {
    }

    public static boolean sameCode(String before, String after) {
        if (before == null || after == null) return false;
        if (before.equals(after)) return true;
        Change change = Change.of(before, after);
        int delta = after.length() - before.length();
        int i = 0;
        int j = 0;
        while (true) {
            i = skipTrivia(before, i);
            j = skipTrivia(after, j);
            boolean beforeDone = i >= before.length();
            boolean afterDone = j >= after.length();
            if (beforeDone || afterDone) return beforeDone && afterDone;
            if (i >= change.oldEnd() && j >= change.newEnd() && j - i == delta) return true;
            int beforeEnd = tokenEnd(before, i);
            int afterEnd = tokenEnd(after, j);
            if (beforeEnd - i != afterEnd - j || !before.regionMatches(i, after, j, beforeEnd - i)) {
                return false;
            }
            i = beforeEnd;
            j = afterEnd;
        }
    }

    private static int[] tokens(String source) {
        int[] result = new int[64];
        int size = 0;
        int i = skipTrivia(source, 0);
        while (i < source.length()) {
            int end = tokenEnd(source, i);
            if (size + 2 > result.length) result = Arrays.copyOf(result, result.length * 2);
            result[size++] = i;
            result[size++] = end;
            i = skipTrivia(source, end);
        }
        return Arrays.copyOf(result, size);
    }

    private static int skipTrivia(String source, int from) {
        int i = from;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                i = source.indexOf('\n', i + 2);
                if (i < 0) return length;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                return i;
            }
        }
        return length;
    }

    private static int tokenEnd(String source, int start) {
        int length = source.length();
        char c = source.charAt(start);
        int i = start;
        if (c == '"' || c == '\'') {
            boolean block = c == '"' && source.startsWith("\"\"\"", i);
            i += block ? 3 : 1;
            while (i < length) {
                if (source.charAt(i) == '\\') {
                    i = Math.min(length, i + 2);
                } else if (block && source.startsWith("\"\"\"", i)) {
                    return i + 3;
                } else if (!block && source.charAt(i) == c) {
                    return i + 1;
                } else {
                    i++;
                }
            }
            return length;
        }
        if (Character.isDigit(c)) {
            i++;
            while (i < length && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            if (i < length && source.charAt(i) == '.'
                    && i + 1 < length && Character.isDigit(source.charAt(i + 1))) {
                i++;
                while (i < length && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            }
            return i;
        }
        if (Character.isJavaIdentifierPart(c)) {
            i++;
            while (i < length && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            return i;
        }
        i++;
        while (i < length && isOperator(source.charAt(i)) && isOperator(c)) i++;
        return i;
    }

    private static boolean isOperator(char c) {
        return "+-*/%=&|^!<>?:~.".indexOf(c) >= 0;
    }

    public static List<Diagnostic> retainUnaffected(Collection<Diagnostic> previous, String before, String after) {
        if (previous == null || previous.isEmpty()) return List.of();
        if (before == null || after == null || before.equals(after)) return List.copyOf(previous);
        Change change = Change.of(before, after);
        int[] lines = lineStarts(before);
        List<Diagnostic> retained = previous.stream().filter(d -> {
            int start = offset(before, lines, d.startLine(), d.startCol());
            int end = offset(before, lines, d.endLine(), d.endCol());
            return change.prefix() == change.oldEnd()
                    ? !(start <= change.prefix() && change.prefix() < Math.max(start + 1, end))
                    : !(start < change.oldEnd() && Math.max(start + 1, end) > change.prefix());
        }).toList();
        return move(retained, before, after);
    }

    public static List<Diagnostic> move(Collection<Diagnostic> previous, String before, String after) {
        if (previous == null || previous.isEmpty()) return List.of();
        if (before == null || after == null) return List.copyOf(previous);
        Change change = Change.of(before, after);
        int[] beforeLines = lineStarts(before);
        int[] afterLines = lineStarts(after);
        int[] oldTokens = null;
        int[] newTokens = null;
        boolean aligned = false;
        List<Diagnostic> moved = new ArrayList<>(previous.size());
        for (Diagnostic diagnostic : previous) {
            int start = offset(before, beforeLines, diagnostic.startLine(), diagnostic.startCol());
            int end = offset(before, beforeLines, diagnostic.endLine(), diagnostic.endCol());
            int mappedStart = -1;
            int mappedEnd = -1;
            if (touchesChange(start, end, change)) {
                if (oldTokens == null) {
                    oldTokens = tokens(before);
                    newTokens = tokens(after);
                    aligned = oldTokens.length == newTokens.length;
                }
                if (aligned) {
                    mappedStart = tokenOffset(start, false, oldTokens, newTokens);
                    mappedEnd = tokenOffset(end, true, oldTokens, newTokens);
                }
            }
            if (mappedStart < 0) mappedStart = map(start, change.prefix(), change.oldEnd(), change.newEnd(), false);
            if (mappedEnd < 0) mappedEnd = map(end, change.prefix(), change.oldEnd(), change.newEnd(), true);
            int[] startPosition = position(after, afterLines, mappedStart);
            int[] endPosition = position(after, afterLines, Math.max(mappedStart, mappedEnd));
            moved.add(new Diagnostic(startPosition[0], startPosition[1], endPosition[0],
                    endPosition[1], diagnostic.severity(), diagnostic.message(), diagnostic.source(),
                    diagnostic.overrideColor(), DiagnosticTags.isUnnecessary(diagnostic)));
        }
        return List.copyOf(moved);
    }

    private static boolean touchesChange(int start, int end, Change change) {
        return end >= change.prefix() && start <= change.oldEnd() + STRADDLE_MARGIN;
    }

    private static int tokenOffset(int offset, boolean end, int[] before, int[] after) {
        int low = 0;
        int high = before.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int oldStart = before[mid * 2];
            int oldEnd = before[mid * 2 + 1];
            boolean inside = end ? oldStart < offset && offset <= oldEnd : oldStart <= offset && offset < oldEnd;
            if (inside) {
                int newStart = after[mid * 2];
                return newStart + Math.min(offset - oldStart, after[mid * 2 + 1] - newStart);
            }
            if ((end ? offset <= oldStart : offset < oldStart)) {
                high = mid - 1;
            } else {
                low = mid + 1;
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

    private static int[] lineStarts(String source) {
        int[] starts = new int[64];
        int count = 0;
        starts[count++] = 0;
        for (int i = source.indexOf('\n'); i >= 0; i = source.indexOf('\n', i + 1)) {
            if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
            starts[count++] = i + 1;
        }
        return Arrays.copyOf(starts, count);
    }

    private static int offset(String source, int[] lineStarts, int line, int col) {
        if (line >= lineStarts.length) return source.length();
        int start = lineStarts[Math.max(0, line)];
        return Math.min(source.length(), Math.max(0, start + col));
    }

    private static int[] position(String source, int[] lineStarts, int offset) {
        int bounded = Math.max(0, Math.min(source.length(), offset));
        int index = Arrays.binarySearch(lineStarts, bounded);
        int line = index >= 0 ? index : -index - 2;
        return new int[]{line, bounded - lineStarts[line]};
    }

    private record Change(int prefix, int oldEnd, int newEnd) {

        static Change of(String before, String after) {
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
            return new Change(prefix, oldEnd, newEnd);
        }
    }
}
