package dtm.ide.build;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.project.JavaModule;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface BuildSystem {

    enum BuildAction {
        COMPILE,
        TEST_COMPILE,
        REBUILD,
        CLEAN,
        TEST,
        PACKAGE,
        INSTALL
    }

    String name();

    BuildResult execute(BuildRequest request, Consumer<String> output);

    void cancel();

    boolean isRunning();

    Optional<String> resolveRuntimeClasspath(JavaModule module);

    default Optional<String> resolveTestClasspath(JavaModule module) {
        return resolveRuntimeClasspath(module);
    }

    default Optional<String> resolveCompileClasspath(JavaModule module) {
        return resolveRuntimeClasspath(module);
    }

    default BuildResult refreshDependencies(JavaModule module, DependencyCoordinate dependency,
                                            Consumer<String> output) {
        return BuildResult.failed(name(), "Atualizacao forcada de dependencias nao suportada.");
    }

    void invalidateClasspathCache();

    default Optional<String> lastClasspathFailure(JavaModule module) {
        return Optional.empty();
    }

    default BuildResult executeToolCommand(JavaModule module, List<String> command,
                                           Consumer<String> output) {
        return BuildResult.failed(name(), "Comando de build nao suportado.");
    }

    default BuildResult executeToolCommand(JavaModule module, List<String> command,
                                           BuildCommand.Options options, Consumer<String> output) {
        return executeToolCommand(module, command, output);
    }

    default Optional<BuildCommand> toolCommand(JavaModule module, List<String> goals,
                                               BuildCommand.Options options) {
        return Optional.empty();
    }
}
