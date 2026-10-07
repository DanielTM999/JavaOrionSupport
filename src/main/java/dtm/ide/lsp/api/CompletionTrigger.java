package dtm.ide.lsp.api;

public enum CompletionTrigger {
    INVOKED(1),
    TRIGGER_CHARACTER(2),
    INCOMPLETE(3);

    private final int lspKind;

    CompletionTrigger(int lspKind) {
        this.lspKind = lspKind;
    }

    public int lspKind() {
        return lspKind;
    }
}
