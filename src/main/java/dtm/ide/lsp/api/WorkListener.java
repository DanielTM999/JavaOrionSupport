package dtm.ide.lsp.api;

public interface WorkListener {
    void onWork(String message, int percent, boolean active);
}
