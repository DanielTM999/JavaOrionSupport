package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.test.JavaTest;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class JdtTestItems {
    private JdtTestItems() {
    }

    static List<JavaTest> parse(JsonNode result, Path file) {
        if (result == null || result.isNull()) {
            return List.of();
        }
        List<JavaTest> tests = new ArrayList<>();
        collect(result, file, "", tests);
        return tests;
    }

    private static void collect(JsonNode node, Path fallbackFile, String parentClass,
                                List<JavaTest> tests) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, fallbackFile, parentClass, tests));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String fullName = node.path("fullName").asText("");
        int separator = fullName.indexOf('#');
        int level = node.has("testLevel") ? node.path("testLevel").asInt(-1)
                : node.path("level").asInt(-1);
        String className = separator > 0 ? fullName.substring(0, separator)
                : level == 5 || level == 3 ? fullName : parentClass;
        if (separator > 0 || level == 6 || level == 4) {
            String method = separator > 0 ? fullName.substring(separator + 1)
                    : node.path("name").asText(node.path("label").asText(""));
            int parameters = method.indexOf('(');
            if (parameters > 0) {
                method = method.substring(0, parameters);
            }
            if (!className.isBlank() && !method.isBlank()) {
                String label = node.path("label").asText("");
                tests.add(new JavaTest(className, method,
                        label.equals(method) || label.equals(method + "()") ? "" : label,
                        sourcePath(node, fallbackFile), line(node),
                        node.has("children") && !node.path("children").isEmpty()));
            }
        }
        String nextParent = className.isBlank() ? parentClass : className;
        collect(node.path("children"), fallbackFile, nextParent, tests);
    }

    private static Path sourcePath(JsonNode node, Path fallback) {
        String value = node.path("uri").asText(node.path("location").path("uri").asText(""));
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Path.of(URI.create(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int line(JsonNode node) {
        JsonNode range = node.has("range") ? node.path("range")
                : node.path("location").path("range");
        return Math.max(1, range.path("start").path("line").asInt(0) + 1);
    }
}
