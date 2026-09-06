package dtm.ide.run;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Stops an application gracefully on the first request and forcefully terminates its complete
 * process tree on subsequent requests.
 */
@Slf4j
final class TwoStageProcessTerminator {

    private final ProcessHandle root;
    private final BooleanSupplier alive;
    private final Runnable gracefulStop;
    private final Runnable forceStop;
    private final AtomicBoolean gracefulStopRequested = new AtomicBoolean();

    TwoStageProcessTerminator(Process process) {
        this(handleOf(process), process::isAlive, process::destroy, process::destroyForcibly);
    }

    TwoStageProcessTerminator(ProcessHandle root, BooleanSupplier alive, Runnable gracefulStop) {
        this(root, alive, gracefulStop, () -> { });
    }

    private TwoStageProcessTerminator(ProcessHandle root, BooleanSupplier alive,
                                      Runnable gracefulStop, Runnable forceStop) {
        this.root = root;
        this.alive = alive;
        this.gracefulStop = gracefulStop;
        this.forceStop = forceStop;
    }

    boolean isAlive() {
        return alive.getAsBoolean();
    }

    void terminate() {
        if (!isAlive()) {
            return;
        }
        if (gracefulStopRequested.compareAndSet(false, true)) {
            try {
                gracefulStop.run();
            } catch (RuntimeException error) {
                log.debug("Falha ao solicitar a parada normal do processo {}: {}",
                        rootPid(), error.getMessage());
            }
            return;
        }
        if (root == null) {
            try {
                forceStop.run();
            } catch (RuntimeException error) {
                log.debug("Falha ao finalizar o processo a forca: {}", error.getMessage());
            }
            return;
        }
        forceTerminateTree(root);
    }

    private static ProcessHandle handleOf(Process process) {
        try {
            return process.toHandle();
        } catch (RuntimeException error) {
            log.debug("Processo sem ProcessHandle direto ({}); tentando resolver pelo pid.",
                    error.toString());
        }
        try {
            return ProcessHandle.of(process.pid()).orElse(null);
        } catch (RuntimeException error) {
            log.debug("Processo sem pid utilizavel ({}); a arvore nao sera encerrada.",
                    error.toString());
            return null;
        }
    }

    private String rootPid() {
        try {
            return root == null ? "?" : String.valueOf(root.pid());
        } catch (RuntimeException error) {
            return "?";
        }
    }

    private static void forceTerminateTree(ProcessHandle process) {
        List<ProcessHandle> children;
        try {
            children = process.children().toList();
        } catch (RuntimeException error) {
            children = List.of();
        }
        for (ProcessHandle child : children) {
            forceTerminateTree(child);
        }
        if (process.isAlive()) {
            try {
                process.destroyForcibly();
            } catch (RuntimeException error) {
                log.debug("Falha ao finalizar processo {} a forca: {}",
                        process.pid(), error.getMessage());
            }
        }
    }
}
