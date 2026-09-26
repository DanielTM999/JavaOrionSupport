package dtm.ide.debug;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolDebugListenerTest {

    private record Attached(JavaAttachTarget target, Consumer<Boolean> detached) {
    }

    @Test
    void attachesEachJvmInTurnWhileTheBuildRuns() throws Exception {
        BlockingQueue<Attached> attached = new LinkedBlockingQueue<>();
        AtomicInteger cancelled = new AtomicInteger();
        try (BuildToolDebugListener listener = BuildToolDebugListener.open(
                (target, detached) -> attached.add(new Attached(target, detached)),
                cancelled::incrementAndGet, Runnable::run);
             Socket first = connect(listener.listenPort())) {

            Attached session = attached.poll(2, TimeUnit.SECONDS);
            assertNotNull(session, "a primeira JVM deveria disparar o attach");
            assertTrue(session.target().terminateOnDisconnect());
            assertEquals(JavaAttachTarget.LOCALHOST, session.target().host());

            try (Socket second = connect(listener.listenPort())) {
                assertNull(attached.poll(200, TimeUnit.MILLISECONDS),
                        "a segunda JVM espera a sessao atual terminar");

                session.detached().accept(false);

                Attached next = attached.poll(2, TimeUnit.SECONDS);
                assertNotNull(next, "a segunda JVM deveria ser anexada em seguida");
                assertTrue(next.target().port() != session.target().port());
            }
            assertEquals(0, cancelled.get());
        }
    }

    @Test
    void stoppingTheDebuggerCancelsTheBuildAndStopsListening() throws Exception {
        BlockingQueue<Attached> attached = new LinkedBlockingQueue<>();
        AtomicInteger cancelled = new AtomicInteger();
        BuildToolDebugListener listener = BuildToolDebugListener.open(
                (target, detached) -> attached.add(new Attached(target, detached)),
                cancelled::incrementAndGet, Runnable::run);
        int port = listener.listenPort();
        try (Socket jvm = connect(port)) {
            Attached session = attached.poll(2, TimeUnit.SECONDS);
            assertNotNull(session);

            session.detached().accept(true);

            assertEquals(1, cancelled.get());
            assertTrue(listener.isClosed());
        }
        awaitRefused(port);
    }

    @Test
    void closingReleasesThePortWithoutAnyJvm() throws Exception {
        BuildToolDebugListener listener = BuildToolDebugListener.open(
                (target, detached) -> {
                }, () -> {
                }, Runnable::run);
        int port = listener.listenPort();

        listener.close();

        awaitRefused(port);
    }

    private static Socket connect(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return new Socket(JavaAttachTarget.LOCALHOST, port);
            } catch (IOException error) {
                last = error;
                Thread.sleep(20);
            }
        }
        throw last;
    }

    private static void awaitRefused(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket(JavaAttachTarget.LOCALHOST, port)) {
                Thread.sleep(20);
            } catch (IOException refused) {
                return;
            }
        }
        assertThrows(IOException.class, () -> new Socket(JavaAttachTarget.LOCALHOST, port).close());
    }
}
