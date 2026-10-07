package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.Position;

final class LspText {

    private LspText() {
    }

    static int offsetIn(String text, Position position) {
        int line = 0;
        int index = 0;
        while (line < position.line()) {
            int next = text.indexOf('\n', index);
            if (next < 0) {
                return -1;
            }
            index = next + 1;
            line++;
        }
        int lineEnd = text.indexOf('\n', index);
        int limit = lineEnd < 0 ? text.length() : lineEnd;
        long offset = (long) index + position.col();
        return position.col() < 0 || offset > limit ? -1 : (int) offset;
    }
}
