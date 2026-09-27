package dtm.ide.concurrent;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdtStallWatchdogTest {

    @Test
    void capturesWhatTheBlockedEventDispatchThreadIsDoing() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try {
            String stack = EdtStallWatchdog.eventDispatchStack();

            assertTrue(stack.contains("AWT-EventQueue"));
            assertTrue(stack.contains("capturesWhatTheBlockedEventDispatchThreadIsDoing"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void stopEndsTheWatchAndAllowsRestarting() {
        EdtStallWatchdog watchdog = new EdtStallWatchdog("teste");

        watchdog.start();
        assertTrue(watchdog.isRunning());
        watchdog.stop();
        assertFalse(watchdog.isRunning());
        watchdog.start();
        assertTrue(watchdog.isRunning());
        watchdog.stop();
    }
}
