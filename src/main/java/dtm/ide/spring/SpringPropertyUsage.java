package dtm.ide.spring;

import java.nio.file.Path;

public record SpringPropertyUsage(
        String key,
        String defaultValue,
        String ownerType,
        Kind kind,
        Path file,
        int line
) {

    public enum Kind {
        VALUE,
        CONFIGURATION_PROPERTIES
    }

    public SpringPropertyUsage {
        key = key == null ? "" : key.trim();
        defaultValue = defaultValue == null ? "" : defaultValue;
        ownerType = ownerType == null ? "" : ownerType.trim();
        kind = kind == null ? Kind.VALUE : kind;
        line = Math.max(1, line);
    }

    public boolean hasDefault() {
        return !defaultValue.isBlank();
    }

    public boolean isPrefix() {
        return kind == Kind.CONFIGURATION_PROPERTIES;
    }
}
