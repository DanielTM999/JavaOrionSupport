package dtm.ide.lsp.api;

import dtm.stools.component.panels.editor.code.api.Range;

public record PrepareRenameResult(boolean renameable, Range range, String placeholder, String message) {

    public static PrepareRenameResult rejected(String message) {
        return new PrepareRenameResult(false, null, null, message);
    }

    public static PrepareRenameResult of(Range range, String placeholder) {
        return new PrepareRenameResult(true, range, placeholder, null);
    }
}
