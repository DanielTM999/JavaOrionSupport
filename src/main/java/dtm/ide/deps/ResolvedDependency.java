package dtm.ide.deps;

import java.util.List;

public record ResolvedDependency(DependencyCoordinate coordinate, int depth, List<String> path,
                                 boolean conflict, String requestedVersion) {

    public ResolvedDependency {
        depth = Math.max(0, depth);
        path = path == null ? List.of() : List.copyOf(path);
        requestedVersion = requestedVersion == null ? "" : requestedVersion.trim();
    }

    public boolean transitive() {
        return depth > 0;
    }
}
