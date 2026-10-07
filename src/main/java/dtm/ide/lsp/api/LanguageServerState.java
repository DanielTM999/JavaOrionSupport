package dtm.ide.lsp.api;

public enum LanguageServerState {
    NOT_STARTED,
    STARTING,
    INDEXING,
    READY,
    STOPPED,
    ERROR
}
