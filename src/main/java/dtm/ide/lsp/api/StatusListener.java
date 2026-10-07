package dtm.ide.lsp.api;

public interface StatusListener {
    void onStatus(String message, int percent);
}
