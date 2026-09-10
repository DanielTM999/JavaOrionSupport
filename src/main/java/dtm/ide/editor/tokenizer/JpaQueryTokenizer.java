package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class JpaQueryTokenizer {

    public static final String TOKEN_KEYWORD = "JPA_QUERY_KEYWORD";
    public static final String TOKEN_ENTITY = "JPA_QUERY_ENTITY";
    public static final String TOKEN_ALIAS = "JPA_QUERY_ALIAS";
    public static final String TOKEN_PROPERTY = "JPA_QUERY_PROPERTY";
    public static final String TOKEN_FUNCTION = "JPA_QUERY_FUNCTION";
    public static final String TOKEN_PARAMETER = "JPA_QUERY_PARAMETER";

    private static final String IDENTIFIER = "JPA_QUERY_IDENTIFIER";

    private static final Set<String> JPQL_KEYWORDS = Set.of(
            "all", "and", "any", "as", "asc", "between", "both", "by", "case",
            "delete", "desc", "distinct", "else", "empty", "end", "escape", "exists",
            "fetch", "from", "group", "having", "in", "inner", "is", "join", "left",
            "like", "member", "new", "not", "null", "on", "or", "order", "outer",
            "select", "set", "some", "then", "true", "false", "update", "when", "where");

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "all", "and", "any", "as", "asc", "between", "both", "by", "case", "cross",
            "delete", "desc", "distinct", "distinctrow", "else", "end", "exists", "first",
            "from", "full", "group", "having", "in", "inner", "insert", "into", "is",
            "join", "last", "left", "like", "limit", "not", "null", "nulls", "offset",
            "on", "only", "or", "order", "outer", "over", "partition", "range", "returning",
            "right", "row", "rows", "select", "set", "then", "top", "true", "false",
            "union", "update", "values", "when", "where", "with");

    private JpaQueryTokenizer() {
    }

    public static List<Token> tokenize(String source, int from, int to, boolean nativeSql) {
        if (source == null || from >= to) {
            return List.of();
        }
        int start = Math.max(0, from);
        int limit = Math.max(start, Math.min(to, source.length()));
        List<Span> spans = lex(source, start, limit, nativeSql);
        return classify(source, spans, nativeSql);
    }

    private static List<Span> lex(String source, int from, int to, boolean nativeSql) {
        List<Span> spans = new ArrayList<>();
        int cursor = from;
        while (cursor < to) {
            char c = source.charAt(cursor);
            int end;
            String type;
            if (c == '\r' || c == '\n') {
                end = cursor + 1;
                if (c == '\r' && end < to && source.charAt(end) == '\n') {
                    end++;
                }
                type = TokenType.NEWLINE;
            } else if (Character.isWhitespace(c)) {
                end = cursor + 1;
                while (end < to && Character.isWhitespace(source.charAt(end))
                        && source.charAt(end) != '\r' && source.charAt(end) != '\n') {
                    end++;
                }
                type = TokenType.WHITESPACE;
            } else if (source.startsWith("--", cursor)) {
                end = source.indexOf('\n', cursor + 2);
                end = end < 0 || end > to ? to : end;
                type = TokenType.COMMENT;
            } else if (source.startsWith("/*", cursor)) {
                int close = source.indexOf("*/", cursor + 2);
                end = close < 0 || close + 2 > to ? to : close + 2;
                type = TokenType.COMMENT;
            } else if (c == '\'') {
                end = quotedEnd(source, cursor, to, '\'');
                type = TokenType.STRING;
            } else if (c == ':' && cursor + 1 < to
                    && isIdentifierStart(source.charAt(cursor + 1))) {
                end = identifierEnd(source, cursor + 1, to);
                type = TOKEN_PARAMETER;
            } else if (c == '?' && cursor + 1 < to
                    && Character.isDigit(source.charAt(cursor + 1))) {
                end = cursor + 2;
                while (end < to && Character.isDigit(source.charAt(end))) {
                    end++;
                }
                type = TOKEN_PARAMETER;
            } else if (Character.isDigit(c)) {
                end = numberEnd(source, cursor, to);
                type = TokenType.NUMBER;
            } else if (isIdentifierStart(c)) {
                end = identifierEnd(source, cursor, to);
                String word = source.substring(cursor, end).toLowerCase(Locale.ROOT);
                Set<String> keywords = nativeSql ? SQL_KEYWORDS : JPQL_KEYWORDS;
                type = keywords.contains(word) ? TOKEN_KEYWORD
                        : nextNonWhitespace(source, end, to) == '(' ? TOKEN_FUNCTION : IDENTIFIER;
            } else {
                end = cursor + 1;
                type = TokenType.SYMBOL;
            }
            spans.add(new Span(cursor, end, type));
            cursor = end;
        }
        return spans;
    }

    private static List<Token> classify(String source, List<Span> spans, boolean nativeSql) {
        Set<Integer> entities = new HashSet<>();
        Set<Integer> aliasDeclarations = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        List<Integer> significant = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            String type = spans.get(i).type();
            if (!TokenType.WHITESPACE.equals(type) && !TokenType.NEWLINE.equals(type)
                    && !TokenType.COMMENT.equals(type)) {
                significant.add(i);
            }
        }

        for (int position = 0; position < significant.size(); position++) {
            int index = significant.get(position);
            Span span = spans.get(index);
            if (!TOKEN_KEYWORD.equals(span.type())) {
                continue;
            }
            String keyword = text(source, span).toLowerCase(Locale.ROOT);
            boolean join = "join".equals(keyword);
            if (!join && !"from".equals(keyword) && !"update".equals(keyword)
                    && !"into".equals(keyword)) {
                continue;
            }
            int sourcePosition = position + 1;
            if (join && sourcePosition < significant.size()
                    && isKeyword(source, spans.get(significant.get(sourcePosition)), "fetch")) {
                sourcePosition++;
            }
            if (sourcePosition >= significant.size()) {
                continue;
            }
            int sourceIndex = significant.get(sourcePosition);
            if (!isIdentifier(spans.get(sourceIndex))) {
                continue;
            }
            if (!join || nativeSql) {
                entities.add(sourceIndex);
            }
            int afterSource = sourcePosition + 1;
            while (afterSource + 1 < significant.size()
                    && isSymbol(source, spans.get(significant.get(afterSource)), ".")
                    && isIdentifier(spans.get(significant.get(afterSource + 1)))) {
                if (!join || nativeSql) {
                    entities.add(significant.get(afterSource + 1));
                }
                afterSource += 2;
            }
            if (afterSource < significant.size()
                    && isKeyword(source, spans.get(significant.get(afterSource)), "as")) {
                afterSource++;
            }
            if (afterSource < significant.size()) {
                int aliasIndex = significant.get(afterSource);
                if (isIdentifier(spans.get(aliasIndex))) {
                    aliasDeclarations.add(aliasIndex);
                    aliases.add(text(source, spans.get(aliasIndex)));
                }
            }
        }

        List<Token> tokens = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            String type = span.type();
            if (IDENTIFIER.equals(type)) {
                String word = text(source, span);
                if (aliasDeclarations.contains(i) || aliases.contains(word)) {
                    type = TOKEN_ALIAS;
                } else if (entities.contains(i)) {
                    type = TOKEN_ENTITY;
                } else {
                    type = TOKEN_PROPERTY;
                }
            }
            tokens.add(new Token(span.start(), span.end(), type, text(source, span)));
        }
        return List.copyOf(tokens);
    }

    private static boolean isIdentifier(Span span) {
        return IDENTIFIER.equals(span.type()) || TOKEN_FUNCTION.equals(span.type());
    }

    private static boolean isKeyword(String source, Span span, String keyword) {
        return TOKEN_KEYWORD.equals(span.type()) && keyword.equalsIgnoreCase(text(source, span));
    }

    private static boolean isSymbol(String source, Span span, String symbol) {
        return TokenType.SYMBOL.equals(span.type()) && symbol.equals(text(source, span));
    }

    private static char nextNonWhitespace(String source, int from, int to) {
        int cursor = from;
        while (cursor < to && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor < to ? source.charAt(cursor) : '\0';
    }

    private static int quotedEnd(String source, int at, int to, char quote) {
        int cursor = at + 1;
        while (cursor < to) {
            char c = source.charAt(cursor);
            if (c == quote && cursor + 1 < to && source.charAt(cursor + 1) == quote) {
                cursor += 2;
            } else if (c == '\\' && cursor + 1 < to) {
                cursor += 2;
            } else if (c == quote) {
                return cursor + 1;
            } else {
                cursor++;
            }
        }
        return cursor;
    }

    private static int numberEnd(String source, int at, int to) {
        int cursor = at + 1;
        while (cursor < to) {
            char c = source.charAt(cursor);
            if (Character.isDigit(c) || c == '.' || c == '_') {
                cursor++;
            } else {
                break;
            }
        }
        return cursor;
    }

    private static int identifierEnd(String source, int at, int to) {
        int cursor = at + 1;
        while (cursor < to && Character.isJavaIdentifierPart(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isJavaIdentifierStart(c);
    }

    private static String text(String source, Span span) {
        return source.substring(span.start(), span.end());
    }

    private record Span(int start, int end, String type) {
    }
}
