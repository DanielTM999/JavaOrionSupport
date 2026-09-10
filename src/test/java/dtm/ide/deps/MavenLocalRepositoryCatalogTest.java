package dtm.ide.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenLocalRepositoryCatalogTest {

    @TempDir
    Path repository;

    @Test
    void searchesCoordinatesAndFiltersLocalSnapshots() throws Exception {
        artifact("com.acme", "widget", "2.0.0");
        artifact("com.acme", "widget", "10.0.0");
        artifact("com.acme", "widget", "11.0.0-SNAPSHOT");
        artifact("org.example", "other", "1.0.0");

        try (MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog()) {
            catalog.configure(repository, List.of(), ignored -> { });

            MavenLocalRepositoryCatalog.LocalArtifact stable =
                    catalog.search("widget", false).getFirst();
            assertEquals("10.0.0", stable.coordinate().version());
            assertEquals(List.of("10.0.0", "2.0.0"), stable.versions());
            assertTrue(catalog.search("com.acme:widget", false).getFirst()
                    .coordinate().sameArtifact(stable.coordinate()));

            MavenLocalRepositoryCatalog.LocalArtifact all =
                    catalog.search("widget", true).getFirst();
            assertEquals("11.0.0-SNAPSHOT", all.coordinate().version());
        }
    }

    @Test
    void watcherRefreshesTheIndexWhenARepositoryVersionIsCreated() throws Exception {
        artifact("com.acme", "widget", "1.0.0");
        CountDownLatch changed = new CountDownLatch(1);
        try (MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog()) {
            catalog.configure(repository, List.of(), event -> {
                if (event == MavenLocalRepositoryCatalog.Change.CONTENT) {
                    changed.countDown();
                }
            });

            artifact("com.acme", "widget", "2.0.0");

            assertTrue(changed.await(8, TimeUnit.SECONDS));
            assertTrue(awaitVersion(catalog, "2.0.0", Duration.ofSeconds(3)));
        }
    }

    @Test
    void ignoresMarkerFilesThatDoNotContainAnArtifact() throws Exception {
        Path directory = repository.resolve("com/acme/missing/1.0.0");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("missing-1.0.0.pom.lastUpdated"), "failed");

        try (MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog()) {
            catalog.configure(repository, List.of(), ignored -> { });
            assertFalse(catalog.search("missing", true).stream().findAny().isPresent());
        }
    }

    @Test
    void watcherReportsChangesToRepositoryConfiguration() throws Exception {
        Path settings = repository.getParent().resolve("settings.xml");
        Files.writeString(settings, "<settings/>");
        CountDownLatch changed = new CountDownLatch(1);
        try (MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog()) {
            catalog.configure(repository, List.of(settings), event -> {
                if (event == MavenLocalRepositoryCatalog.Change.CONFIGURATION) {
                    changed.countDown();
                }
            });

            Files.writeString(settings,
                    "<settings><localRepository>other</localRepository></settings>");

            assertTrue(changed.await(8, TimeUnit.SECONDS));
        }
    }

    @Test
    void indexesJarOnlyVersionsAndForgetsRemovedOnes() throws Exception {
        Path directory = repository.resolve("com/acme/widget/4.0.0");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("widget-4.0.0.jar"), "binary");
        CountDownLatch removed = new CountDownLatch(1);

        try (MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog()) {
            catalog.configure(repository, List.of(), event -> {
                if (event == MavenLocalRepositoryCatalog.Change.CONTENT) {
                    removed.countDown();
                }
            });
            assertEquals(List.of("4.0.0"), catalog.versions("com.acme:widget", true));

            Files.delete(directory.resolve("widget-4.0.0.jar"));
            Files.delete(directory);

            assertTrue(removed.await(8, TimeUnit.SECONDS));
            assertTrue(awaitAbsentVersion(catalog, "4.0.0", Duration.ofSeconds(3)));
        }
    }

    @Test
    void reconfiguringSwapsTheRepositoryAndClosingStopsEveryThread() throws Exception {
        artifact("com.acme", "widget", "1.0.0");
        Path other = repository.getParent().resolve("other-repository");
        Path moved = other.resolve("org/example/moved/9.9.9");
        Files.createDirectories(moved);
        Files.writeString(moved.resolve("moved-9.9.9.pom"), "<project/>");

        MavenLocalRepositoryCatalog catalog = new MavenLocalRepositoryCatalog();
        try {
            catalog.configure(repository, List.of(), ignored -> { });
            assertEquals(repository, catalog.repository());

            catalog.configure(other, List.of(), ignored -> { });

            assertEquals(other, catalog.repository());
            assertTrue(catalog.versions("com.acme:widget", true).isEmpty());
            assertEquals(List.of("9.9.9"), catalog.versions("org.example:moved", true));
        } finally {
            catalog.close();
        }

        assertTrue(awaitNoWatcherThreads(Duration.ofSeconds(3)));
        assertTrue(catalog.search("moved", true).isEmpty());
    }

    private boolean awaitAbsentVersion(MavenLocalRepositoryCatalog catalog, String version,
                                       Duration timeout) throws InterruptedException {
        Instant limit = Instant.now().plus(timeout);
        while (Instant.now().isBefore(limit)) {
            if (!catalog.versions("com.acme:widget", true).contains(version)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static boolean awaitNoWatcherThreads(Duration timeout) throws InterruptedException {
        Instant limit = Instant.now().plus(timeout);
        while (Instant.now().isBefore(limit)) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(
                    thread -> thread.isAlive()
                            && thread.getName().startsWith("maven-local-repository"))) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private boolean awaitVersion(MavenLocalRepositoryCatalog catalog, String version,
                                 Duration timeout) throws InterruptedException {
        Instant limit = Instant.now().plus(timeout);
        while (Instant.now().isBefore(limit)) {
            if (catalog.versions("com.acme:widget", true).contains(version)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private void artifact(String group, String artifact, String version) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact)
                .resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), "<project/>");
    }
}
