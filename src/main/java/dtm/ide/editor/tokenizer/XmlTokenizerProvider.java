package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class XmlTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_TAG = "XML_TAG";
    public static final String TOKEN_ATTRIBUTE = "XML_ATTRIBUTE";

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

        int cursor = 0;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (current == '<') {
                cursor = source.startsWith("<!--", cursor)
                        ? emitComment(source, cursor, tokens)
                        : emitTag(source, cursor, tokens);
            } else {
                cursor = emitText(source, cursor, tokens);
            }
        }
        return tokens;
    }

    private static int emitComment(String source, int start, List<Token> tokens) {
        int end = source.indexOf("-->", start + 4);
        end = end < 0 ? source.length() : end + 3;
        add(tokens, source, start, end, TokenType.COMMENT);
        return end;
    }

    private static int emitTag(String source, int start, List<Token> tokens) {
        if (source.startsWith("<![CDATA[", start)) {
            int cdataEnd = source.indexOf("]]>", start);
            cdataEnd = cdataEnd < 0 ? source.length() : cdataEnd + 3;
            add(tokens, source, start, cdataEnd, TokenType.STRING);
            return cdataEnd;
        }
        int end = source.indexOf('>', start);
        end = end < 0 ? source.length() : end + 1;

        int cursor = start + 1;
        add(tokens, source, start, cursor, TokenType.SYMBOL);
        cursor = emitTagName(source, cursor, end, tokens);
        cursor = emitAttributes(source, cursor, end, tokens);
        if (cursor < end) {
            add(tokens, source, cursor, end, TokenType.SYMBOL);
        }
        return end;
    }

    private static int emitTagName(String source, int cursor, int end, List<Token> tokens) {
        int nameStart = cursor;
        while (nameStart < end && isTagNamePrefix(source.charAt(nameStart))) {
            nameStart++;
        }
        if (nameStart > cursor) {
            add(tokens, source, cursor, nameStart, TokenType.SYMBOL);
        }
        int nameEnd = nameStart;
        while (nameEnd < end && isNamePart(source.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd > nameStart) {
            add(tokens, source, nameStart, nameEnd, TOKEN_TAG);
        }
        return nameEnd;
    }

    private static int emitAttributes(String source, int cursor, int end, List<Token> tokens) {
        while (cursor < end) {
            char current = source.charAt(cursor);
            if (Character.isWhitespace(current)) {
                int whitespaceEnd = cursor;
                while (whitespaceEnd < end && Character.isWhitespace(source.charAt(whitespaceEnd))) {
                    whitespaceEnd++;
                }
                add(tokens, source, cursor, whitespaceEnd, TokenType.WHITESPACE);
                cursor = whitespaceEnd;
            } else if (current == '"' || current == '\'') {
                int valueEnd = source.indexOf(current, cursor + 1);
                valueEnd = valueEnd < 0 || valueEnd >= end ? end : valueEnd + 1;
                add(tokens, source, cursor, valueEnd, TokenType.STRING);
                cursor = valueEnd;
            } else if (isNamePart(current)) {
                int nameEnd = cursor;
                while (nameEnd < end && isNamePart(source.charAt(nameEnd))) {
                    nameEnd++;
                }
                add(tokens, source, cursor, nameEnd, TOKEN_ATTRIBUTE);
                cursor = nameEnd;
            } else if (current == '=') {
                add(tokens, source, cursor, cursor + 1, TokenType.SYMBOL);
                cursor++;
            } else {
                break;
            }
        }
        return cursor;
    }

    private static int emitText(String source, int start, List<Token> tokens) {
        int end = source.indexOf('<', start);
        end = end < 0 ? source.length() : end;
        int cursor = start;
        while (cursor < end) {
            char current = source.charAt(cursor);
            int runEnd = cursor;
            boolean whitespace = Character.isWhitespace(current);
            while (runEnd < end && Character.isWhitespace(source.charAt(runEnd)) == whitespace) {
                runEnd++;
            }
            add(tokens, source, cursor, runEnd,
                    whitespace ? TokenType.WHITESPACE : TokenType.IDENTIFIER);
            cursor = runEnd;
        }
        return Math.max(end, start + (end == start ? 1 : 0));
    }

    private static boolean isTagNamePrefix(char c) {
        return c == '/' || c == '?' || c == '!';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
    }

    private static void add(List<Token> tokens, String source, int start, int end, String type) {
        if (end > start) {
            tokens.add(new Token(start, end, type, source.substring(start, end)));
        }
    }
}
