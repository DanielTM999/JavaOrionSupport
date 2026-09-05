package dtm.ide.build;

import java.time.Duration;
import java.util.List;

public record BuildResult(
        int exitCode,
        List<BuildDiagnostic> diagnostics,
        Duration duration,
        String command
) {

    public BuildResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        duration = duration == null ? Duration.ZERO : duration;
        command = command == null ? "" : command;
    }

    public boolean successful() {
        return exitCode == 0;
    }

    public List<BuildDiagnostic> errors() {
        return diagnostics.stream().filter(BuildDiagnostic::isError).toList();
    }

    public String summary() {
        long seconds = Math.max(0, duration.toMillis()) / 1000;
        if (successful()) {
            return "build ok (" + seconds + "s)";
        }
        int errorCount = errors().size();
        return errorCount > 0
                ? "build falhou: " + errorCount + " erro(s) (" + seconds + "s)"
                : "build falhou com codigo " + exitCode + " (" + seconds + "s)";
    }

    static BuildResult failed(String command, String reason) {
        return new BuildResult(-1,
                List.of(new BuildDiagnostic(null, 0, 0, null, reason, "build")),
                Duration.ZERO, command);
    }
}
