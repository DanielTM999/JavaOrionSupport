package dtm.ide.run;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProcessSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Termination termination
) {

    public enum Termination {
        TWO_STAGE_TREE,
        PROCESS_ONLY
    }

    public ProcessSpec {
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(environment));
        termination = termination == null ? Termination.TWO_STAGE_TREE : termination;
    }

    public static ProcessSpec of(List<String> command, Path workingDirectory,
                                 Map<String, String> environment) {
        return new ProcessSpec(command, workingDirectory, environment,
                Termination.TWO_STAGE_TREE);
    }

    public ProcessSpec withTermination(Termination policy) {
        return new ProcessSpec(command, workingDirectory, environment, policy);
    }

    public ProcessSpec withArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return this;
        }
        List<String> merged = new java.util.ArrayList<>(command);
        merged.addAll(arguments);
        return new ProcessSpec(merged, workingDirectory, environment, termination);
    }

    public ProcessSpec withEnvironment(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(environment);
        merged.putAll(extra);
        return new ProcessSpec(command, workingDirectory, merged, termination);
    }

    public boolean isEmpty() {
        return command.isEmpty();
    }

    public String display() {
        return String.join(" ", command);
    }
}
