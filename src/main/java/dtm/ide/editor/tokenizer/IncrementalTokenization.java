package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class IncrementalTokenization {

    private IncrementalTokenization() {
    }

    static Collection<Token> retokenizeFromSafeLine(
            TokenizeChange change,
            TokenClassifierCodeEditorProvider classifier,
            FullTokenizer tokenizer) {
        if (change == null) {
            return tokenizer.tokenize("", classifier);
        }

        String oldText = change.oldText();
        String newText = change.newText();
        if (!isConsistent(change, oldText, newText)
                || !tokensCover(change.previousTokens(), oldText)) {
            return tokenizer.tokenize(newText == null ? "" : newText, classifier);
        }

        List<Token> previous = List.copyOf(change.previousTokens());
        int restart = lineStart(oldText, change.changeOffset());
        restart = containingTokenStart(previous, restart);
        if (restart == 0) {
            return tokenizer.tokenize(newText, classifier);
        }

        List<Token> result = new ArrayList<>(previous.size() + 8);
        for (Token token : previous) {
            if (token.getEndOffset() <= restart) {
                result.add(token);
            } else {
                break;
            }
        }

        Collection<Token> suffix = tokenizer.tokenize(newText.substring(restart), classifier);
        if (suffix != null) {
            for (Token token : suffix) {
                int start = restart + token.getStartOffset();
                int end = restart + token.getEndOffset();
                result.add(new Token(start, end, token.getType(), newText.substring(start, end)));
            }
        }
        return result;
    }

    private static boolean isConsistent(TokenizeChange change, String oldText, String newText) {
        if (oldText == null || newText == null) {
            return false;
        }
        int offset = change.changeOffset();
        int removedLength = change.removedLength();
        if (offset < 0 || removedLength < 0 || offset > oldText.length()
                || offset + removedLength > oldText.length()) {
            return false;
        }
        String inserted = change.insertedText() == null ? "" : change.insertedText();
        String rebuilt = oldText.substring(0, offset) + inserted
                + oldText.substring(offset + removedLength);
        return rebuilt.equals(newText);
    }

    private static boolean tokensCover(Collection<Token> tokens, String text) {
        if (tokens == null || text == null) {
            return false;
        }
        int cursor = 0;
        for (Token token : tokens) {
            if (token == null || token.getStartOffset() != cursor
                    || token.getEndOffset() <= cursor || token.getEndOffset() > text.length()) {
                return false;
            }
            cursor = token.getEndOffset();
        }
        return cursor == text.length();
    }

    private static int lineStart(String text, int offset) {
        int from = Math.min(offset, text.length()) - 1;
        for (int i = from; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i + 1;
            }
        }
        return 0;
    }

    private static int containingTokenStart(List<Token> tokens, int offset) {
        for (Token token : tokens) {
            if (token.getStartOffset() < offset && token.getEndOffset() > offset) {
                return token.getStartOffset();
            }
            if (token.getStartOffset() >= offset) {
                break;
            }
        }
        return offset;
    }

    @FunctionalInterface
    interface FullTokenizer {
        Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier);
    }
}
