package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class JavaTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_ANNOTATION = "JAVA_ANNOTATION";
    public static final String TOKEN_TYPE = "JAVA_TYPE";
    public static final String TOKEN_METHOD = "JAVA_METHOD";
    public static final String TOKEN_JAVADOC = "JAVA_JAVADOC";
    public static final String TOKEN_TEXT_BLOCK = "JAVA_TEXT_BLOCK";

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while",
            "true", "false", "null",
            "var", "record", "sealed", "permits", "yield", "non-sealed", "when"
    );

    private static final Set<String> IMPLICIT_TYPES = Set.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Character",
            "Byte", "Short", "Number", "Math", "System", "Thread", "Runnable", "Exception",
            "RuntimeException", "Error", "Throwable", "Class", "Enum", "Record", "Iterable",
            "Comparable", "CharSequence", "StringBuilder", "StringBuffer", "Void"
    );

    /** Old and new text of an edit are scanned alternately, so two entries avoid thrashing. */
    private record CachedScan(String source, JpaQueryLiteralScanner.Scan scan) {
    }

    private volatile CachedScan recentScan;
    private volatile CachedScan olderScan;

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public Collection<Token> tokenize(TokenizeChange change,
                                      TokenClassifierCodeEditorProvider classifier) {
        if (change != null && queryAnnotationChanged(change)) {
            return tokenize(change.newText(), classifier);
        }
        return IncrementalTokenization.retokenizeFromSafeLine(change, classifier, this::tokenize,
                JavaTokenizerProvider::withoutQueryLiterals);
    }

    @Override
    public Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String source = text == null ? "" : text;
        List<Token> tokens = new ArrayList<>(Math.max(16, source.length() / 4));
        JpaQueryLiteralScanner.Scan queryLiterals = mentionsQuery(source)
                ? scanOf(source)
                : JpaQueryLiteralScanner.EMPTY;

        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);

            if (c == '\r' || c == '\n') {
                int start = i++;
                if (c == '\r' && i < source.length() && source.charAt(i) == '\n') {
                    i++;
                }
                tokens.add(token(source, start, i, TokenType.NEWLINE));
            } else if (Character.isWhitespace(c)) {
                int start = i++;
                while (i < source.length() && isInlineWhitespace(source.charAt(i))) {
                    i++;
                }
                tokens.add(token(source, start, i, TokenType.WHITESPACE));
            } else if (source.startsWith("//", i)) {
                int start = i;
                i = lineEnd(source, i);
                tokens.add(token(source, start, i, TokenType.COMMENT));
            } else if (source.startsWith("/*", i)) {
                int start = i;
                boolean javadoc = source.startsWith("/**", i) && !source.startsWith("/**/", i);
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                tokens.add(token(source, start, i, javadoc ? TOKEN_JAVADOC : TokenType.COMMENT));
            } else if (source.startsWith("\"\"\"", i)) {
                int start = i;
                i = textBlockEnd(source, i);
                JpaQueryLiteralScanner.QueryLiteral query = queryLiterals.literalAt(start);
                if (query == null) {
                    tokens.add(token(source, start, i, TOKEN_TEXT_BLOCK));
                } else {
                    addQueryTokens(tokens, source, query, TOKEN_TEXT_BLOCK);
                }
            } else if (c == '"' || c == '\'') {
                int start = i;
                i = stringEnd(source, i, c);
                JpaQueryLiteralScanner.QueryLiteral query = c == '"'
                        ? queryLiterals.literalAt(start) : null;
                if (query == null) {
                    tokens.add(token(source, start, i, TokenType.STRING));
                } else {
                    addQueryTokens(tokens, source, query, TokenType.STRING);
                }
            } else if (c == '@' && i + 1 < source.length() && isIdentifierStart(source.charAt(i + 1))) {
                int start = i;
                i = annotationEnd(source, i);
                tokens.add(token(source, start, i, TOKEN_ANNOTATION));
            } else if (Character.isDigit(c)
                    || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                i = numberEnd(source, i);
                tokens.add(token(source, start, i, TokenType.NUMBER));
            } else if (isIdentifierStart(c)) {
                int start = i;
                while (i < source.length() && isIdentifierPart(source.charAt(i))) {
                    i++;
                }
                String word = source.substring(start, i);
                tokens.add(token(source, start, i, classifyWord(source, start, i, word, classifier)));
            } else {
                tokens.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            }
        }
        return tokens;
    }

    private boolean queryAnnotationChanged(TokenizeChange change) {
        String oldText = change.oldText() == null ? "" : change.oldText();
        String newText = change.newText() == null ? "" : change.newText();
        if (!mentionsQuery(oldText) && !mentionsQuery(newText)) {
            return false;
        }
        int start = Math.max(0, change.changeOffset());
        int oldEnd = Math.min(oldText.length(), start + Math.max(0, change.removedLength()));
        int newEnd = Math.min(newText.length(), start
                + (change.insertedText() == null ? 0 : change.insertedText().length()));
        return scanOf(oldText).intersects(start, oldEnd)
                || scanOf(newText).intersects(start, newEnd);
    }

    private static boolean withoutQueryLiterals(String text, int from, int to) {
        int found = text.indexOf("Query", from);
        return found < 0 || found >= to;
    }

    private static boolean mentionsQuery(String text) {
        return text != null && text.indexOf("Query") >= 0;
    }

    private JpaQueryLiteralScanner.Scan scanOf(String source) {
        CachedScan recent = recentScan;
        if (recent != null && recent.source().equals(source)) {
            return recent.scan();
        }
        CachedScan older = olderScan;
        if (older != null && older.source().equals(source)) {
            olderScan = recent;
            recentScan = older;
            return older.scan();
        }
        JpaQueryLiteralScanner.Scan scan = JpaQueryLiteralScanner.scan(source);
        olderScan = recent;
        recentScan = new CachedScan(source, scan);
        return scan;
    }

    private static void addQueryTokens(List<Token> tokens, String source,
                                       JpaQueryLiteralScanner.QueryLiteral query,
                                       String delimiterType) {
        if (query.contentStart() >= query.contentEnd()) {
            tokens.add(token(source, query.start(), query.end(), delimiterType));
            return;
        }
        tokens.add(token(source, query.start(), query.contentStart(), delimiterType));
        tokens.addAll(JpaQueryTokenizer.tokenize(source, query.contentStart(), query.contentEnd(),
                query.nativeSql()));
        if (query.contentEnd() < query.end()) {
            tokens.add(token(source, query.contentEnd(), query.end(), delimiterType));
        }
    }

    private static String classifyWord(String source, int start, int end, String word,
                                       TokenClassifierCodeEditorProvider classifier) {
        if (KEYWORDS.contains(word)) {
            return TokenType.KEYWORD;
        }
        if (isCallSite(source, end)) {
            return TOKEN_METHOD;
        }
        if (looksLikeType(word)) {
            return TOKEN_TYPE;
        }
        if (isDeclaredTypeName(source, start)) {
            return TOKEN_TYPE;
        }
        String classified = classifier == null ? null : classifier.classify(word);
        return classified == null || classified.isBlank() || TokenType.UNKNOWN.equals(classified)
                ? TokenType.IDENTIFIER
                : classified;
    }

    private static boolean isCallSite(String source, int end) {
        int next = skipWhitespace(source, end);
        return next < source.length() && source.charAt(next) == '(';
    }

    private static boolean looksLikeType(String word) {
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
            return false;
        }
        if (IMPLICIT_TYPES.contains(word)) {
            return true;
        }
        return word.chars().anyMatch(Character::isLowerCase);
    }

    private static boolean isDeclaredTypeName(String source, int start) {
        int cursor = start - 1;
        while (cursor >= 0 && isInlineWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        int wordEnd = cursor + 1;
        while (cursor >= 0 && isIdentifierPart(source.charAt(cursor))) {
            cursor--;
        }
        if (wordEnd <= cursor + 1) {
            return false;
        }
        String previous = source.substring(cursor + 1, wordEnd);
        return "class".equals(previous) || "interface".equals(previous)
                || "enum".equals(previous) || "record".equals(previous)
                || "extends".equals(previous) || "implements".equals(previous)
                || "new".equals(previous) || "throws".equals(previous);
    }

    private static int annotationEnd(String source, int at) {
        int i = at + 1;
        while (i < source.length() && (isIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
            i++;
        }
        while (i > at + 1 && source.charAt(i - 1) == '.') {
            i--;
        }
        return i;
    }

    private static int numberEnd(String source, int at) {
        if (source.startsWith("0x", at) || source.startsWith("0X", at)) {
            return radixNumberEnd(source, at, JavaTokenizerProvider::isHexDigit);
        }
        if (source.startsWith("0b", at) || source.startsWith("0B", at)) {
            return radixNumberEnd(source, at, c -> c == '0' || c == '1');
        }
        return decimalNumberEnd(source, at);
    }

    private static int radixNumberEnd(String source, int at, DigitPredicate digit) {
        int i = at + 2;
        while (i < source.length() && (digit.test(source.charAt(i)) || source.charAt(i) == '_')) {
            i++;
        }
        if (i < source.length() && isNumberSuffix(source.charAt(i))) {
            i++;
        }
        return Math.max(i, at + 2);
    }

    private static int decimalNumberEnd(String source, int at) {
        int i = at;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isDigit(c) || c == '_' || c == '.') {
                i++;
            } else if ((c == 'e' || c == 'E') && i + 1 < source.length()
                    && (Character.isDigit(source.charAt(i + 1))
                        || source.charAt(i + 1) == '+' || source.charAt(i + 1) == '-')) {
                i += 2;
            } else {
                if (isNumberSuffix(c)) {
                    i++;
                }
                break;
            }
        }
        return Math.max(i, at + 1);
    }

    @FunctionalInterface
    private interface DigitPredicate {
        boolean test(char c);
    }

    private static int textBlockEnd(String source, int at) {
        int i = at + 3;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (source.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return source.length();
    }

    private static int stringEnd(String source, int at, char quote) {
        int i = at + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int lineEnd(String value, int at) {
        int end = value.indexOf('\n', at);
        return end < 0 ? value.length() : end;
    }

    private static int skipWhitespace(String source, int at) {
        int i = at;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isInlineWhitespace(char c) {
        return Character.isWhitespace(c) && c != '\r' && c != '\n';
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isNumberSuffix(char c) {
        return c == 'L' || c == 'l' || c == 'f' || c == 'F' || c == 'd' || c == 'D';
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isJavaIdentifierPart(c);
    }

    private static Token token(String source, int start, int end, String type) {
        return new Token(start, end, type, source.substring(start, end));
    }
}
