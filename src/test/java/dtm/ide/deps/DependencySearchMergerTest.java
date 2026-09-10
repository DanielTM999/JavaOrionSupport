package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencySearchMergerTest {

    @Test
    void mergesTheSameCoordinateAndKeepsBothOrigins() {
        MavenCentralClient.SearchResult remote = new MavenCentralClient.SearchResult(
                DependencyCoordinate.of("com.acme", "widget", "2.0.0"), 8, 100);
        MavenLocalRepositoryCatalog.LocalArtifact local =
                new MavenLocalRepositoryCatalog.LocalArtifact(
                        DependencyCoordinate.of("com.acme", "widget", "3.0.0"),
                        List.of("3.0.0", "1.0.0"), 200, Path.of("local-repository"));

        DependencySearchResult result = DependencySearchMerger.merge("widget",
                List.of(local), List.of(remote), false).getFirst();

        assertTrue(result.local());
        assertTrue(result.remote());
        assertEquals("3.0.0", result.coordinate().version());
        assertEquals(8, result.versionCount());
        assertEquals(List.of("3.0.0", "1.0.0"), result.localVersions());
    }

    @Test
    void localResultWinsARelevanceTie() {
        MavenLocalRepositoryCatalog.LocalArtifact local =
                new MavenLocalRepositoryCatalog.LocalArtifact(
                        DependencyCoordinate.of("local.group", "library-one", "1.0"),
                        List.of("1.0"), 1, Path.of("repo"));
        MavenCentralClient.SearchResult remote = new MavenCentralClient.SearchResult(
                DependencyCoordinate.of("remote.group", "library-two", "1.0"), 1, 1);

        List<DependencySearchResult> results = DependencySearchMerger.merge("library",
                List.of(local), List.of(remote), false);

        assertTrue(results.getFirst().local());
        assertFalse(results.getLast().local());
    }

    @Test
    void versionChoicesPreserveEachAvailableOrigin() {
        List<DependencyVersionChoice> versions = DependencySearchMerger.mergeVersions(
                List.of("3.0", "2.0"), List.of("2.0", "1.0"));

        assertEquals(List.of("3.0", "2.0", "1.0"),
                versions.stream().map(DependencyVersionChoice::version).toList());
        assertTrue(versions.get(0).localOnly());
        assertTrue(versions.get(1).local());
        assertTrue(versions.get(1).remote());
        assertFalse(versions.get(2).local());
    }

    @Test
    void eachOriginSurvivesTheFailureOfTheOther() {
        MavenLocalRepositoryCatalog.LocalArtifact local =
                new MavenLocalRepositoryCatalog.LocalArtifact(
                        DependencyCoordinate.of("com.acme", "widget", "3.0.0"),
                        List.of("3.0.0"), 200, Path.of("repo"));
        MavenCentralClient.SearchResult remote = new MavenCentralClient.SearchResult(
                DependencyCoordinate.of("com.acme", "widget", "2.0.0"), 8, 100);

        DependencySearchResult offline = DependencySearchMerger.merge("widget",
                List.of(local), List.of(), false).getFirst();
        assertTrue(offline.local());
        assertFalse(offline.remote());
        assertEquals("3.0.0", offline.coordinate().version());

        DependencySearchResult withoutIndex = DependencySearchMerger.merge("widget",
                List.of(), List.of(remote), false).getFirst();
        assertFalse(withoutIndex.local());
        assertTrue(withoutIndex.remote());
        assertEquals("2.0.0", withoutIndex.coordinate().version());
        assertEquals(List.of(), withoutIndex.localVersions());
    }

    @Test
    void preReleasesOnlyLeadWhenTheyAreRequested() {
        MavenCentralClient.SearchResult remote = new MavenCentralClient.SearchResult(
                DependencyCoordinate.of("com.acme", "widget", "4.0.0-RC1"), 3, 100);
        MavenLocalRepositoryCatalog.LocalArtifact local =
                new MavenLocalRepositoryCatalog.LocalArtifact(
                        DependencyCoordinate.of("com.acme", "widget", "3.0.0"),
                        List.of("3.0.0"), 200, Path.of("repo"));

        assertEquals("3.0.0", DependencySearchMerger.merge("widget", List.of(local),
                List.of(remote), false).getFirst().coordinate().version());
        assertEquals("4.0.0-RC1", DependencySearchMerger.merge("widget", List.of(local),
                List.of(remote), true).getFirst().coordinate().version());
    }

    @Test
    void mergedVersionsNeverCarryVisualMarkersIntoTheCoordinate() {
        DependencyCoordinate base = DependencyCoordinate.of("com.acme", "widget", "");
        DependencyVersionChoice choice = DependencySearchMerger.mergeVersions(
                List.of("5.0.0-SNAPSHOT"), List.of()).getFirst();

        assertTrue(choice.localOnly());
        assertEquals("com.acme:widget:5.0.0-SNAPSHOT",
                base.withVersion(choice.version()).notation());
    }
}
