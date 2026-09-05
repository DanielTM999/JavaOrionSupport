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
        Path wrapper
) {

    public JavaProjectDescriptor {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(kind, "kind");
        root = root.toAbsolutePath().normalize();
        modules = modules == null ? List.of() : List.copyOf(modules);
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
}
