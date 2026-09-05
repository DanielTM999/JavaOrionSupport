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
    private final AtomicBoolean gracefulStopRequested = new AtomicBoolean();

    TwoStageProcessTerminator(Process process) {
        this(process.toHandle(), process::isAlive, process::destroy);
    }

    TwoStageProcessTerminator(ProcessHandle root, BooleanSupplier alive, Runnable gracefulStop) {
        this.root = root;
        this.alive = alive;
        this.gracefulStop = gracefulStop;
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
                        root.pid(), error.getMessage());
            }
            return;
        }
        forceTerminateTree(root);
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
