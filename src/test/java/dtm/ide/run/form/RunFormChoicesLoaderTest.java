package dtm.ide.run.form;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunFormChoicesLoaderTest {

    @TempDir
    Path root;

    private JavaProjectDescriptor descriptor;
    private final AtomicInteger jdkLookups = new AtomicInteger();
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final Executor queued = queue::add;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project></project>");
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(), List.of(), root.resolve("target/classes"));
        descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(module),
                false, false, 21, null);
    }

    @Test
    void theSnapshotIsComputedOffTheCallingThreadAndDeliveredLater() {
        RunFormChoicesLoader loader = loader(queued, queued);
        List<RunFormChoices> delivered = new ArrayList<>();

        loader.request(delivered::add);
        assertTrue(delivered.isEmpty(), "nada e entregue antes do executor rodar");

        drain();
        assertEquals(1, delivered.size());
        assertEquals(List.of("demo"), delivered.getFirst().modules());
    }

    @Test
    void aSecondRequestForTheSameProjectReusesTheCache() {
        RunFormChoicesLoader loader = loader(queued, queued);
        loader.request(choices -> {
        });
        drain();
        assertEquals(1, jdkLookups.get());

        List<RunFormChoices> delivered = new ArrayList<>();
        loader.request(delivered::add);

        assertEquals(1, delivered.size(), "o cache responde na hora, sem executor");
        assertEquals(1, jdkLookups.get(), "o snapshot nao e recalculado");
    }

    @Test
    void concurrentRequestsShareASingleComputation() {
        RunFormChoicesLoader loader = loader(queued, queued);
        List<RunFormChoices> delivered = new ArrayList<>();

        loader.request(delivered::add);
        loader.request(delivered::add);
        loader.request(delivered::add);
        drain();

        assertEquals(3, delivered.size(), "todos os formularios recebem o snapshot");
        assertEquals(1, jdkLookups.get(), "o projeto e varrido uma unica vez");
    }

    @Test
    void invalidatingForcesAFreshSnapshot() {
        RunFormChoicesLoader loader = loader(queued, queued);
        loader.request(choices -> {
        });
        drain();
        loader.invalidate();

        loader.request(choices -> {
        });
        drain();

        assertEquals(2, jdkLookups.get());
    }

    @Test
    void theJdkListAlwaysStartsWithTheProjectOption() {
        RunFormChoicesLoader loader = loader(Runnable::run, Runnable::run);
        List<RunFormChoices> delivered = new ArrayList<>();
        loader.request(delivered::add);

        RunFormChoices choices = delivered.getFirst();
        assertEquals("JDK do projeto", choices.jdks().keySet().iterator().next());
        assertEquals("", choices.jdks().values().iterator().next());
        assertTrue(choices.jdks().containsValue(root.resolve("jdk").toString()));
    }

    @Test
    void aProjectlessLoaderStillAnswersWithTheProjectJdkOption() {
        RunFormChoicesLoader loader = new RunFormChoicesLoader(() -> null, List::of,
                Runnable::run, Runnable::run, "JDK do projeto");
        List<RunFormChoices> delivered = new ArrayList<>();

        loader.request(delivered::add);

        assertEquals(1, delivered.size());
        assertTrue(delivered.getFirst().modules().isEmpty());
        assertFalse(delivered.getFirst().jdks().isEmpty());
    }

    private RunFormChoicesLoader loader(Executor background, Executor ui) {
        return new RunFormChoicesLoader(() -> descriptor, this::jdks, background, ui,
                "JDK do projeto");
    }

    private List<JdkInstallation> jdks() {
        jdkLookups.incrementAndGet();
        return List.of(new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.MANAGED));
    }

    private void drain() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }
}
