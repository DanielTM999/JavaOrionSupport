package dtm.ide.build;

import dtm.ide.project.JavaModule;

import java.util.List;
import java.util.Map;

public record BuildRequest(
        BuildSystem.BuildAction action,
        JavaModule module,
        List<JavaModule> modules,
        List<String> extraArguments,
        List<String> profiles,
        Map<String, String> environment,
        boolean offline,
        boolean skipTests,
        boolean alsoMake
) {

    public BuildRequest {
        action = action == null ? BuildSystem.BuildAction.COMPILE : action;
        modules = modules == null ? List.of() : List.copyOf(modules);
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public static BuildRequest of(BuildSystem.BuildAction action) {
        return new BuildRequest(action, null, List.of(), List.of(), List.of(), Map.of(),
                false, false, true);
    }

    public static BuildRequest of(BuildSystem.BuildAction action, JavaModule module) {
        return new BuildRequest(action, module, List.of(), List.of(), List.of(), Map.of(),
                false, false, true);
    }

    public BuildRequest withModule(JavaModule target) {
        return new BuildRequest(action, target, modules, extraArguments, profiles, environment,
                offline, skipTests, alsoMake);
    }

    public BuildRequest withModules(List<JavaModule> selection) {
        return new BuildRequest(action, module, selection, extraArguments, profiles, environment,
                offline, skipTests, alsoMake);
    }

    public BuildRequest withArguments(List<String> arguments) {
        return new BuildRequest(action, module, modules, arguments, profiles, environment,
                offline, skipTests, alsoMake);
    }

    public BuildRequest withProfiles(List<String> activeProfiles) {
        return new BuildRequest(action, module, modules, extraArguments, activeProfiles, environment,
                offline, skipTests, alsoMake);
    }

    public BuildRequest withSkipTests(boolean skip) {
        return new BuildRequest(action, module, modules, extraArguments, profiles, environment,
                offline, skip, alsoMake);
    }

    public BuildRequest withOffline(boolean value) {
        return new BuildRequest(action, module, modules, extraArguments, profiles, environment,
                value, skipTests, alsoMake);
    }

    public BuildRequest withAlsoMake(boolean value) {
        return new BuildRequest(action, module, modules, extraArguments, profiles, environment,
                offline, skipTests, value);
    }

    public List<JavaModule> selection() {
        if (!modules.isEmpty()) {
            return modules;
        }
        return module == null ? List.of() : List.of(module);
    }
}
