package dtm.ide.concurrent;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class PluginTaskExecutor implements Executor, AutoCloseable {

    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PluginTaskExecutor(String threadPrefix) {
        String prefix = threadPrefix == null || threadPrefix.isBlank()
                ? "java-orion-support" : threadPrefix;
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(prefix + "-", 0).factory());
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, prefix + "-delay");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Future<?> submit(Runnable task) {
        if (task == null || closed.get()) {
            return null;
        }
        AtomicReference<Future<?>> reference = new AtomicReference<>();
        try {
            Future<?> future = workers.submit(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    log.warn("Tarefa em segundo plano falhou", error);
                } finally {
                    Future<?> tracked = reference.get();
                    if (tracked != null) {
                        pending.remove(tracked);
                    }
                }
            });
            reference.set(future);
            pruneCompleted();
            pending.add(future);
            if (future.isDone()) {
                pending.remove(future);
            }
            return future;
        } catch (RejectedExecutionException ignored) {
            return null;
        }
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (task == null || closed.get()) {
            return null;
        }
        AtomicReference<ScheduledFuture<?>> reference = new AtomicReference<>();
        try {
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    if (!closed.get()) {
                        submit(task);
                    }
                } finally {
                    ScheduledFuture<?> tracked = reference.get();
                    if (tracked != null) {
                        pending.remove(tracked);
                    }
                }
            }, Math.max(0, delay), unit == null ? TimeUnit.MILLISECONDS : unit);
            reference.set(future);
            pruneCompleted();
            pending.add(future);
            if (future.isDone()) {
                pending.remove(future);
            }
            return future;
        } catch (RejectedExecutionException ignored) {
            return null;
        }
    }

    public void cancelPending() {
        for (Future<?> future : Set.copyOf(pending)) {
            future.cancel(true);
        }
        pending.clear();
    }

    public int pendingCount() {
        pruneCompleted();
        return pending.size();
    }

    private void pruneCompleted() {
        pending.removeIf(Future::isDone);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void execute(Runnable command) {
        submit(command);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelPending();
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
