package dtm.ide.build;

import dtm.ide.lsp.JdtLsService;
import dtm.ide.project.JavaModule;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class JavaDevelopmentBuildService {

    public enum State {
        SUCCESS, WITH_ERRORS, UNAVAILABLE, FAILED
    }

    public record Result(State state, Duration duration, String message) {
        public boolean successful() {
            return state == State.SUCCESS;
        }

        public boolean shouldFallback() {
            return state == State.UNAVAILABLE || state == State.FAILED;
        }
    }

    private final Supplier<JdtLsService> languageServer;

    public JavaDevelopmentBuildService(Supplier<JdtLsService> languageServer) {
        this.languageServer = languageServer;
    }

    public Result build(JavaModule module, boolean fullBuild, Consumer<String> output) {
        Instant started = Instant.now();
        JdtLsService server = languageServer == null ? null : languageServer.get();
        if (server == null || !server.isInteractive()) {
            return new Result(State.UNAVAILABLE, Duration.ZERO, "JDT LS ainda nao esta pronto");
        }
        emit(output, "> JDT incremental " + (fullBuild ? "rebuild" : "build")
                + (module == null ? "" : " " + module.name()));
        try {
            String status = server.buildWorkspace(fullBuild).toUpperCase(java.util.Locale.ROOT);
            Duration duration = Duration.between(started, Instant.now());
            if (status.contains("SUCCEED")) {
                emit(output, "Build incremental concluido em " + duration.toMillis() + " ms");
                return new Result(State.SUCCESS, duration, status);
            }
            if (status.contains("WITH_ERROR")) {
                emit(output, "Build incremental encontrou erros de compilacao");
                return new Result(State.WITH_ERRORS, duration, status);
            }
            return new Result(State.FAILED, duration, status);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            emit(output, "JDT incremental indisponivel: " + message);
            return new Result(State.FAILED, Duration.between(started, Instant.now()), message);
        }
    }

    public Optional<String> runtimeClasspath(JavaModule module) {
        JdtLsService server = languageServer == null ? null : languageServer.get();
        return server == null || module == null ? Optional.empty()
                : server.runtimeClasspath(module.root());
    }

    private static void emit(Consumer<String> output, String message) {
        if (output != null) {
            output.accept(message);
        }
    }
}
