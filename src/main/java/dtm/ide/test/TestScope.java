package dtm.ide.test;

import java.util.Arrays;
import java.util.Locale;

public enum TestScope {

    ALL,
    PACKAGE,
    CLASS,
    METHOD,
    PATTERN;

    public boolean requiresTarget() {
        return this != ALL;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TestScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.name().equals(value))
                .findFirst()
                .orElse(ALL);
    }
}
