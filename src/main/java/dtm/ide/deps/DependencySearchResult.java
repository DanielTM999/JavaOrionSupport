package dtm.ide.deps;

import java.nio.file.Path;
import java.util.List;

public record DependencySearchResult(
        DependencyCoordinate coordinate,
        int versionCount,
        long lastUpdated,
        boolean local,
        boolean remote,
        Path localRepository,
        List<String> localVersions
) {

    public DependencySearchResult {
        versionCount = Math.max(0, versionCount);
        localRepository = localRepository == null
                ? null : localRepository.toAbsolutePath().normalize();
        localVersions = localVersions == null ? List.of() : List.copyOf(localVersions);
    }
}
