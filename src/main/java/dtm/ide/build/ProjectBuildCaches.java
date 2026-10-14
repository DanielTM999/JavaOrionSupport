package dtm.ide.build;

import dtm.ide.build.incremental.IncrementalJavaBuilder;
import dtm.ide.build.incremental.ModuleBuildState;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.Files;
import java.nio.file.Path;

/** Derived build data only; project files and run configurations are preserved. */
@Slf4j
public final class ProjectBuildCaches {
    private ProjectBuildCaches() { }

    public static void invalidate(BuildSystem build, Path root) {
        if (build != null) build.invalidateClasspathCache();
        clearPersistedClasspaths(root); // Also handles a build service that has not been created yet.
        if (root != null) ModuleBuildState.discard(IncrementalJavaBuilder.stateDirectory(root));
    }

    static void clearPersistedClasspaths(Path root) {
        if (root == null) return;
        Path directory = root.resolve(".orion/classpath");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".classpath"))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (Exception e) { log.warn("Could not remove classpath cache {}", path, e); }
                    });
        } catch (Exception e) {
            log.warn("Could not clear classpath caches in {}", directory, e);
        }
    }
}
