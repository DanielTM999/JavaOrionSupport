package dtm.ide.test;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.JdtLsService;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavaSemanticTestDiscovery {
    private JavaSemanticTestDiscovery() {
    }

    public static List<JavaTest> enrich(JavaProjectDescriptor descriptor, JdtLsService lsp,
                                        List<JavaTest> provisional) {
        List<JavaTest> lexical = provisional == null ? List.of() : provisional;
        if (descriptor == null || lsp == null || !lsp.isTestRunnerAvailable()) {
            return lexical;
        }
        Set<Path> files = candidateFiles(descriptor, lexical);
        List<JavaTest> semantic = files.parallelStream().map(file -> discoverFile(lsp, file))
                .flatMap(List::stream).toList();
        if (semantic.isEmpty()) {
            return lexical;
        }
        Map<String, JavaTest> merged = new LinkedHashMap<>();
        lexical.forEach(test -> merged.put(key(test), test));
        semantic.forEach(test -> merged.merge(key(test), test,
                (oldValue, newValue) -> newValue.displayName().isBlank()
                        ? oldValue : newValue));
        return merged.values().stream().sorted().toList();
    }

    private static Set<Path> candidateFiles(JavaProjectDescriptor descriptor,
                                            List<JavaTest> provisional) {
        Set<Path> files = new LinkedHashSet<>();
        provisional.stream().map(JavaTest::file).filter(java.util.Objects::nonNull)
                .forEach(files::add);
        descriptor.modules().forEach(module -> module.existingTestRoots().forEach(root -> {
            if (files.size() >= 5_000) {
                return;
            }
            for (Path path : JavaProjectConventions.javaSources(
                    root, 0, JavaProjectConventions.MAX_SCAN_FILES)) {
                if (containsTestMarker(path)) {
                    files.add(path);
                    if (files.size() >= 5_000) {
                        break;
                    }
                }
            }
        }));
        return files;
    }

    private static boolean containsTestMarker(Path file) {
        String source = JavaProjectConventions.readOrEmpty(file);
        return source.contains("@Test") || source.contains("@ParameterizedTest")
                || source.contains("@RepeatedTest") || source.contains("@TestFactory")
                || source.contains("org.testng") || source.contains("extends TestCase");
    }

    private static List<JavaTest> discoverFile(JdtLsService lsp, Path file) {
        JsonNode result = lsp.findTestTypesAndMethods(file);
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

    private static String key(JavaTest test) {
        return test.className() + "#" + test.methodName();
    }
}
