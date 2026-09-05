package dtm.ide.build;

import dtm.ide.project.JavaModule;

import java.util.List;
import java.util.Map;

public record BuildRequest(
        BuildSystem.BuildAction action,
        JavaModule module,
        List<String> extraArguments,
        List<String> profiles,
        Map<String, String> environment,
        boolean offline,
        boolean skipTests
) {

    public BuildRequest {
        action = action == null ? BuildSystem.BuildAction.COMPILE : action;
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public static BuildRequest of(BuildSystem.BuildAction action) {
        return new BuildRequest(action, null, List.of(), List.of(), Map.of(), false, false);
    }

    public static BuildRequest of(BuildSystem.BuildAction action, JavaModule module) {
        return new BuildRequest(action, module, List.of(), List.of(), Map.of(), false, false);
    }

    public BuildRequest withModule(JavaModule target) {
        return new BuildRequest(action, target, extraArguments, profiles, environment,
                offline, skipTests);
    }

    public BuildRequest withArguments(List<String> arguments) {
        return new BuildRequest(action, module, arguments, profiles, environment,
                offline, skipTests);
    }

    public BuildRequest withProfiles(List<String> activeProfiles) {
        return new BuildRequest(action, module, extraArguments, activeProfiles, environment,
                offline, skipTests);
    }

    public BuildRequest withSkipTests(boolean skip) {
        return new BuildRequest(action, module, extraArguments, profiles, environment,
                offline, skip);
    }

    public BuildRequest withOffline(boolean value) {
        return new BuildRequest(action, module, extraArguments, profiles, environment,
                value, skipTests);
    }
}
