package dtm.ide.swingdesigner.catalog;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record DescriptorSet(String name,
                            Layer layer,
                            Path entry,
                            String defaultCategory,
                            List<String> hide,
                            Map<String, ComponentDescriptor> components,
                            Map<String, LayoutDescriptor> layouts) {

    public enum Layer {
        JDK,
        LIBRARY,
        PROJECT
    }

    public DescriptorSet {
        hide = hide == null ? List.of() : List.copyOf(hide);
        components = components == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(components));
        layouts = layouts == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(layouts));
    }

    public boolean hides(String className) {
        for (String pattern : hide) {
            if (matches(pattern, className)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String pattern, String className) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String trimmed = pattern.trim();
        if (trimmed.endsWith(".**")) {
            String prefix = trimmed.substring(0, trimmed.length() - 3);
            return className.equals(prefix) || className.startsWith(prefix + ".")
                    || className.startsWith(prefix + "$");
        }
        if (trimmed.endsWith(".*")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2);
            if (!className.startsWith(prefix + ".")) {
                return false;
            }
            return className.indexOf('.', prefix.length() + 1) < 0;
        }
        if (trimmed.contains("*")) {
            StringBuilder regex = new StringBuilder();
            String[] parts = trimmed.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append("[^.]*");
                }
                regex.append(Pattern.quote(parts[i]));
            }
            return className.matches(regex.toString());
        }
        return trimmed.equals(className);
    }
}
