package dtm.ide.run;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Local run processes owned by this plugin, including runs sharing one configuration. */
@Slf4j
public final class OwnedRunProcesses {

    private static final Set<ProcessHandle> PROCESSES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean CLOSING = new AtomicBoolean();
    private static final AtomicLong GENERATION = new AtomicLong();

    private OwnedRunProcesses() {
    }

    public static long launchGeneration() {
        return GENERATION.get();
    }

    public static void register(Process process, long launchGeneration) {
        ProcessHandle handle;
        try {
            handle = process.toHandle();
        } catch (RuntimeException unavailable) {
            handle = ProcessHandle.of(process.pid()).orElse(null);
        }
        if (handle == null) {
            log.warn("Processo Java local sem ProcessHandle: pid={}", process.pid());
            return;
        }
        if (CLOSING.get() || GENERATION.get() != launchGeneration) {
            terminateTree(handle);
            return;
        }
        PROCESSES.add(handle);
        if ((CLOSING.get() || GENERATION.get() != launchGeneration)
                && PROCESSES.remove(handle)) {
            terminateTree(handle);
        }
    }

    public static synchronized void shutdownAll() {
        CLOSING.set(true);
        GENERATION.incrementAndGet();
        try {
            CompletableFuture<?>[] tasks = List.copyOf(PROCESSES).stream()
                    .map(handle -> CompletableFuture.runAsync(() -> {
                        try {
                            terminateTree(handle);
                        } finally {
                            PROCESSES.remove(handle);
                        }
                    }, task -> Thread.ofVirtual().name("java-process-stop").start(task)))
                    .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(tasks).get(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.warn("Processos Java locais ainda encerrando", error);
            }
        } finally {
            CLOSING.set(false);
        }
    }

    private static void terminateTree(ProcessHandle root) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<ProcessHandle> tree = new ArrayList<>();
        try (var descendants = root.descendants()) {
            tree.addAll(descendants.toList());
        } catch (RuntimeException error) {
            log.debug("Falha ao listar filhos do processo {}", root.pid(), error);
        }
        for (int index = tree.size() - 1; index >= 0; index--) {
            destroy(tree.get(index));
        }
        destroy(root);
        for (ProcessHandle handle : tree) {
            awaitExit(handle, deadline);
        }
        awaitExit(root, deadline);
    }

    private static void destroy(ProcessHandle handle) {
        if (handle.isAlive()) {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException error) {
                log.warn("Falha ao encerrar processo Java local pid={}", handle.pid(), error);
            }
        }
    }

    private static void awaitExit(ProcessHandle handle, long deadline) {
        if (!handle.isAlive()) {
            return;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (Exception error) {
            log.debug("Falha ao aguardar processo Java local pid={}", handle.pid(), error);
        }
        if (handle.isAlive()) {
            log.warn("Processo Java local ainda ativo apos encerramento: pid={}", handle.pid());
        }
    }
}
