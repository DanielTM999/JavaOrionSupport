package dtm.ide.deps;

import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DependencyHealthService {

    private final JavaProjectDescriptor descriptor;
    private final BuildSystem buildSystem;
    private final OsvClient osv;

    public DependencyHealthService(JavaProjectDescriptor descriptor, BuildSystem buildSystem,
                                   OsvClient osv) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
        this.osv = osv == null ? new OsvClient() : osv;
    }

    public DependencyHealthSnapshot analyze(JavaModule module,
                                            List<DependencyCoordinate> declared) {
        DependencyGraphService.Snapshot graph =
                new DependencyGraphService(descriptor, buildSystem).resolve(module);
        List<ResolvedDependency> dependencies = graph.dependencies().isEmpty()
                ? directDependencies(declared) : graph.dependencies();
        return analyze(dependencies, declared, graph.failed());
    }

    public DependencyHealthSnapshot analyze(DependencyInventorySnapshot inventory) {
        DependencyInventorySnapshot source = inventory == null
                ? DependencyInventorySnapshot.empty() : inventory;
        List<ResolvedDependency> dependencies = source.graph().isEmpty()
                ? directDependencies(source.effectiveDeclared()) : source.graph();
        return analyze(dependencies, source.effectiveDeclared(), source.resolutionFailed());
    }

    private DependencyHealthSnapshot analyze(List<ResolvedDependency> dependencies,
                                             List<DependencyCoordinate> declared,
                                             boolean graphFailed) {
        Map<String, DependencyCoordinate> queryable = new LinkedHashMap<>();
        for (ResolvedDependency dependency : dependencies) {
            DependencyCoordinate coordinate = dependency.coordinate();
            if (coordinate != null && coordinate.hasVersion()) {
                queryable.putIfAbsent(coordinate.notation(), coordinate);
            }
        }
        if (declared != null) {
            for (DependencyCoordinate coordinate : declared) {
                if (coordinate != null && coordinate.hasVersion()) {
                    queryable.putIfAbsent(coordinate.notation(), coordinate);
                }
            }
        }
        OsvClient.Result vulnerabilities = osv.query(List.copyOf(queryable.values()));
        return new DependencyHealthSnapshot(dependencies, vulnerabilities.vulnerabilities(),
                graphFailed, vulnerabilities.failed());
    }

    private static List<ResolvedDependency> directDependencies(
            List<DependencyCoordinate> declared) {
        if (declared == null) {
            return List.of();
        }
        return declared.stream().filter(coordinate -> coordinate != null && coordinate.isValid())
                .map(coordinate -> new ResolvedDependency(coordinate, 0,
                        List.of(coordinate.key()), false, coordinate.version()))
                .toList();
    }
}
