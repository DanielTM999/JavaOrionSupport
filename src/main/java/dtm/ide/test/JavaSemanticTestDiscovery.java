package dtm.ide.test;

import dtm.ide.lsp.api.TestDiscoverySupport;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavaSemanticTestDiscovery {
    private JavaSemanticTestDiscovery() {
    }

    public static List<JavaTest> enrich(JavaProjectDescriptor descriptor, TestDiscoverySupport discovery,
                                        List<JavaTest> provisional) {
        List<JavaTest> lexical = provisional == null ? List.of() : provisional;
        if (descriptor == null || discovery == null || !discovery.isTestRunnerAvailable()) {
            return lexical;
        }
        Set<Path> files = candidateFiles(descriptor, lexical);
        List<JavaTest> semantic = files.parallelStream().map(discovery::testsIn)
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

    private static String key(JavaTest test) {
        return test.className() + "#" + test.methodName();
    }
}
