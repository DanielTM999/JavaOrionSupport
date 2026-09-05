package dtm.ide.settings;

public enum HotReloadMode {
    MANUAL("manual", "Manual"),
    AUTOMATIC("automatic", "Automatic after save"),
    NEVER("never", "Disabled");

    private final String key;
    private final String label;

    HotReloadMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public static HotReloadMode fromKey(String value) {
        for (HotReloadMode mode : values()) {
            if (mode.key.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return MANUAL;
    }

    @Override
    public String toString() {
        return label;
    }
}
