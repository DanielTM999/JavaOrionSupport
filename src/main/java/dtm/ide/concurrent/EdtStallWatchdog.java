package dtm.ide.concurrent;

import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class EdtStallWatchdog {

    private static final long CHECK_INTERVAL_MS = 1_000;
    private static final long STALL_THRESHOLD_MS = 3_000;

    private final String purpose;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();

    public EdtStallWatchdog(String purpose) {
        this.purpose = purpose == null ? "" : purpose;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long id = generation.incrementAndGet();
        Thread thread = new Thread(() -> watch(id), "orion-java-edt-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        generation.incrementAndGet();
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void watch(long id) {
        try {
            while (generation.get() == id) {
                CountDownLatch answered = new CountDownLatch(1);
                long sent = System.nanoTime();
                SwingUtilities.invokeLater(answered::countDown);
                if (!answered.await(STALL_THRESHOLD_MS, TimeUnit.MILLISECONDS)) {
                    log.warn("EDT sem responder ha mais de {} ms durante {}:\n{}",
                            STALL_THRESHOLD_MS, purpose, eventDispatchStack());
                    answered.await();
                    log.warn("EDT voltou a responder apos {} ms durante {}",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent), purpose);
                }
                Thread.sleep(CHECK_INTERVAL_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static String eventDispatchStack() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("AWT-EventQueue")) {
                continue;
            }
            text.append('"').append(entry.getKey().getName()).append("\" ")
                    .append(entry.getKey().getState()).append('\n');
            for (StackTraceElement element : entry.getValue()) {
                text.append("    at ").append(element).append('\n');
            }
        }
        return text.isEmpty() ? "(thread da EDT nao encontrada)" : text.toString();
    }
}
