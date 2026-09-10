package dtm.ide;

import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.deps.OsvClient;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.ui.DependencyManagerPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyManagerCoordinatorTest {

    @TempDir
    Path workspace;

    @Test
    void repositoryEventsRefreshTheSearchWithoutQueryingTheWebAgain() throws Exception {
        Path repository = workspace.resolve("MavenRepository");
        artifact(repository, "com.acme", "widget", "1.0.0");
        Path project = projectUsing(repository);
        CountingCentralClient central = new CountingCentralClient();
        CountDownLatch repositoryChanged = new CountDownLatch(1);

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            coordinator.onLocalRepositoryChanged(repositoryChanged::countDown);

            assertEquals(List.of("1.0.0"), localVersions(coordinator, "widget"));
            assertEquals(1, central.searches.get());

            assertEquals(List.of("1.0.0"), localVersions(coordinator, "widget"));
            assertEquals(1, central.searches.get());

            artifact(repository, "com.acme", "widget", "2.0.0");
            assertTrue(repositoryChanged.await(10, TimeUnit.SECONDS));

            assertTrue(awaitLocalVersion(coordinator, "2.0.0"));
            assertEquals(1, central.searches.get());
        }
    }

    @Test
    void aFailingWebSearchStillReportsTheLocalIndex() throws Exception {
        Path repository = workspace.resolve("MavenRepository");
        artifact(repository, "com.acme", "widget", "1.0.0");
        Path project = projectUsing(repository);
        CountingCentralClient central = new CountingCentralClient();
        central.offline = true;

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            DependencyManagerPanel.SearchOutcome outcome = search(coordinator, "widget");

            assertEquals(DependencyManagerPanel.RemoteStatus.FAILED, outcome.remote());
            assertFalse(outcome.localUnavailable());
            assertEquals(1, outcome.results().size());
            assertTrue(outcome.results().getFirst().local());
            assertFalse(outcome.results().getFirst().remote());
        }
    }

    @Test
    void anUnreachableRepositoryLeavesTheWebResultsUntouched() throws Exception {
        Path project = projectUsing(workspace.resolve("missing-repository"));
        CountingCentralClient central = new CountingCentralClient();

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            DependencyManagerPanel.SearchOutcome outcome = search(coordinator, "widget");

            assertTrue(outcome.localUnavailable());
            assertEquals(DependencyManagerPanel.RemoteStatus.OK, outcome.remote());
            assertEquals(1, outcome.results().size());
            assertTrue(outcome.results().getFirst().remote());
            assertFalse(outcome.results().getFirst().local());
        }
    }

    @Test
    void theLocalOnlyModeNeverTouchesTheWeb() throws Exception {
        Path repository = workspace.resolve("MavenRepository");
        artifact(repository, "com.acme", "widget", "1.0.0");
        Path project = projectUsing(repository);
        CountingCentralClient central = new CountingCentralClient();

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            coordinator.setLocalOnly(true);

            DependencyManagerPanel.SearchOutcome outcome = search(coordinator, "widget");

            assertEquals(DependencyManagerPanel.RemoteStatus.SKIPPED, outcome.remote());
            assertEquals(0, central.searches.get());
            assertEquals(1, outcome.results().size());
            assertTrue(outcome.results().getFirst().local());
            assertEquals(List.of("1.0.0"), versions(coordinator,
                    DependencyCoordinate.of("com.acme", "widget", "1.0.0")));
            assertEquals(0, central.versionLookups.get());
            assertEquals(Map.of(), latestVersions(coordinator,
                    DependencyCoordinate.of("com.acme", "widget", "1.0.0")));
        }
    }

    @Test
    void veryShortQueriesAnswerFromTheLocalIndexWithoutAWebCall() throws Exception {
        Path repository = workspace.resolve("MavenRepository");
        artifact(repository, "com.acme", "widget", "1.0.0");
        Path project = projectUsing(repository);
        CountingCentralClient central = new CountingCentralClient();

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            DependencyManagerPanel.SearchOutcome shortQuery = search(coordinator, "wi");

            assertEquals(DependencyManagerPanel.RemoteStatus.SKIPPED, shortQuery.remote());
            assertEquals(0, central.searches.get());
            assertEquals(1, shortQuery.results().size());
            assertTrue(shortQuery.results().getFirst().local());

            assertEquals(DependencyManagerPanel.RemoteStatus.OK,
                    search(coordinator, "wid").remote());
            assertEquals(1, central.searches.get());
        }
    }

    @Test
    void anExplicitCoordinateStillReachesTheWebEvenWhenShort() throws Exception {
        Path project = projectUsing(workspace.resolve("MavenRepository"));
        CountingCentralClient central = new CountingCentralClient();

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            assertEquals(DependencyManagerPanel.RemoteStatus.OK,
                    search(coordinator, "a:b").remote());
            assertEquals(1, central.searches.get());
        }
    }

    @Test
    void theUpdatesTabGivesUpImmediatelyWhileTheCentralIsCoolingDown() throws Exception {
        Path project = projectUsing(workspace.resolve("MavenRepository"));
        CountingCentralClient central = new CountingCentralClient();
        central.coolingDown = true;

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            Map<String, String> latest = latestVersions(coordinator,
                    DependencyCoordinate.of("com.acme", "widget", "1.0.0"),
                    DependencyCoordinate.of("com.acme", "gadget", "1.0.0"),
                    DependencyCoordinate.of("com.acme", "gizmo", "1.0.0"));

            assertEquals(Map.of(), latest);
            assertEquals(0, central.versionLookups.get());
        }
    }

    @Test
    void webSearchesAreReportedAsSkippedWhileTheCentralIsCoolingDown() throws Exception {
        Path repository = workspace.resolve("MavenRepository");
        artifact(repository, "com.acme", "widget", "1.0.0");
        Path project = projectUsing(repository);
        CountingCentralClient central = new CountingCentralClient();
        central.coolingDown = true;

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            DependencyManagerPanel.SearchOutcome outcome = search(coordinator, "widget");

            assertEquals(DependencyManagerPanel.RemoteStatus.SKIPPED, outcome.remote());
            assertEquals(0, central.searches.get());
            assertEquals(1, outcome.results().size());
            assertTrue(outcome.results().getFirst().local());
        }
    }

    @Test
    void theUpdatesTabResolvesEveryCoordinateInParallel() throws Exception {
        Path project = projectUsing(workspace.resolve("MavenRepository"));
        CountingCentralClient central = new CountingCentralClient();

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            Map<String, String> latest = latestVersions(coordinator,
                    DependencyCoordinate.of("com.acme", "widget", "0.1"),
                    DependencyCoordinate.of("com.acme", "gadget", "0.1"),
                    DependencyCoordinate.of("com.acme", "gizmo", "0.1"));

            assertEquals(3, latest.size());
            assertEquals("1.0.0", latest.get("com.acme:widget"));
            assertEquals(List.of("com.acme:widget", "com.acme:gadget", "com.acme:gizmo"),
                    List.copyOf(latest.keySet()));
            assertEquals(3, central.versionLookups.get());
            assertTrue(central.maxConcurrent.get() <= 6,
                    "no maximo 6 consultas simultaneas, mas houve " + central.maxConcurrent.get());
        }
    }

    @Test
    void theUpdatesTabNeverReturnsAnOlderVersionAsAnUpdate() throws Exception {
        Path project = projectUsing(workspace.resolve("MavenRepository"));
        CountingCentralClient central = new CountingCentralClient();
        central.availableVersions = List.of("1.18.38");

        try (PluginTaskExecutor tasks = new PluginTaskExecutor("coordinator-test");
             DependencyManagerCoordinator coordinator = coordinator(tasks, central, project)) {
            Map<String, String> latest = latestVersions(coordinator,
                    DependencyCoordinate.of("org.projectlombok", "lombok", "1.18.46"));

            assertEquals(Map.of(), latest);
            assertEquals(1, central.versionLookups.get());
        }
    }

    private static Map<String, String> latestVersions(DependencyManagerCoordinator coordinator,
                                                      DependencyCoordinate... coordinates)
            throws Exception {
        BlockingQueue<Map<String, String>> results = new ArrayBlockingQueue<>(1);
        coordinator.latestVersions(List.of(coordinates), results::offer);
        Map<String, String> latest = results.poll(10, TimeUnit.SECONDS);
        assertTrue(latest != null, "latestVersions did not complete");
        return latest;
    }

    private static List<String> versions(DependencyManagerCoordinator coordinator,
                                         DependencyCoordinate coordinate) throws Exception {
        BlockingQueue<List<String>> results = new ArrayBlockingQueue<>(1);
        coordinator.versions(coordinate, false, choices -> results.offer(
                choices.stream().map(choice -> choice.version()).toList()));
        List<String> versions = results.poll(10, TimeUnit.SECONDS);
        assertTrue(versions != null, "versions did not complete");
        return versions;
    }

    private static DependencyManagerCoordinator coordinator(PluginTaskExecutor tasks,
                                                            MavenCentralClient central,
                                                            Path project) {
        JavaModule module = new JavaModule(project, "demo", "com.demo", "demo", "jar",
                List.of(), List.of(), project.resolve("build/classes/java/main"));
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(project,
                JavaProjectKind.GRADLE, List.of(module), false, false, 21, null);
        return new DependencyManagerCoordinator(tasks, central, new OsvClient(),
                () -> descriptor, () -> null, () -> null, () -> 0L, () -> project,
                (ticket, root) -> true, changed -> { });
    }

    private static DependencyManagerPanel.SearchOutcome search(
            DependencyManagerCoordinator coordinator, String query) throws Exception {
        BlockingQueue<DependencyManagerPanel.SearchOutcome> outcomes = new ArrayBlockingQueue<>(1);
        coordinator.search(query, false, outcomes::offer);
        DependencyManagerPanel.SearchOutcome outcome = outcomes.poll(10, TimeUnit.SECONDS);
        assertTrue(outcome != null, "search did not complete");
        return outcome;
    }

    private static List<String> localVersions(DependencyManagerCoordinator coordinator,
                                              String query) throws Exception {
        return search(coordinator, query).results().getFirst().localVersions();
    }

    private static boolean awaitLocalVersion(DependencyManagerCoordinator coordinator,
                                             String version) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (localVersions(coordinator, "widget").contains(version)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static Path projectUsing(Path repository) throws Exception {
        Path project = repository.getParent().resolve("project");
        Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve(".mvn").resolve("maven.config"),
                "-Dmaven.repo.local=" + repository.toAbsolutePath());
        return project;
    }

    private static void artifact(Path repository, String group, String artifact, String version)
            throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact)
                .resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), "<project/>");
    }

    private static final class CountingCentralClient extends MavenCentralClient {
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger versionLookups = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private List<String> availableVersions = List.of("1.0.0");
        private boolean offline;
        private boolean coolingDown;

        @Override
        public boolean isCoolingDown() {
            return coolingDown;
        }

        @Override
        public Optional<List<SearchResult>> trySearch(String query) {
            searches.incrementAndGet();
            if (offline) {
                return Optional.empty();
            }
            return Optional.of(List.of(new SearchResult(
                    DependencyCoordinate.of("com.acme", "widget", "1.0.0"), 1, 100)));
        }

        @Override
        public List<String> versions(String groupId, String artifactId) {
            versionLookups.incrementAndGet();
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return availableVersions;
        }
    }
}
