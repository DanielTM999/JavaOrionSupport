package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TextEditApplier {

    private TextEditApplier() {
    }

    static String apply(String text, List<TextEdit> edits) {
        if (text == null || edits == null || edits.isEmpty()) {
            return text;
        }
        int[] lineOffsets = lineOffsets(text);
        List<Span> spans = resolve(text, edits, lineOffsets);

        StringBuilder result = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            Span span = spans.get(i);
            result.replace(span.start(), span.end(), span.newText());
        }
        return result.toString();
    }

    private static List<Span> resolve(String text, List<TextEdit> edits, int[] lineOffsets) {
        List<Span> spans = new ArrayList<>(edits.size());
        boolean crlf = text.contains("\r\n");
        for (TextEdit edit : edits) {
            if (edit == null || edit.range() == null) {
                continue;
            }
            int start = offsetOf(edit.range().start(), lineOffsets, text.length());
            int end = offsetOf(edit.range().end(), lineOffsets, text.length());
            if (start <= end) {
                String newText = LspConversions.normalizeLineBreaks(edit.newText() == null ? "" : edit.newText());
                if (crlf) {
                    newText = newText.replace("\n", "\r\n");
                }
                spans.add(new Span(start, end, newText));
            }
        }
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));

        List<Span> accepted = new ArrayList<>(spans.size());
        int lastEnd = -1;
        for (Span span : spans) {
            if (span.start() >= lastEnd) {
                accepted.add(span);
                lastEnd = span.end();
            }
        }
        return accepted;
    }

    private record Span(int start, int end, String newText) {
    }

    private static int[] lineOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        int[] result = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            result[i] = offsets.get(i);
        }
        return result;
    }

    private static int offsetOf(Position position, int[] lineOffsets, int textLength) {
        if (position == null) {
            return 0;
        }
        int line = position.line();
        if (line < 0) {
            return 0;
        }
        if (line >= lineOffsets.length) {
            return textLength;
        }
        int lineEnd = line + 1 < lineOffsets.length ? lineOffsets[line + 1] - 1 : textLength;
        return Math.min(lineEnd, lineOffsets[line] + Math.max(0, position.col()));
    }

    static String apply(String text, TextEdit edit) {
        return apply(text, List.of(edit));
    }
}
