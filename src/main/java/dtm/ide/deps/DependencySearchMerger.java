package dtm.ide.deps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DependencySearchMerger {

    private DependencySearchMerger() {
    }

    public static List<DependencySearchResult> merge(
            String query, List<MavenLocalRepositoryCatalog.LocalArtifact> local,
            List<MavenCentralClient.SearchResult> remote, boolean includePreReleases) {
        Map<String, Builder> merged = new LinkedHashMap<>();
        for (MavenCentralClient.SearchResult result : safe(remote)) {
            if (result.coordinate() != null) {
                merged.computeIfAbsent(result.coordinate().key(), ignored -> new Builder())
                        .remote = result;
            }
        }
        for (MavenLocalRepositoryCatalog.LocalArtifact result : safe(local)) {
            if (result.coordinate() != null) {
                merged.computeIfAbsent(result.coordinate().key(), ignored -> new Builder())
                        .local = result;
            }
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return merged.values().stream().map(builder -> builder.build(includePreReleases))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(
                                (DependencySearchResult result) -> relevance(result, needle))
                        .thenComparing(result -> result.local() ? 0 : 1)
                        .thenComparing(result -> result.coordinate().key()))
                .limit(60).toList();
    }

    public static List<DependencyVersionChoice> mergeVersions(List<String> local,
                                                               List<String> remote) {
        Map<String, VersionSources> versions = new LinkedHashMap<>();
        safe(local).forEach(version -> versions.computeIfAbsent(version,
                ignored -> new VersionSources()).local = true);
        safe(remote).forEach(version -> versions.computeIfAbsent(version,
                ignored -> new VersionSources()).remote = true);
        return versions.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Map.Entry.comparingByKey(MavenVersionOrder.DESCENDING))
                .map(entry -> new DependencyVersionChoice(entry.getKey(),
                        entry.getValue().local, entry.getValue().remote))
                .toList();
    }

    private static int relevance(DependencySearchResult result, String needle) {
        String key = result.coordinate().key().toLowerCase(Locale.ROOT);
        String artifact = result.coordinate().artifactId().toLowerCase(Locale.ROOT);
        if (key.equals(needle)) return 0;
        if (artifact.equals(needle)) return 1;
        if (artifact.startsWith(needle)) return 2;
        if (key.startsWith(needle)) return 3;
        if (artifact.contains(needle)) return 4;
        return 5;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static final class Builder {
        private MavenCentralClient.SearchResult remote;
        private MavenLocalRepositoryCatalog.LocalArtifact local;

        private DependencySearchResult build(boolean includePreReleases) {
            DependencyCoordinate remoteCoordinate = remote == null ? null : remote.coordinate();
            DependencyCoordinate localCoordinate = local == null ? null : local.coordinate();
            DependencyCoordinate base = remoteCoordinate == null ? localCoordinate : remoteCoordinate;
            if (base == null) {
                return null;
            }
            List<String> candidates = new ArrayList<>();
            if (remoteCoordinate != null && (includePreReleases
                    || MavenCentralClient.isStable(remoteCoordinate.version()))) {
                candidates.add(remoteCoordinate.version());
            }
            if (local != null) {
                candidates.addAll(local.versions());
            }
            candidates.removeIf(String::isBlank);
            candidates.sort(MavenVersionOrder.DESCENDING);
            String latest = candidates.isEmpty() ? base.version() : candidates.getFirst();
            int count = Math.max(remote == null ? 0 : remote.versionCount(),
                    local == null ? 0 : local.versions().size());
            long updated = Math.max(remote == null ? 0 : remote.lastUpdated(),
                    local == null ? 0 : local.lastUpdated());
            return new DependencySearchResult(base.withVersion(latest), count, updated,
                    local != null, remote != null, local == null ? null : local.repository(),
                    local == null ? List.of() : local.versions());
        }
    }

    private static final class VersionSources {
        private boolean local;
        private boolean remote;
    }
}
