package dtm.ide.debug;

import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaModule;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class JavaHotReloadService {

    public enum Result {
        APPLIED,
        NO_SESSION,
        BUILD_FAILED,
        STRUCTURAL_CHANGE,
        FAILED
    }

    private final Supplier<JavaProjectDescriptor> descriptor;
    private final Supplier<BuildSystem> buildSystem;
    private final Supplier<JavaDebugSession> debugSession;
    private final Supplier<JavaModule> debugModule;

    public JavaHotReloadService(Supplier<JavaProjectDescriptor> descriptor,
                                Supplier<BuildSystem> buildSystem,
                                Supplier<JavaDebugSession> debugSession,
                                Supplier<JavaModule> debugModule) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
        this.debugSession = debugSession;
        this.debugModule = debugModule;
    }

    public Result reload(Consumer<String> output) {
        JavaDebugSession session = debugSession.get();
        JavaProjectDescriptor project = descriptor.get();
        BuildSystem build = buildSystem.get();
        if (session == null || session.snapshot().state() == JavaDebugSnapshot.State.TERMINATED) {
            return Result.NO_SESSION;
        }
        if (project == null || build == null) {
            return Result.BUILD_FAILED;
        }
        JavaModule module = debugModule.get();
        BuildResult result = build.execute(BuildRequest.of(BuildSystem.BuildAction.COMPILE,
                module == null ? project.rootModule() : module).withSkipTests(true), output);
        if (!result.successful()) {
            return Result.BUILD_FAILED;
        }
        return redefine(session);
    }

    private Result redefine(JavaDebugSession session) {
        try {
            session.redefineClasses().get(20, TimeUnit.SECONDS);
            return Result.APPLIED;
        } catch (Exception error) {
            String message = rootMessage(error).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("schema") || message.contains("structural")
                    || message.contains("add method") || message.contains("delete method")
                    || message.contains("signature")) {
                return Result.STRUCTURAL_CHANGE;
            }
            return Result.FAILED;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
