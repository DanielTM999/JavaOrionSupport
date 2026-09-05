package dtm.ide.test;

import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTestRunnerDebugTest {
    @TempDir
    Path root;

    @Test
    void configuresSurefireWithDynamicJdwpPort() throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>x</groupId><artifactId>x</artifactId></project>");
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        CapturingBuild build = new CapturingBuild();

        new JavaTestRunner(descriptor, build).debug(List.of(), descriptor.rootModule(),
                61234, line -> {
                });

        assertTrue(build.request.get().extraArguments().stream()
                .anyMatch(value -> value.contains("address=*:61234")));
        assertTrue(build.request.get().extraArguments().contains("-DforkCount=1"));
    }

    private static final class CapturingBuild implements BuildSystem {
        private final AtomicReference<BuildRequest> request = new AtomicReference<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public BuildResult execute(BuildRequest value, Consumer<String> output) {
            request.set(value);
            return new BuildResult(0, List.of(), Duration.ZERO, "test");
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public Optional<String> resolveRuntimeClasspath(dtm.ide.project.JavaModule module) {
            return Optional.empty();
        }

        @Override
        public void invalidateClasspathCache() {
        }
    }
}
