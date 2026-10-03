package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;

import java.util.ArrayList;
import java.util.List;

/** Converts between character offsets and the line/column positions used by LSP and the editor. */
public final class TextOffsets {

    private TextOffsets() {
    }

    public static Position position(String text, int offset) {
        String source = text == null ? "" : text;
        int bounded = Math.max(0, Math.min(offset, source.length()));
        int line = 0;
        int lineStart = 0;
        for (int index = source.indexOf('\n'); index >= 0 && index < bounded;
             index = source.indexOf('\n', index + 1)) {
            line++;
            lineStart = index + 1;
        }
        return new Position(line, bounded - lineStart);
    }

    /** Offsets {@code [start, end)} for each range, clamped to the text. */
    public static List<int[]> offsets(String text, List<Range> ranges) {
        String source = text == null ? "" : text;
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int index = source.indexOf('\n'); index >= 0; index = source.indexOf('\n', index + 1)) {
            lineStarts.add(index + 1);
        }
        List<int[]> result = new ArrayList<>(ranges == null ? 0 : ranges.size());
        if (ranges != null) {
            for (Range range : ranges) {
                result.add(new int[]{offset(source, lineStarts, range.start()),
                        offset(source, lineStarts, range.end())});
            }
        }
        return List.copyOf(result);
    }

    private static int offset(String source, List<Integer> lineStarts, Position position) {
        if (position.line() >= lineStarts.size()) {
            return source.length();
        }
        int lineStart = lineStarts.get(Math.max(0, position.line()));
        return Math.max(0, Math.min(source.length(), lineStart + position.col()));
    }
}
