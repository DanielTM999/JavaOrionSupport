package dtm.ide.settings;

public enum InlayHintsMode {
    NONE("none", "Desligadas"),
    LITERALS("literals", "Nomes de parametros para literais"),
    ALL("all", "Todos os parametros e tipos inferidos");

    private final String key;
    private final String label;

    InlayHintsMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public static InlayHintsMode fromKey(String value) {
        for (InlayHintsMode mode : values()) {
            if (mode.key.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return LITERALS;
    }

    @Override
    public String toString() {
        return label;
    }
}
