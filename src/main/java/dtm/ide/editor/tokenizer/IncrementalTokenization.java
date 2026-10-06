package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class IncrementalTokenization {

    private IncrementalTokenization() {
    }

    private static final int INITIAL_WINDOW_LINES = 8;
    private static final int WINDOW_GROWTH = 4;
    private static final int SYNC_TAIL_LINES = 2;

    static Collection<Token> retokenizeFromSafeLine(
            TokenizeChange change,
            TokenClassifierCodeEditorProvider classifier,
            FullTokenizer tokenizer) {
        return retokenizeFromSafeLine(change, classifier, tokenizer, (text, from, to) -> true);
    }

    static Collection<Token> retokenizeFromSafeLine(
            TokenizeChange change,
            TokenClassifierCodeEditorProvider classifier,
            FullTokenizer tokenizer,
            ResyncGuard guard) {
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

        int delta = newText.length() - oldText.length();
        int changeEnd = change.changeOffset() + insertedLength(change);
        for (int lines = INITIAL_WINDOW_LINES; ; lines *= WINDOW_GROWTH) {
            int windowEnd = lineEndAfter(newText, changeEnd, lines);
            if (windowEnd >= newText.length()
                    || !guard.allowsResync(newText, restart, windowEnd)
                    || !guard.allowsResync(oldText, restart, Math.max(restart, windowEnd - delta))) {
                appendTokens(result, tokenizer.tokenize(newText.substring(restart), classifier),
                        restart, newText);
                return result;
            }
            List<Token> window = List.copyOf(tokenizer.tokenize(
                    newText.substring(restart, windowEnd), classifier));
            int sync = resyncPoint(window, restart, windowEnd, changeEnd, delta, previous,
                    oldText, newText);
            if (sync >= 0) {
                int oldIndex = indexStartingAt(previous, restart + window.get(sync).getStartOffset() - delta);
                appendTokens(result, window.subList(0, sync), restart, newText);
                for (int i = oldIndex; i < previous.size(); i++) {
                    Token token = previous.get(i);
                    result.add(delta == 0 ? token : new Token(token.getStartOffset() + delta,
                            token.getEndOffset() + delta, token.getType(), token.getText()));
                }
                return result;
            }
        }
    }

    private static int resyncPoint(List<Token> window, int restart, int windowEnd, int changeEnd,
                                   int delta, List<Token> previous, String oldText, String newText) {
        for (int i = 0; i < window.size(); i++) {
            Token token = window.get(i);
            int start = restart + token.getStartOffset();
            if (start <= changeEnd || !startsLine(newText, start)
                    || !startsLine(oldText, start - delta)) {
                continue;
            }
            if (lineEndAfter(newText, start, SYNC_TAIL_LINES) >= windowEnd) {
                return -1;
            }
            int oldIndex = indexStartingAt(previous, start - delta);
            if (oldIndex < 0) {
                continue;
            }
            Token old = previous.get(oldIndex);
            int length = token.getEndOffset() - token.getStartOffset();
            if (old.getEndOffset() - old.getStartOffset() == length
                    && Objects.equals(old.getType(), token.getType())) {
                return i;
            }
        }
        return -1;
    }

    private static void appendTokens(List<Token> result, Collection<Token> tokens, int restart,
                                     String newText) {
        if (tokens == null) {
            return;
        }
        for (Token token : tokens) {
            int start = restart + token.getStartOffset();
            int end = restart + token.getEndOffset();
            result.add(new Token(start, end, token.getType(), newText.substring(start, end)));
        }
    }

    private static int indexStartingAt(List<Token> tokens, int offset) {
        int low = 0;
        int high = tokens.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int start = tokens.get(mid).getStartOffset();
            if (start == offset) {
                return mid;
            }
            if (start < offset) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -1;
    }

    private static boolean startsLine(String text, int offset) {
        if (offset <= 0) {
            return offset == 0;
        }
        char before = text.charAt(offset - 1);
        return before == '\n' || (before == '\r' && (offset >= text.length() || text.charAt(offset) != '\n'));
    }

    private static int lineEndAfter(String text, int from, int lines) {
        int index = Math.max(0, from);
        for (int line = 0; line < lines; line++) {
            int next = text.indexOf('\n', index);
            if (next < 0) {
                return text.length();
            }
            index = next + 1;
        }
        return index;
    }

    private static int insertedLength(TokenizeChange change) {
        return change.insertedText() == null ? 0 : change.insertedText().length();
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
        int suffix = oldText.length() - offset - removedLength;
        return newText.length() == offset + inserted.length() + suffix
                && oldText.regionMatches(0, newText, 0, offset)
                && newText.startsWith(inserted, offset)
                && oldText.regionMatches(offset + removedLength, newText, offset + inserted.length(), suffix);
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
    interface ResyncGuard {
        boolean allowsResync(String text, int from, int to);
    }

    @FunctionalInterface
    interface FullTokenizer {
        Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier);
    }
}
