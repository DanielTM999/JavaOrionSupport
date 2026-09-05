package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfigTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_KEY = "CONFIG_KEY";
    public static final String TOKEN_PLACEHOLDER = "CONFIG_PLACEHOLDER";
    public static final String TOKEN_DOCUMENT_MARKER = "CONFIG_DOCUMENT_MARKER";

    public enum Mode {
        PROPERTIES,
        YAML
    }

    private static final Set<String> LITERALS = Set.of(
            "true", "false", "null", "yes", "no", "on", "off", "~");

    private final Mode mode;

    public ConfigTokenizerProvider(Mode mode) {
        this.mode = mode == null ? Mode.PROPERTIES : mode;
    }

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public Collection<Token> tokenize(TokenizeChange change,
                                      TokenClassifierCodeEditorProvider classifier) {
        return IncrementalTokenization.retokenizeFromSafeLine(change, classifier, this::tokenize);
    }

    @Override
    public Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String source = text == null ? "" : text;
        List<Token> tokens = new ArrayList<>(Math.max(16, source.length() / 8));

        int i = 0;
        while (i < source.length()) {
            int lineEnd = lineEnd(source, i);
            emitLine(source, i, lineEnd, tokens);
            i = lineEnd;
            i = emitLineBreak(source, i, tokens);
        }
        return tokens;
    }

    private void emitLine(String source, int from, int to, List<Token> tokens) {
        int cursor = emitIndent(source, from, to, tokens);
        if (cursor >= to) {
            return;
        }

        char first = source.charAt(cursor);
        if (first == '#' || (mode == Mode.PROPERTIES && first == '!')) {
            tokens.add(token(source, cursor, to, TokenType.COMMENT));
            return;
        }
        if (mode == Mode.YAML && source.startsWith("---", cursor)) {
            tokens.add(token(source, cursor, to, TOKEN_DOCUMENT_MARKER));
            return;
        }
        if (mode == Mode.YAML && first == '-') {
            tokens.add(token(source, cursor, cursor + 1, TokenType.SYMBOL));
            cursor = emitIndent(source, cursor + 1, to, tokens);
            if (cursor >= to) {
                return;
            }
        }

        int separator = findSeparator(source, cursor, to);
        if (separator < 0) {
            emitValue(source, cursor, to, tokens);
            return;
        }
        tokens.add(token(source, cursor, separator, TOKEN_KEY));
        tokens.add(token(source, separator, separator + 1, TokenType.SYMBOL));

        int valueStart = emitIndent(source, separator + 1, to, tokens);
        if (valueStart < to) {
            emitValue(source, valueStart, to, tokens);
        }
    }

    private int findSeparator(String source, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = source.charAt(i);
            if (mode == Mode.PROPERTIES) {
                if (c == '=' || c == ':') {
                    return i;
                }
            } else if (c == ':' && (i + 1 >= to || source.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static void emitValue(String source, int from, int to, List<Token> tokens) {
        int i = from;
        int plainStart = from;
        while (i < to) {
            if (!source.startsWith("${", i)) {
                i++;
                continue;
            }
            if (plainStart < i) {
                tokens.add(classifyPlain(source, plainStart, i));
            }
            int close = source.indexOf('}', i + 2);
            int end = close < 0 || close >= to ? to : close + 1;
            tokens.add(token(source, i, end, TOKEN_PLACEHOLDER));
            i = end;
            plainStart = end;
        }
        if (plainStart < to) {
            tokens.add(classifyPlain(source, plainStart, to));
        }
    }

    private static Token classifyPlain(String source, int from, int to) {
        String value = source.substring(from, to).trim();
        if (LITERALS.contains(value.toLowerCase(Locale.ROOT))) {
            return token(source, from, to, TokenType.KEYWORD);
        }
        if (isNumeric(value)) {
            return token(source, from, to, TokenType.NUMBER);
        }
        return token(source, from, to, TokenType.STRING);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        boolean digitSeen = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digitSeen = true;
            } else if (c != '.' && c != '-' && c != '+') {
                return false;
            }
        }
        return digitSeen;
    }

    private static int emitIndent(String source, int from, int to, List<Token> tokens) {
        int i = from;
        while (i < to && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
            i++;
        }
        if (i > from) {
            tokens.add(token(source, from, i, TokenType.WHITESPACE));
        }
        return i;
    }

    private static int emitLineBreak(String source, int at, List<Token> tokens) {
        if (at >= source.length()) {
            return at;
        }
        int i = at;
        char c = source.charAt(i++);
        if (c == '\r' && i < source.length() && source.charAt(i) == '\n') {
            i++;
        }
        tokens.add(token(source, at, i, TokenType.NEWLINE));
        return i;
    }

    private static int lineEnd(String source, int at) {
        int i = at;
        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static Token token(String source, int start, int end, String type) {
        return new Token(start, end, type, source.substring(start, end));
    }
}
