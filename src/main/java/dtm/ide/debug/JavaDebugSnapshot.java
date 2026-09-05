package dtm.ide.debug;

import java.nio.file.Path;
import java.util.List;

public record JavaDebugSnapshot(
        State state,
        String message,
        int threadId,
        List<ThreadInfo> threads,
        List<StackFrame> frames,
        List<Variable> variables,
        List<Scope> scopes
) {
    public enum State {
        STARTING, RUNNING, PAUSED, TERMINATED, ERROR
    }

    public record ThreadInfo(int id, String name) {
    }

    public record StackFrame(int id, String name, Path source, int line) {
    }

    public record Scope(String name, int variablesReference, List<Variable> variables) {
        public Scope {
            name = name == null || name.isBlank() ? "Scope" : name;
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    public record Variable(String name, String value, String type, String evaluateName,
                           int variablesReference, int namedVariables, int indexedVariables) {
        public Variable(String name, String value, String type, int variablesReference) {
            this(name, value, type, name, variablesReference, 0, 0);
        }

        public boolean expandable() {
            return variablesReference > 0;
        }

        @Override
        public String toString() {
            String suffix = type == null || type.isBlank() ? "" : " : " + type;
            return name + " = " + value + suffix;
        }
    }

    public JavaDebugSnapshot {
        message = message == null ? "" : message;
        threads = threads == null ? List.of() : List.copyOf(threads);
        frames = frames == null ? List.of() : List.copyOf(frames);
        variables = variables == null ? List.of() : List.copyOf(variables);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public JavaDebugSnapshot(State state, String message, int threadId, List<ThreadInfo> threads,
                             List<StackFrame> frames, List<Variable> variables) {
        this(state, message, threadId, threads, frames, variables, List.of());
    }

    public static JavaDebugSnapshot starting(String message) {
        return new JavaDebugSnapshot(State.STARTING, message, 0, List.of(), List.of(), List.of(),
                List.of());
    }
}
