package dtm.ide.settings;

import java.util.Locale;

public enum LanguageServerMode {

    AUTO("auto"),

    JDTLS("jdtls"),

    SNIPPETS_ONLY("snippets");

    private final String key;

    LanguageServerMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean startsServer() {
        return this != SNIPPETS_ONLY;
    }

    public static LanguageServerMode fromKey(String key) {
        if (key == null || key.isBlank()) {
            return AUTO;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (LanguageServerMode mode : values()) {
            if (mode.key.equals(normalized)) {
                return mode;
            }
        }
        return AUTO;
    }
}
