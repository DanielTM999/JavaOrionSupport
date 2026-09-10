package dtm.ide.deps;

import java.util.List;

public record DependencyInventorySnapshot(
        List<ManagedDependency> declared,
        List<ResolvedDependency> graph,
        boolean resolutionFailed
) {

    public DependencyInventorySnapshot {
        declared = declared == null ? List.of() : List.copyOf(declared);
        graph = graph == null ? List.of() : List.copyOf(graph);
    }

    public static DependencyInventorySnapshot empty() {
        return new DependencyInventorySnapshot(List.of(), List.of(), false);
    }

    public ManagedDependency dependency(String key) {
        if (key == null) {
            return null;
        }
        return declared.stream().filter(item -> key.equals(item.key())).findFirst().orElse(null);
    }

    public List<DependencyCoordinate> effectiveDeclared() {
        return declared.stream()
                .map(item -> item.effective() == null ? item.declared() : item.effective())
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
