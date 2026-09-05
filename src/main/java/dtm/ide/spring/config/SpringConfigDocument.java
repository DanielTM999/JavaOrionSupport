package dtm.ide.spring.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SpringConfigDocument {

    public record ConfigKey(String key, String value, int line, int keyStart, int keyEnd) {

        public boolean hasValue() {
            return !value.isBlank();
        }
    }

    public enum Format {
        PROPERTIES,
        YAML;

        public static Format of(String fileName) {
            if (fileName == null) {
                return PROPERTIES;
            }
            String lower = fileName.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".yml") || lower.endsWith(".yaml") ? YAML : PROPERTIES;
        }
    }

    private SpringConfigDocument() {
    }

    public static List<ConfigKey> keys(String text, Format format) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return format == Format.YAML ? yamlKeys(text) : propertiesKeys(text);
    }

    public static String keyAt(String text, int line, Format format) {
        return keys(text, format).stream()
                .filter(key -> key.line() == line)
                .map(ConfigKey::key)
                .findFirst()
                .orElse("");
    }

    public static Optional<String> keyPrefixAt(String text, int line, int column, Format format) {
        if (text == null || text.isBlank()) {
            return Optional.of("");
        }
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length) {
            return Optional.empty();
        }
        String current = lines[line];
        String typed = current.substring(0, Math.min(Math.max(0, column), current.length()));
        String bare = typed.trim();

        int separator = format == Format.YAML ? bare.indexOf(':') : indexOfPropertiesSeparator(bare);
        if (separator >= 0) {
            return Optional.empty();
        }
        if (format == Format.PROPERTIES) {
            return Optional.of(bare);
        }
        String parent = yamlParentPath(lines, line, indentOf(current));
        return Optional.of(parent.isBlank() ? bare : parent + "." + bare);
    }

    private static List<ConfigKey> propertiesKeys(String text) {
        List<ConfigKey> keys = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        for (int line = 0; line < lines.length; line++) {
            String raw = lines[line];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = indexOfPropertiesSeparator(raw);
            if (separator < 0) {
                continue;
            }
            int keyStart = indentOf(raw);
            String key = raw.substring(keyStart, separator).trim();
            if (key.isEmpty()) {
                continue;
            }
            keys.add(new ConfigKey(key, raw.substring(separator + 1).trim(), line,
                    keyStart, keyStart + key.length()));
        }
        return keys;
    }

    private static int indexOfPropertiesSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static List<ConfigKey> yamlKeys(String text) {
        List<ConfigKey> keys = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        for (int line = 0; line < lines.length; line++) {
            String raw = lines[line];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("---")) {
                path.clear();
                indents.clear();
                continue;
            }
            if (trimmed.startsWith("-")) {
                continue;
            }
            int separator = yamlSeparator(trimmed);
            if (separator < 0) {
                continue;
            }
            int indent = indentOf(raw);
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                path.removeLast();
            }
            String name = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (name.isEmpty()) {
                continue;
            }
            path.add(name);
            indents.add(indent);

            String fullKey = String.join(".", path);
            keys.add(new ConfigKey(fullKey, value, line, indent, indent + name.length()));
        }
        return keys;
    }

    private static int yamlSeparator(String trimmed) {
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ':'
                    && (i + 1 >= trimmed.length() || trimmed.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String yamlParentPath(String[] lines, int line, int indent) {
        List<String> path = new ArrayList<>();
        int expected = indent;

        for (int i = line - 1; i >= 0 && expected > 0; i--) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            if (trimmed.startsWith("---")) {
                break;
            }
            int currentIndent = indentOf(raw);
            if (currentIndent >= expected) {
                continue;
            }
            int separator = yamlSeparator(trimmed);
            if (separator < 0) {
                continue;
            }
            path.addFirst(trimmed.substring(0, separator).trim());
            expected = currentIndent;
        }
        return String.join(".", path);
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        return indent;
    }
}
