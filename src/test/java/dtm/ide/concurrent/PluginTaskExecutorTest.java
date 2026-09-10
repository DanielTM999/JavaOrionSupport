package dtm.ide.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTaskExecutorTest {

    @Test
    void executesAndRemovesCompletedTasks() throws Exception {
        try (PluginTaskExecutor executor = new PluginTaskExecutor("test-task")) {
            CountDownLatch done = new CountDownLatch(1);
            executor.submit(done::countDown);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 20 && executor.pendingCount() > 0; attempt++) {
                Thread.sleep(10);
            }
            assertTrue(executor.pendingCount() == 0);
        }
    }

    @Test
    void cancellationInterruptsProjectWork() throws Exception {
        try (PluginTaskExecutor executor = new PluginTaskExecutor("test-cancel")) {
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            executor.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            executor.cancelPending();

            for (int attempt = 0; attempt < 50 && !interrupted.get(); attempt++) {
                Thread.sleep(10);
            }
            assertTrue(interrupted.get());
        }
    }

    @Test
    void closeRejectsNewWork() {
        PluginTaskExecutor executor = new PluginTaskExecutor("test-close");
        executor.close();

        AtomicBoolean ran = new AtomicBoolean();
        executor.execute(() -> ran.set(true));

        assertFalse(ran.get());
        assertTrue(executor.isClosed());
    }

    @Test
    void scheduledWorkMovesToAWorkerAndCanBeCancelledBeforeTheDelay() throws Exception {
        try (PluginTaskExecutor executor = new PluginTaskExecutor("test-schedule")) {
            AtomicBoolean cancelledRan = new AtomicBoolean();
            var cancelled = executor.schedule(() -> cancelledRan.set(true),
                    200, TimeUnit.MILLISECONDS);
            assertTrue(cancelled.cancel(false));

            CountDownLatch done = new CountDownLatch(1);
            executor.schedule(done::countDown, 5, TimeUnit.MILLISECONDS);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertFalse(cancelledRan.get());
        }
    }
}
