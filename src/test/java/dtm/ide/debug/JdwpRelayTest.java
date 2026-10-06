package dtm.ide.debug;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdwpRelayTest {

    private static final String LOOPBACK = "127.0.0.1";

    @Test
    void relaysTrafficInBothDirections() throws Exception {
        try (JdwpRelay relay = JdwpRelay.open(LOOPBACK, 0);
             Socket jvm = new Socket(LOOPBACK, relay.listenPort())) {

            relay.awaitTarget(2_000);
            try (Socket adapter = new Socket(LOOPBACK, relay.adapterPort())) {
                assertArrayEquals("JDWP-Handshake".getBytes(StandardCharsets.UTF_8),
                        exchange(adapter, jvm, "JDWP-Handshake"),
                        "o adapter fala com a JVM atraves do relay");
                assertArrayEquals("JDWP-Handshake".getBytes(StandardCharsets.UTF_8),
                        exchange(jvm, adapter, "JDWP-Handshake"),
                        "e a JVM responde pelo mesmo caminho");
            }
        }
    }

    @Test
    void timesOutWhenNoJvmConnects() throws Exception {
        try (JdwpRelay relay = JdwpRelay.open(LOOPBACK, 0)) {
            assertThrows(SocketTimeoutException.class, () -> relay.awaitTarget(150));
        }
    }

    @Test
    void aBusyPortFailsImmediatelyInsteadOfWaiting() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThrows(IOException.class, () -> JdwpRelay.open(LOOPBACK, busy.getLocalPort()));
        }
    }

    @Test
    void closingReleasesSocketsAndThreads() throws Exception {
        JdwpRelay relay = JdwpRelay.open(LOOPBACK, 0);
        int listenPort = relay.listenPort();
        try (Socket jvm = new Socket(LOOPBACK, listenPort)) {
            relay.awaitTarget(2_000);
            assertTrue(jvm.isConnected());
        }
        relay.close();
        relay.awaitShutdown(2_000);

        assertTrue(relay.isClosed());
        try (ServerSocket reopened = new ServerSocket(listenPort, 1,
                InetAddress.getByName(LOOPBACK))) {
            assertEquals(listenPort, reopened.getLocalPort());
        }
    }

    @Test
    void closingIsIdempotent() throws Exception {
        JdwpRelay relay = JdwpRelay.open(LOOPBACK, 0);
        relay.close();
        relay.close();
        assertTrue(relay.isClosed());
    }

    @Test
    void aDroppedJvmTearsDownTheRelay() throws Exception {
        try (JdwpRelay relay = JdwpRelay.open(LOOPBACK, 0)) {
            Socket jvm = new Socket(LOOPBACK, relay.listenPort());
            relay.awaitTarget(2_000);
            try (Socket adapter = new Socket(LOOPBACK, relay.adapterPort())) {
                adapter.getOutputStream().write(1);
                adapter.getOutputStream().flush();
                jvm.close();
                assertTrue(awaitClosed(relay, 5_000),
                        "o relay precisa cair junto com a JVM que desconectou");
            }
        }
    }

    private static boolean awaitClosed(JdwpRelay relay, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (relay.isClosed()) {
                return true;
            }
            Thread.sleep(25);
        }
        return relay.isClosed();
    }

    private static byte[] exchange(Socket from, Socket to, String message) throws IOException {
        OutputStream output = from.getOutputStream();
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.flush();

        InputStream input = to.getInputStream();
        byte[] buffer = new byte[message.length()];
        int read = 0;
        while (read < buffer.length) {
            int chunk = input.read(buffer, read, buffer.length - read);
            if (chunk < 0) {
                break;
            }
            read += chunk;
        }
        return buffer;
    }
}
