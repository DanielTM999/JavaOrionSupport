package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class ReactorClasspath {

    private static final String TEST_OUTPUT_DIR = "target/test-classes";
    private static final String TESTS_CLASSIFIER = "-tests.jar";
    private static final java.util.regex.Pattern GROUP_SEPARATOR =
            java.util.regex.Pattern.compile("[.]");
    private static final java.util.regex.Pattern PATH_SEPARATOR =
            java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(File.pathSeparator));

    private ReactorClasspath() {
    }

    public static String substituteWorkspaceModules(String classpath,
                                                    JavaProjectDescriptor descriptor,
                                                    JavaModule current, boolean test) {
        if (classpath == null || classpath.isBlank() || descriptor == null) {
            return classpath == null ? "" : classpath;
        }
        Map<Path, JavaModule> candidates = candidatesOf(descriptor, current);
        if (candidates.isEmpty()) {
            return classpath;
        }

        List<JavaModule> modules = List.copyOf(candidates.values());
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : PATH_SEPARATOR.split(classpath)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            entries.add(replacement(trimmed, modules, test).orElse(trimmed));
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Map<Path, JavaModule> candidatesOf(JavaProjectDescriptor descriptor,
                                                      JavaModule current) {
        Map<Path, JavaModule> candidates = new LinkedHashMap<>();
        for (JavaModule module : descriptor.modules()) {
            if (module == null || module.isAggregator()) {
                continue;
            }
            if (current != null && module.root().equals(current.root())) {
                continue;
            }
            candidates.putIfAbsent(module.root(), module);
        }
        return candidates;
    }

    private static Optional<String> replacement(String entry, List<JavaModule> modules,
                                                boolean test) {
        if (!entry.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return Optional.empty();
        }
        Path jar;
        try {
            jar = Path.of(entry);
        } catch (Exception e) {
            return Optional.empty();
        }
        for (JavaModule module : modules) {
            if (!matches(jar, module)) {
                continue;
            }
            Path output = test && entry.endsWith(TESTS_CLASSIFIER)
                    ? module.root().resolve(TEST_OUTPUT_DIR)
                    : module.outputDir();
            if (!Files.isDirectory(output)) {
                log.debug("Modulo {} substituido por {}, que ainda nao existe", module.coordinates(),
                        output);
            }
            return Optional.of(output.toString());
        }
        return Optional.empty();
    }

    static boolean matches(Path jar, JavaModule module) {
        Path versionDir = jar.getParent();
        if (versionDir == null) {
            return false;
        }
        Path artifactDir = versionDir.getParent();
        if (artifactDir == null || artifactDir.getFileName() == null) {
            return false;
        }
        if (!artifactDir.getFileName().toString().equals(module.artifactId())) {
            return false;
        }
        if (versionDir.getFileName() == null) {
            return false;
        }
        String version = versionDir.getFileName().toString();
        if (!jar.getFileName().toString().startsWith(module.artifactId() + "-" + version)) {
            return false;
        }
        return groupMatches(artifactDir.getParent(), module.groupId());
    }

    private static boolean groupMatches(Path beforeArtifact, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return true;
        }
        List<String> expected = new ArrayList<>(List.of(GROUP_SEPARATOR.split(groupId)));
        Path cursor = beforeArtifact;
        for (int i = expected.size() - 1; i >= 0; i--) {
            if (cursor == null || cursor.getFileName() == null) {
                return false;
            }
            if (!cursor.getFileName().toString().equals(expected.get(i))) {
                return false;
            }
            cursor = cursor.getParent();
        }
        return true;
    }
}
