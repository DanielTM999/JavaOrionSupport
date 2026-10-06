package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class JarCandidates {

    private static final List<String> OUTPUT_DIRS = List.of("target", "build/libs");

    private static final List<String> IGNORED_SUFFIXES = List.of(
            "-sources.jar", "-javadoc.jar", "-tests.jar", "-test.jar");

    private JarCandidates() {
    }

    public static List<Path> find(JavaProjectDescriptor descriptor, JavaModule module) {
        if (descriptor == null) {
            return List.of();
        }
        List<JavaModule> modules = module != null ? List.of(module)
                : descriptor.buildableModules();
        Set<Path> found = new LinkedHashSet<>();
        for (JavaModule candidate : modules) {
            found.addAll(findInModule(candidate));
        }
        return List.copyOf(found);
    }

    static List<Path> findInModule(JavaModule module) {
        if (module == null) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (String outputDir : OUTPUT_DIRS) {
            Path directory = module.root();
            for (String segment : outputDir.split("/")) {
                directory = directory.resolve(segment);
            }
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                entries.filter(JarCandidates::isRunnableJar).forEach(jars::add);
            } catch (Exception ignored) {
            }
        }
        jars.sort(Comparator.comparing((Path path) -> lastModified(path)).reversed()
                .thenComparing(path -> path.getFileName().toString()));
        return List.copyOf(jars);
    }

    static boolean isRunnableJar(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar")) {
            return false;
        }
        if (name.startsWith("original-")) {
            return false;
        }
        return IGNORED_SUFFIXES.stream().noneMatch(name::endsWith);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception error) {
            return 0L;
        }
    }
}
