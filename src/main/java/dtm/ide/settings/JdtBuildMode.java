package dtm.ide.settings;

public enum JdtBuildMode {
    PROJECT_BUILD("projectBuild", "Ao compilar o projeto"),
    AUTOBUILD_ISOLATED("autobuildIsolated", "Continuamente, em pasta separada");

    private final String key;
    private final String label;

    JdtBuildMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public boolean isAutobuild() {
        return this == AUTOBUILD_ISOLATED;
    }

    public static JdtBuildMode fromKey(String value) {
        for (JdtBuildMode mode : values()) {
            if (mode.key.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return PROJECT_BUILD;
    }

    @Override
    public String toString() {
        return label;
    }
}
