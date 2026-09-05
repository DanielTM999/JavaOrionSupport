package dtm.ide.deps;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
public class DependencyService {

    private final JavaProjectDescriptor descriptor;

    public DependencyService(JavaProjectDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public boolean isSupported() {
        return descriptor != null && descriptor.kind().hasBuildTool();
    }

    public Optional<Path> buildFileOf(JavaModule module) {
        JavaModule target = moduleOrRoot(module);
        if (target == null || descriptor == null) {
            return Optional.empty();
        }
        if (descriptor.isMaven()) {
            Path pom = target.root().resolve(JavaProjectConventions.POM_FILE);
            return Files.isRegularFile(pom) ? Optional.of(pom) : Optional.empty();
        }
        if (descriptor.isGradle()) {
            Path script = JavaProjectConventions.gradleBuildFile(target.root());
            return Files.isRegularFile(script) ? Optional.of(script) : Optional.empty();
        }
        return Optional.empty();
    }

    public List<DependencyCoordinate> declaredDependencies(JavaModule module) {
        Optional<Path> buildFile = buildFileOf(module);
        if (buildFile.isEmpty()) {
            return List.of();
        }
        String content = JavaProjectConventions.readOrEmpty(buildFile.get());
        return descriptor.isMaven()
                ? PomEditor.readDependencies(content)
                : GradleDependencyEditor.readDependencies(content);
    }

    public boolean add(JavaModule module, DependencyCoordinate coordinate) {
        return rewrite(module, content -> descriptor.isMaven()
                ? PomEditor.addDependency(content, coordinate)
                : GradleDependencyEditor.addDependency(content, coordinate));
    }

    public boolean remove(JavaModule module, DependencyCoordinate coordinate) {
        return rewrite(module, content -> descriptor.isMaven()
                ? PomEditor.removeDependency(content, coordinate)
                : GradleDependencyEditor.removeDependency(content, coordinate));
    }

    public boolean updateVersion(JavaModule module, DependencyCoordinate coordinate, String version) {
        return rewrite(module, content -> descriptor.isMaven()
                ? PomEditor.setVersion(content, coordinate, version)
                : GradleDependencyEditor.setVersion(content, coordinate, version));
    }

    private boolean rewrite(JavaModule module, java.util.function.UnaryOperator<String> transform) {
        Optional<Path> buildFile = buildFileOf(module);
        if (buildFile.isEmpty()) {
            return false;
        }
        Path file = buildFile.get();
        try {
            String original = Files.readString(file);
            String updated = transform.apply(original);
            if (updated == null || updated.equals(original)) {
                return false;
            }
            Files.writeString(file, updated);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao editar {}: {}", file, e.getMessage());
            return false;
        }
    }

    private JavaModule moduleOrRoot(JavaModule module) {
        if (module != null) {
            return module;
        }
        return descriptor == null ? null : descriptor.rootModule();
    }
}
