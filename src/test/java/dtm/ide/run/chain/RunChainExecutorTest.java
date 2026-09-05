package dtm.ide.run.chain;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunChainExecutorTest {

    private final List<String> executed = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    @Test
    void stepsRunInOrder() {
        FakeHost host = host("a", "b", "c");
        Optional<String> failure = execute(host, List.of(
                RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("b", false, "JAR"),
                RunChainStep.of("c", true, "Remote")));

        assertTrue(failure.isEmpty());
        assertEquals(List.of("a:run", "b:run", "c:debug"), executed);
    }

    @Test
    void theOutputIsNumbered() {
        FakeHost host = host("a", "b");
        execute(host, List.of(RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("b", false, "JAR")));

        assertTrue(lines.get(0).startsWith("[1/2] "), lines.get(0));
        assertTrue(lines.get(1).startsWith("[2/2] "), lines.get(1));
        assertTrue(lines.get(0).contains("Maven"));
    }

    @Test
    void aFailingStepStopsTheChain() {
        FakeHost host = host("a", "b");
        host.failOn = "a";

        Optional<String> failure = execute(host, List.of(
                RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("b", false, "JAR")));

        assertTrue(failure.isPresent());
        assertFalse(executed.contains("b:run"), "o passo seguinte nao pode rodar");
    }

    @Test
    void aMissingConfigurationAbortsWithAClearMessage() {
        FakeHost host = host("a");

        Optional<String> failure = execute(host, List.of(
                RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("removida", false, "Apagada")));

        assertTrue(failure.isPresent());
        assertTrue(failure.get().contains("Apagada"), failure.get());
        assertEquals(List.of("a:run"), executed);
    }

    @Test
    void waitingBlocksUntilTheProcessEnds() {
        FakeHost host = host("a", "b");
        host.aliveOnce = true;

        execute(host, List.of(RunChainStep.of("a", false, "Servidor"),
                RunChainStep.of("b", false, "Depois")));

        assertEquals(List.of("a:run", "b:run"), executed);
        assertTrue(host.polled, "o executor precisa consultar isAlive enquanto espera");
    }

    @Test
    void notWaitingMovesOnImmediately() {
        FakeHost host = host("a", "b");
        host.neverEnds = true;

        Optional<String> failure = execute(host, List.of(
                new RunChainStep("a", false, false, "Servidor"),
                RunChainStep.of("b", false, "Depois")));

        assertTrue(failure.isEmpty());
        assertEquals(List.of("a:run", "b:run"), executed);
    }

    @Test
    void aSelfReferenceIsSkipped() {
        FakeHost host = host("a", "self");

        Optional<String> failure = new RunChainExecutor(host, lines::add)
                .run(List.of(RunChainStep.of("self", false, "Eu mesma"),
                        RunChainStep.of("a", false, "Maven")), "self");

        assertTrue(failure.isEmpty());
        assertEquals(List.of("a:run"), executed);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Eu mesma")));
    }

    @Test
    void aRepeatedStepRunsOnlyOnce() {
        FakeHost host = host("a");

        execute(host, List.of(RunChainStep.of("a", false, "Maven"),
                RunChainStep.of("a", false, "Maven")));

        assertEquals(List.of("a:run"), executed);
    }

    @Test
    void anEmptyChainDoesNothing() {
        FakeHost host = host("a");

        assertTrue(execute(host, List.of()).isEmpty());
        assertTrue(execute(host, null).isEmpty());
        assertTrue(executed.isEmpty());
    }

    @Test
    void anExceptionFromTheHostBecomesAFailure() {
        FakeHost host = host("a");
        host.thrower = id -> {
            throw new IllegalStateException("host caiu");
        };

        Optional<String> failure = execute(host, List.of(RunChainStep.of("a", false, "Maven")));

        assertTrue(failure.isPresent());
        assertTrue(failure.get().contains("host caiu"), failure.get());
    }

    private Optional<String> execute(FakeHost host, List<RunChainStep> steps) {
        return new RunChainExecutor(host, lines::add).run(steps, "current");
    }

    private FakeHost host(String... ids) {
        return new FakeHost(List.of(ids));
    }

    private final class FakeHost implements RunChainHost {

        private final List<String> ids;
        private String failOn;
        private boolean aliveOnce;
        private boolean neverEnds;
        private boolean polled;
        private Function<String, RunProcessHandle> thrower;

        private FakeHost(List<String> ids) {
            this.ids = ids;
        }

        @Override
        public List<RunConfigurationData> configurations() {
            List<RunConfigurationData> configurations = new ArrayList<>();
            for (String id : ids) {
                Map<String, Object> properties = new LinkedHashMap<>();
                configurations.add(RunConfigurationData.builder()
                        .id(id).type("java.run").title(id).properties(properties).build());
            }
            return configurations;
        }

        @Override
        public RunProcessHandle execute(String configurationId, boolean debug) {
            if (thrower != null) {
                return thrower.apply(configurationId);
            }
            executed.add(configurationId + (debug ? ":debug" : ":run"));
            if (configurationId.equals(failOn)) {
                throw new IllegalStateException("falhou");
            }
            if (neverEnds && "a".equals(configurationId)) {
                return RunProcessHandle.builder().alive(() -> true).build();
            }
            if (aliveOnce) {
                AtomicBoolean alive = new AtomicBoolean(true);
                return RunProcessHandle.builder().alive(() -> {
                    polled = true;
                    return !alive.compareAndSet(true, false);
                }).build();
            }
            return RunProcessHandle.empty();
        }
    }
}
