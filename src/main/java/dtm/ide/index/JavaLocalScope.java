package dtm.ide.index;

import dtm.stools.component.panels.editor.code.api.Range;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

public final class JavaLocalScope {

    private static final Set<String> CONTROL = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else",
            "return", "case", "assert", "throw", "yield", "super", "this");
    private static final Set<String> TYPE_HOLDERS = Set.of(
            "new", "record", "class", "interface", "enum");
    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short");

    public record Scope(String name, Range declaration, boolean onDeclaration,
                        int startLine, int endLine, List<Range> usages) {
    }

    private JavaLocalScope() {
    }

    public static Scope at(String source, int line, int col) {
        if (source == null || source.isEmpty() || line < 0 || col < 0) {
            return null;
        }
        String code = JavaLexicalSource.mask(source);
        int[] lineStarts = JavaLexicalSource.lineStarts(code);
        int caret = offsetOf(code, lineStarts, line, col);
        if (caret < 0) {
            return null;
        }
        int[] span = wordSpan(code, caret);
        if (span == null) {
            return null;
        }
        String name = code.substring(span[0], span[1]);
        if (JavaLexicalSource.isKeyword(name)) {
            return null;
        }
        int[] region = methodRegion(code, span[0]);
        if (region == null || span[0] < region[0] || span[1] > region[1]) {
            return null;
        }
        int[] declaration = isDeclaration(code, span[0])
                ? span : declarationIn(code, region, name);
        if (declaration == null) {
            return null;
        }
        List<Range> usages = new ArrayList<>();
        for (int[] occurrence : JavaLexicalSource.occurrences(code, Set.of(name))) {
            if (occurrence[0] < region[0] || occurrence[1] > region[1]
                    || occurrence[0] == declaration[0]
                    || isMemberAccess(code, occurrence[0], occurrence[1])) {
                continue;
            }
            usages.add(JavaLexicalSource.rangeOf(lineStarts, occurrence[0], occurrence[1]));
        }
        return new Scope(name,
                JavaLexicalSource.rangeOf(lineStarts, declaration[0], declaration[1]),
                declaration[0] == span[0],
                JavaLexicalSource.lineOf(lineStarts, region[0]),
                JavaLexicalSource.lineOf(lineStarts, Math.max(region[0], region[1] - 1)),
                List.copyOf(usages));
    }

    private static int offsetOf(String code, int[] lineStarts, int line, int col) {
        if (line >= lineStarts.length) {
            return -1;
        }
        int start = lineStarts[line];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : code.length();
        return Math.min(start + col, end);
    }

    private static int[] wordSpan(String code, int caret) {
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
            end++;
        }
        if (start >= end || !Character.isJavaIdentifierStart(code.charAt(start))) {
            return null;
        }
        return new int[]{start, end};
    }

    private static int[] methodRegion(String code, int offset) {
        Deque<Integer> braces = new ArrayDeque<>();
        Deque<Integer> parens = new ArrayDeque<>();
        for (int i = 0; i < offset; i++) {
            switch (code.charAt(i)) {
                case '{' -> braces.push(i);
                case '}' -> pop(braces);
                case '(' -> parens.push(i);
                case ')' -> pop(parens);
                default -> {
                }
            }
        }
        if (!parens.isEmpty()) {
            int[] header = regionOfHeader(code, parens.peek());
            if (header != null) {
                return header;
            }
        }
        for (int brace : braces) {
            if (!isMethodBody(code, brace)) {
                continue;
            }
            int close = matchForward(code, brace, '{', '}');
            return new int[]{headerStart(code, brace), close < 0 ? code.length() : close + 1};
        }
        return null;
    }

    private static void pop(Deque<Integer> stack) {
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    private static int[] regionOfHeader(String code, int open) {
        if (!isMethodHeaderParen(code, open)) {
            return null;
        }
        int close = matchForward(code, open, '(', ')');
        if (close < 0) {
            return null;
        }
        int brace = bodyBraceAfter(code, close + 1);
        if (brace < 0) {
            return null;
        }
        int end = matchForward(code, brace, '{', '}');
        return new int[]{open, end < 0 ? code.length() : end + 1};
    }

    private static boolean isMethodBody(String code, int brace) {
        int index = skipBack(code, brace - 1);
        while (index >= 0 && code.charAt(index) != ')') {
            char current = code.charAt(index);
            if (!Character.isJavaIdentifierPart(current) && current != '.' && current != ',') {
                return false;
            }
            index = skipBack(code, index - 1);
        }
        if (index < 0) {
            return false;
        }
        int open = matchBackward(code, index, '(', ')');
        return open >= 0 && isMethodHeaderParen(code, open);
    }

    private static boolean isMethodHeaderParen(String code, int open) {
        int end = skipBack(code, open - 1) + 1;
        if (end <= 0 || !Character.isJavaIdentifierPart(code.charAt(end - 1))) {
            return false;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--;
        }
        String name = code.substring(start, end);
        if (CONTROL.contains(name) || JavaLexicalSource.isKeyword(name)) {
            return false;
        }
        int previous = skipBack(code, start - 1);
        if (previous < 0) {
            return true;
        }
        if (code.charAt(previous) == '.') {
            return false;
        }
        return !TYPE_HOLDERS.contains(tokenEndingAt(code, previous + 1));
    }

    private static int bodyBraceAfter(String code, int from) {
        int index = skipForward(code, from);
        while (index < code.length() && code.charAt(index) != '{') {
            char current = code.charAt(index);
            if (!Character.isJavaIdentifierPart(current) && current != '.' && current != ',') {
                return -1;
            }
            index = skipForward(code, index + 1);
        }
        return index < code.length() ? index : -1;
    }

    private static int headerStart(String code, int brace) {
        int index = skipBack(code, brace - 1);
        while (index >= 0 && code.charAt(index) != ')') {
            index = skipBack(code, index - 1);
        }
        if (index < 0) {
            return brace;
        }
        int open = matchBackward(code, index, '(', ')');
        return open < 0 ? brace : open;
    }

    private static int[] declarationIn(String code, int[] region, String name) {
        for (int[] occurrence : JavaLexicalSource.occurrences(code, Set.of(name))) {
            if (occurrence[0] < region[0] || occurrence[1] > region[1]) {
                continue;
            }
            if (!isMemberAccess(code, occurrence[0], occurrence[1])
                    && isDeclaration(code, occurrence[0])) {
                return occurrence;
            }
        }
        return null;
    }

    private static boolean isMemberAccess(String code, int start, int end) {
        int before = skipBack(code, start - 1);
        if (before >= 0 && code.charAt(before) == '.') {
            return true;
        }
        int after = skipForward(code, end);
        return after < code.length() && code.charAt(after) == '(';
    }

    private static boolean isDeclaration(String code, int start) {
        int index = skipBack(code, start - 1);
        if (index < 0) {
            return false;
        }
        char previous = code.charAt(index);
        if (previous == '>' || previous == ']') {
            return true;
        }
        if (!Character.isJavaIdentifierPart(previous)) {
            return false;
        }
        String token = tokenEndingAt(code, index + 1);
        if (token.equals("var") || token.equals("final") || token.equals("instanceof")) {
            return true;
        }
        if (JavaLexicalSource.isKeyword(token)) {
            return PRIMITIVES.contains(token);
        }
        return true;
    }

    private static String tokenEndingAt(String code, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--;
        }
        return start >= end ? "" : code.substring(start, end);
    }

    private static int skipBack(String code, int from) {
        int index = Math.min(from, code.length() - 1);
        while (index >= 0 && Character.isWhitespace(code.charAt(index))) {
            index--;
        }
        return index;
    }

    private static int skipForward(String code, int from) {
        int index = Math.max(0, from);
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int matchForward(String code, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char current = code.charAt(i);
            if (current == opening) {
                depth++;
            } else if (current == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int matchBackward(String code, int close, char opening, char closing) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char current = code.charAt(i);
            if (current == closing) {
                depth++;
            } else if (current == opening && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
