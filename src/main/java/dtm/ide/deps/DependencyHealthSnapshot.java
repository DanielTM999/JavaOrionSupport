package dtm.ide.deps;

import java.util.List;
import java.util.Map;

public record DependencyHealthSnapshot(
        List<ResolvedDependency> dependencies,
        Map<String, List<DependencyVulnerability>> vulnerabilities,
        boolean graphFailed,
        boolean vulnerabilityLookupFailed
) {

    public DependencyHealthSnapshot {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        vulnerabilities = vulnerabilities == null ? Map.of() : Map.copyOf(vulnerabilities);
    }

    public static DependencyHealthSnapshot empty() {
        return new DependencyHealthSnapshot(List.of(), Map.of(), false, false);
    }

    public List<DependencyVulnerability> vulnerabilitiesOf(DependencyCoordinate coordinate) {
        return coordinate == null ? List.of()
                : vulnerabilities.getOrDefault(coordinate.key(), List.of());
    }
}
