package dtm.ide.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JavaProjectDescriptor(
        Path root,
        JavaProjectKind kind,
        List<JavaModule> modules,
        boolean springBoot,
        boolean spring,
        Integer jdkVersion,
        Path wrapper,
        List<Path> buildRoots
) {

    public JavaProjectDescriptor {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(kind, "kind");
        root = root.toAbsolutePath().normalize();
        modules = modules == null ? List.of() : List.copyOf(modules);
        buildRoots = buildRoots == null ? List.of()
                : buildRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public JavaProjectDescriptor(Path root, JavaProjectKind kind, List<JavaModule> modules,
                                 boolean springBoot, boolean spring, Integer jdkVersion, Path wrapper) {
        this(root, kind, modules, springBoot, spring, jdkVersion, wrapper, List.of());
    }

    public JavaModule rootModule() {
        return modules.stream()
                .filter(module -> module.root().equals(root))
                .findFirst()
                .orElseGet(() -> modules.isEmpty() ? null : modules.getFirst());
    }

    public Optional<JavaModule> moduleOf(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        Path normalized = path.toAbsolutePath().normalize();
        return modules.stream()
                .filter(module -> normalized.startsWith(module.root()))
                .max((a, b) -> Integer.compare(a.root().getNameCount(), b.root().getNameCount()));
    }

    public List<JavaModule> buildableModules() {
        return modules.stream().filter(module -> !module.isAggregator()).toList();
    }

    public Optional<Integer> jdkMajor() {
        return Optional.ofNullable(jdkVersion);
    }

    public Optional<Path> wrapperPath() {
        return Optional.ofNullable(wrapper);
    }

    public boolean isMaven() {
        return kind.isMaven();
    }

    public boolean isGradle() {
        return kind.isGradle();
    }

    public boolean isMavenWorkspace() {
        return kind == JavaProjectKind.MAVEN_WORKSPACE;
    }

    public Path buildRootOf(JavaModule module) {
        if (!isMavenWorkspace() || module == null) {
            return root;
        }
        Path moduleRoot = module.root().toAbsolutePath().normalize();
        return buildRoots.stream()
                .filter(moduleRoot::startsWith)
                .max((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()))
                .orElse(moduleRoot);
    }

    public List<Path> projectRoots() {
        return isMavenWorkspace() && !buildRoots.isEmpty() ? buildRoots : List.of(root);
    }
}
