package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class WorkspaceModuleGraph {

    private final Map<String, JavaModule> byCoordinates;
    private final Map<Path, List<JavaModule>> dependencies;

    private WorkspaceModuleGraph(Map<String, JavaModule> byCoordinates,
                                 Map<Path, List<JavaModule>> dependencies) {
        this.byCoordinates = byCoordinates;
        this.dependencies = dependencies;
    }

    public static WorkspaceModuleGraph of(JavaProjectDescriptor descriptor) {
        Map<String, JavaModule> byCoordinates = new LinkedHashMap<>();
        if (descriptor != null) {
            for (JavaModule module : descriptor.modules()) {
                if (module == null || module.isAggregator()) {
                    continue;
                }
                byCoordinates.putIfAbsent(module.coordinates(), module);
                byCoordinates.putIfAbsent(module.artifactId(), module);
            }
        }
        Map<Path, List<JavaModule>> dependencies = new LinkedHashMap<>();
        for (JavaModule module : new LinkedHashSet<>(byCoordinates.values())) {
            dependencies.put(module.root(), dependenciesOf(module, byCoordinates));
        }
        return new WorkspaceModuleGraph(byCoordinates, dependencies);
    }

    private static List<JavaModule> dependenciesOf(JavaModule module,
                                                   Map<String, JavaModule> byCoordinates) {
        Path pom = module.root().resolve(JavaProjectConventions.POM_FILE);
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }
        MavenPom parsed = MavenPom.parse(pom);
        if (!parsed.isValid()) {
            return List.of();
        }
        List<JavaModule> resolved = new ArrayList<>();
        for (List<String> entry : parsed.entries("dependencies", "dependency",
                "groupId", "artifactId")) {
            JavaModule found = lookup(byCoordinates, entry.get(0), entry.get(1));
            if (found != null && !found.root().equals(module.root()) && !resolved.contains(found)) {
                resolved.add(found);
            }
        }
        return List.copyOf(resolved);
    }

    private static JavaModule lookup(Map<String, JavaModule> byCoordinates, String groupId,
                                     String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return null;
        }
        if (groupId != null && !groupId.isBlank()) {
            JavaModule exact = byCoordinates.get(groupId.trim() + ":" + artifactId.trim());
            if (exact != null) {
                return exact;
            }
        }
        return byCoordinates.get(artifactId.trim());
    }

    public List<JavaModule> buildOrderFor(JavaModule target) {
        if (target == null) {
            return List.of();
        }
        List<JavaModule> order = new ArrayList<>();
        Set<Path> done = new LinkedHashSet<>();
        if (target.isAggregator()) {
            for (JavaModule module : new LinkedHashSet<>(byCoordinates.values())) {
                visit(module, new LinkedHashSet<>(), done, order);
            }
        } else {
            visit(target, new LinkedHashSet<>(), done, order);
        }
        if (order.isEmpty()) {
            order.add(target);
        }
        return List.copyOf(order);
    }

    private void visit(JavaModule module, Set<Path> visiting, Set<Path> done,
                       List<JavaModule> order) {
        if (done.contains(module.root())) {
            return;
        }
        if (!visiting.add(module.root())) {
            log.warn("Ciclo entre modulos do workspace em {}", module.coordinates());
            return;
        }
        for (JavaModule dependency : dependencies.getOrDefault(module.root(), List.of())) {
            visit(dependency, visiting, done, order);
        }
        visiting.remove(module.root());
        done.add(module.root());
        order.add(module);
    }

    public boolean isEmpty() {
        return byCoordinates.isEmpty();
    }
}
