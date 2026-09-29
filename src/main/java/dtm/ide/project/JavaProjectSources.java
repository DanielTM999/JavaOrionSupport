package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


@Slf4j
public final class JavaProjectSources {

    public static final int MAX_FILES = 20_000;

    public record Source(Path file, JavaModule module, boolean test, String content) {
        public Source {
            file = JavaProjectConventions.normalize(file);
            content = content == null ? "" : content;
        }
    }

    private final List<Source> files;
    private final long bytes;

    private JavaProjectSources(List<Source> files, long bytes) {
        this.files = List.copyOf(files);
        this.bytes = bytes;
    }

    public static JavaProjectSources collect(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return new JavaProjectSources(List.of(), 0);
        }
        long started = System.nanoTime();
        List<Source> collected = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();

        // Production sources are collected first so the shared limit cannot hide Spring beans
        // behind a very large test tree.
        collect(descriptor.modules(), false, collected, visited);
        collect(descriptor.modules(), true, collected, visited);

        long bytes = collected.stream().mapToLong(source -> source.content().length()).sum();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        log.info("Fontes do projeto: {} arquivo(s), {} caractere(s), {} ms em {}",
                collected.size(), bytes, elapsedMs, descriptor.root());
        return new JavaProjectSources(collected, bytes);
    }

    private static void collect(List<JavaModule> modules, boolean test, List<Source> target,
                                Set<Path> visited) {
        for (JavaModule module : modules) {
            List<Path> roots = test ? module.existingTestRoots() : module.existingSourceRoots();
            for (Path root : roots) {
                int remaining = MAX_FILES - target.size();
                if (remaining <= 0) {
                    return;
                }
                for (Path file : JavaProjectConventions.javaSources(root, 0, remaining)) {
                    Path normalized = JavaProjectConventions.normalize(file);
                    if (visited.add(normalized)) {
                        target.add(new Source(normalized, module, test,
                                JavaProjectConventions.readOrEmpty(normalized)));
                    }
                }
            }
        }
    }

    public List<Source> files() {
        return files;
    }

    public List<Source> productionFiles() {
        return files.stream().filter(source -> !source.test()).toList();
    }

    public long bytes() {
        return bytes;
    }
}
