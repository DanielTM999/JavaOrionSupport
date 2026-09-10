package dtm.ide.deps;

import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.MavenPom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DependencyInventoryService {

    private static final Pattern PROPERTY = Pattern.compile("^\\$\\{([^}]+)}$");
    private static final Pattern PARENT_BLOCK = Pattern.compile("(?s)<parent\\s*>(.*?)</parent\\s*>");
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "(?s)<relativePath(?:\\s*/>|\\s*>(.*?)</relativePath\\s*>)");

    private final JavaProjectDescriptor descriptor;
    private final BuildSystem buildSystem;

    public DependencyInventoryService(JavaProjectDescriptor descriptor, BuildSystem buildSystem) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
    }

    public DependencyInventorySnapshot resolve(JavaModule module,
                                                List<DependencyCoordinate> declared) {
        List<DependencyCoordinate> source = declared == null ? List.of() : List.copyOf(declared);
        DependencyGraphService.Snapshot graph =
                new DependencyGraphService(descriptor, buildSystem).resolve(module);
        return resolve(module, source, graph);
    }

    DependencyInventorySnapshot resolve(JavaModule module,
                                        List<DependencyCoordinate> declared,
                                        DependencyGraphService.Snapshot graph) {
        List<DependencyCoordinate> source = declared == null ? List.of() : List.copyOf(declared);
        DependencyGraphService.Snapshot resolvedGraph = graph == null
                ? new DependencyGraphService.Snapshot(List.of(), true) : graph;
        Map<String, ResolvedDependency> direct = new LinkedHashMap<>();
        Map<String, ResolvedDependency> any = new LinkedHashMap<>();
        for (ResolvedDependency dependency : resolvedGraph.dependencies()) {
            if (dependency.coordinate() == null) {
                continue;
            }
            any.putIfAbsent(dependency.coordinate().key(), dependency);
            if (!dependency.transitive()) {
                direct.putIfAbsent(dependency.coordinate().key(), dependency);
            }
        }

        List<Path> pomChain = descriptor != null && descriptor.isMaven()
                ? localPomChain(module) : List.of();
        List<ManagedDependency> inventory = new ArrayList<>();
        for (DependencyCoordinate coordinate : source) {
            ResolvedDependency resolved = direct.get(coordinate.key());
            if (resolved == null) {
                resolved = any.get(coordinate.key());
            }
            String version = resolved == null ? fallbackVersion(coordinate)
                    : resolved.coordinate().version();
            DependencyCoordinate effective = new DependencyCoordinate(coordinate.groupId(),
                    coordinate.artifactId(), version, coordinate.scope());
            Origin origin = originOf(coordinate, module, pomChain);
            inventory.add(new ManagedDependency(coordinate, effective, origin.kind(),
                    origin.file(), origin.propertyName()));
        }
        return new DependencyInventorySnapshot(inventory, resolvedGraph.dependencies(),
                resolvedGraph.failed());
    }

    private Origin originOf(DependencyCoordinate coordinate, JavaModule module,
                            List<Path> pomChain) {
        Path buildFile = buildFile(module);
        if (descriptor == null || !descriptor.isMaven()) {
            return ManagedDependency.isPlaceholder(coordinate.version())
                    ? new Origin(DependencyVersionOrigin.EXTERNAL_MANAGEMENT, null, "")
                    : new Origin(DependencyVersionOrigin.DIRECT, buildFile, "");
        }
        Matcher property = PROPERTY.matcher(coordinate.version());
        if (property.matches()) {
            return propertyOrigin(property.group(1), pomChain);
        }
        if (!coordinate.version().isBlank()) {
            return new Origin(DependencyVersionOrigin.DIRECT, buildFile, "");
        }
        for (Path pom : pomChain) {
            String managed = PomEditor.managedVersion(
                    JavaProjectConventions.readOrEmpty(pom), coordinate);
            if (managed.isBlank()) {
                continue;
            }
            Matcher managedProperty = PROPERTY.matcher(managed);
            if (managedProperty.matches()) {
                Origin propertyOrigin = propertyOrigin(managedProperty.group(1), pomChainFrom(pomChain, pom));
                return propertyOrigin.kind() == DependencyVersionOrigin.LOCAL_PROPERTY
                        ? propertyOrigin
                        : new Origin(DependencyVersionOrigin.EXTERNAL_MANAGEMENT, null, "");
            }
            return new Origin(DependencyVersionOrigin.LOCAL_MANAGEMENT, pom, "");
        }
        return new Origin(DependencyVersionOrigin.EXTERNAL_MANAGEMENT, null, "");
    }

    private static Origin propertyOrigin(String name, List<Path> pomChain) {
        for (Path pom : pomChain) {
            if (PomEditor.hasProperty(JavaProjectConventions.readOrEmpty(pom), name)) {
                return new Origin(DependencyVersionOrigin.LOCAL_PROPERTY, pom, name);
            }
        }
        return new Origin(DependencyVersionOrigin.EXTERNAL_MANAGEMENT, null, name);
    }

    private List<Path> localPomChain(JavaModule module) {
        Path root = descriptor == null ? null : descriptor.root();
        Path current = buildFile(module);
        List<Path> result = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        while (current != null && Files.isRegularFile(current) && visited.add(current)) {
            Path normalized = current.toAbsolutePath().normalize();
            if (root != null && !normalized.startsWith(root.toAbsolutePath().normalize())) {
                break;
            }
            result.add(normalized);
            current = localParentPom(normalized);
        }
        return List.copyOf(result);
    }

    private static List<Path> pomChainFrom(List<Path> chain, Path start) {
        int index = chain.indexOf(start);
        return index < 0 ? chain : chain.subList(index, chain.size());
    }

    private static Path localParentPom(Path pom) {
        String content = JavaProjectConventions.readOrEmpty(pom);
        MavenPom parsed = MavenPom.parseContent(content);
        if (!parsed.isValid() || parsed.value("parent", "artifactId").isBlank()) {
            return null;
        }
        Matcher parent = PARENT_BLOCK.matcher(content);
        String relative = "../pom.xml";
        if (parent.find()) {
            Matcher configured = RELATIVE_PATH.matcher(parent.group(1));
            if (configured.find()) {
                String value = configured.group(1);
                if (value == null || value.isBlank()) {
                    return null;
                }
                relative = value.trim();
            }
        }
        Path parentDirectory = pom.getParent();
        return parentDirectory == null ? null
                : parentDirectory.resolve(relative).toAbsolutePath().normalize();
    }

    private Path buildFile(JavaModule module) {
        if (module == null || descriptor == null) {
            return null;
        }
        if (descriptor.isMaven()) {
            return module.root().resolve(JavaProjectConventions.POM_FILE).toAbsolutePath().normalize();
        }
        if (descriptor.isGradle()) {
            Path file = JavaProjectConventions.gradleBuildFile(module.root());
            return file == null ? null : file.toAbsolutePath().normalize();
        }
        return null;
    }

    private static String fallbackVersion(DependencyCoordinate coordinate) {
        return coordinate == null || ManagedDependency.isPlaceholder(coordinate.version())
                ? "" : coordinate.version();
    }

    private record Origin(DependencyVersionOrigin kind, Path file, String propertyName) {
    }
}
