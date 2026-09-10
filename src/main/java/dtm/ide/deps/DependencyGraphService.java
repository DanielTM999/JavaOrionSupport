package dtm.ide.deps;

import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.ProcessRunner;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.util.ArrayList;
import java.util.List;

public final class DependencyGraphService {

    public record Snapshot(List<ResolvedDependency> dependencies, boolean failed) {
        public Snapshot {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    private final JavaProjectDescriptor descriptor;
    private final BuildSystem buildSystem;

    public DependencyGraphService(JavaProjectDescriptor descriptor, BuildSystem buildSystem) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
    }

    public Snapshot resolve(JavaModule module) {
        if (descriptor == null || buildSystem == null) {
            return new Snapshot(List.of(), true);
        }
        List<String> goals;
        BuildCommand.Options options = BuildCommand.Options.none();
        if (descriptor.isMaven()) {
            goals = List.of("dependency:tree");
            options = options.withExtraArguments(List.of("-Dverbose"));
        } else if (descriptor.isGradle()) {
            goals = List.of("dependencies");
            options = options.withExtraArguments(List.of("--configuration", "runtimeClasspath"));
        } else {
            return new Snapshot(List.of(), true);
        }
        BuildCommand command = buildSystem.toolCommand(module, goals, options).orElse(null);
        if (command == null) {
            return new Snapshot(List.of(), true);
        }
        List<String> output = new ArrayList<>();
        int exit = new ProcessRunner().run(command.command(), command.workingDirectory(),
                command.environment(), output::add);
        List<ResolvedDependency> parsed = descriptor.isMaven()
                ? DependencyGraphParser.parseMaven(output)
                : DependencyGraphParser.parseGradle(output);
        return new Snapshot(parsed, exit != 0 || parsed.isEmpty());
    }
}
