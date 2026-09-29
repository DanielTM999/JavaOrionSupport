package dtm.ide.editor;

import java.util.Set;

public final class JavaTypingContext {

    enum Region { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK }

    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "return", "new", "throw", "case", "else", "yield", "instanceof", "assert", "do",
            "default", "throws", "extends", "implements", "permits", "import", "static");
    private static final Set<String> DECLARATION_TYPE_KEYWORDS = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "var");
    private static final Set<String> TYPE_NAME_KEYWORDS = Set.of(
            "class", "interface", "enum", "record", "package");

    private JavaTypingContext() {
    }

    public static boolean inCode(String text, int offset) {
        return regionAt(text, offset) == Region.CODE;
    }

    public static boolean allowsTriggerCharacter(String text, int offset, char trigger) {
        if (text == null || offset <= 0 || offset > text.length()) {
            return false;
        }
        int at = offset - 1;
        Region region = regionAt(text, at);
        return switch (trigger) {
            case '.' -> region == Region.CODE && !afterNumber(text, at);
            case '@' -> region == Region.CODE;
            case ':' -> region == Region.CODE && at > 0 && text.charAt(at - 1) == ':';
            case '(' -> region == Region.CODE && closesAnnotationName(text, at);
            case '$' -> region == Region.STRING || region == Region.TEXT_BLOCK;
            default -> false;
        };
    }

    public static boolean allowsIdleCompletion(String text, int offset) {
        if (text == null || offset <= 0 || offset > text.length()
                || regionAt(text, offset) != Region.CODE) {
            return false;
        }
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        if (start == offset || !Character.isJavaIdentifierStart(text.charAt(start))) {
            return false;
        }
        int end = start;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end == start || end == 0) {
            return true;
        }
        char before = text.charAt(end - 1);
        if (before == ']') {
            return false;
        }
        if (before == '>') {
            return !closesGenericType(text, end - 1);
        }
        if (!Character.isJavaIdentifierPart(before)) {
            return true;
        }
        int wordStart = end;
        while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        if (wordStart > 0 && text.charAt(wordStart - 1) == '@') {
            return true;
        }
        String previous = text.substring(wordStart, end);
        if (TYPE_NAME_KEYWORDS.contains(previous) || DECLARATION_TYPE_KEYWORDS.contains(previous)) {
            return false;
        }
        return EXPRESSION_KEYWORDS.contains(previous) || isModifier(previous);
    }

    static Region regionAt(String text, int position) {
        if (text == null) {
            return Region.CODE;
        }
        int limit = Math.max(0, Math.min(position, text.length()));
        Region region = Region.CODE;
        int i = 0;
        while (i < limit) {
            char c = text.charAt(i);
            switch (region) {
                case CODE -> {
                    if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                        region = Region.LINE_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                        region = Region.BLOCK_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '"' && text.startsWith("\"\"\"", i)) {
                        region = Region.TEXT_BLOCK;
                        i += 3;
                        continue;
                    }
                    if (c == '"') {
                        region = Region.STRING;
                    } else if (c == '\'') {
                        region = Region.CHAR;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        region = Region.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                        region = Region.CODE;
                        i += 2;
                        continue;
                    }
                }
                case STRING, CHAR -> {
                    char quote = region == Region.STRING ? '"' : '\'';
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == quote || c == '\n') {
                        region = Region.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (text.startsWith("\"\"\"", i)) {
                        region = Region.CODE;
                        i += 3;
                        continue;
                    }
                }
            }
            i++;
        }
        return region;
    }

    private static boolean afterNumber(String text, int dot) {
        int start = dot;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return start < dot && Character.isDigit(text.charAt(start));
    }

    private static boolean closesAnnotationName(String text, int paren) {
        int i = paren;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        int nameEnd = i;
        while (i > 0 && (Character.isJavaIdentifierPart(text.charAt(i - 1)) || text.charAt(i - 1) == '.')) {
            i--;
        }
        return i < nameEnd && i > 0 && text.charAt(i - 1) == '@';
    }

    private static boolean closesGenericType(String text, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '>') {
                depth++;
            } else if (c == '<') {
                depth--;
                if (depth == 0) {
                    return i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1));
                }
            } else if (c == '\n' || c == ';' || c == '(' || c == ')' || c == '='
                    || c == '&' || c == '|' || c == '{' || c == '}') {
                return false;
            }
        }
        return false;
    }

    private static boolean isModifier(String word) {
        return switch (word) {
            case "public", "protected", "private", "abstract", "final", "static", "sealed",
                 "transient", "volatile", "synchronized", "native", "strictfp" -> true;
            default -> false;
        };
    }
}
