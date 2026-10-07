package dtm.ide.lsp.api;

import java.nio.file.Path;

public interface LateCompletionListener {
    void onLateCompletion(Path filePath, int line, int col);
}
