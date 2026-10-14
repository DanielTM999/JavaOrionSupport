package dtm.ide.adapter;

import dtm.ide.build.*;
import dtm.ide.project.*;
import dtm.ide.settings.JavaPluginSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class BuildCacheInvalidationTest {
    @TempDir Path root;

    @Test void rebuildInvalidatesBeforeExecuting() throws Exception { verify(BuildSystem.BuildAction.REBUILD); }
    @Test void cleanInvalidatesBeforeExecuting() throws Exception { verify(BuildSystem.BuildAction.CLEAN); }

    @Test void clearWithoutAnInitializedBuildServiceStillRemovesDerivedData() throws Exception {
        Path classpath = write(".orion/classpath/module-runtime.classpath");
        Path state = write(".orion/incremental/module.state");
        Path workspace = write(".orion/workspace.orion");
        Path compiled = write("target/classes/Main.class");
        Path source = write("src/main/java/Main.java");
        ProjectBuildCaches.invalidate(null, root);
        assertFalse(Files.exists(classpath));
        assertFalse(Files.exists(state));
        assertTrue(Files.exists(workspace));
        assertTrue(Files.exists(compiled));
        assertTrue(Files.exists(source));
    }

    private void verify(BuildSystem.BuildAction action) throws Exception {
        Path classpath = write(".orion/classpath/module-runtime.classpath");
        Path state = write(".orion/incremental/module.state");
        FakeBuild build = new FakeBuild();
        var descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(), false, false, 25, null);
        AdapterHost host = (AdapterHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AdapterHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "projectRoot" -> root;
                    case "descriptor" -> descriptor;
                    case "settings" -> new JavaPluginSettings(root.resolve("settings.properties"));
                    default -> null;
                });
        build.beforeExecute = () -> {
            assertTrue(build.invalidated);
            assertFalse(Files.exists(classpath));
            assertFalse(Files.exists(state));
        };
        BuildResult result = new BuildSupport(host).executeBuild(action, build, null, line -> {});
        assertTrue(result.successful());
        assertEquals(action, build.action);
    }

    private Path write(String name) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "preserved or invalidated according to its role");
    }

    private static class FakeBuild implements BuildSystem {
        boolean invalidated;
        BuildAction action;
        Runnable beforeExecute;
        public String name() { return "test"; }
        public BuildResult execute(BuildRequest request, Consumer<String> output) {
            beforeExecute.run();
            action = request.action();
            return new BuildResult(0, List.of(), Duration.ZERO, "test");
        }
        public void cancel() { }
        public boolean isRunning() { return false; }
        public Optional<String> resolveRuntimeClasspath(JavaModule module) { return Optional.empty(); }
        public void invalidateClasspathCache() { invalidated = true; }
    }
}
