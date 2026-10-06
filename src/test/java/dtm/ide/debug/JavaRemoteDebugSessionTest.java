package dtm.ide.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRemoteDebugSessionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void attachCarriesTheConfiguredHostPortAndTimeout() throws Exception {
        try (FakeAdapter adapter = FakeAdapter.start()) {
            JavaDebugSession session = new JavaDebugSession(adapter.port(),
                    JavaAttachTarget.remote("10.0.0.7", 6006, 12_000), root, List.of(),
                    value -> {
                    });
            session.start();

            JsonNode attach = adapter.await("attach").path("arguments");
            assertEquals("10.0.0.7", attach.path("hostName").asText());
            assertEquals(6006, attach.path("port").asInt());
            assertEquals(12_000, attach.path("timeout").asInt());
            session.close();
        }
    }

    @Test
    void disconnectingFromARemoteJvmDoesNotTerminateIt() throws Exception {
        try (FakeAdapter adapter = FakeAdapter.start()) {
            JavaDebugSession session = new JavaDebugSession(adapter.port(),
                    JavaAttachTarget.remote("127.0.0.1", 5005, 5_000), root, List.of(),
                    value -> {
                    });
            session.start();
            adapter.await("configurationDone");
            session.close();

            assertFalse(adapter.await("disconnect")
                            .path("arguments").path("terminateDebuggee").asBoolean(),
                    "a JVM remota apenas perde o depurador");
        }
    }

    @Test
    void aLocallyLaunchedProcessIsTerminatedOnDisconnect() throws Exception {
        try (FakeAdapter adapter = FakeAdapter.start()) {
            JavaDebugSession session = new JavaDebugSession(adapter.port(), 5005, root,
                    List.of(), value -> {
                    });
            session.start();
            adapter.await("configurationDone");
            session.close();

            assertTrue(adapter.await("disconnect")
                    .path("arguments").path("terminateDebuggee").asBoolean());
        }
    }

    @Test
    void localSessionsKeepAttachingToLoopback() throws Exception {
        try (FakeAdapter adapter = FakeAdapter.start()) {
            JavaDebugSession session = new JavaDebugSession(adapter.port(), 5199, root,
                    List.of(), value -> {
                    });
            session.start();

            JsonNode attach = adapter.await("attach").path("arguments");
            assertEquals(JavaAttachTarget.LOCALHOST, attach.path("hostName").asText());
            assertEquals(5199, attach.path("port").asInt());
            session.close();
        }
    }

    @Test
    void attachTargetsNormalizeBlankHostsAndTimeouts() {
        JavaAttachTarget target = new JavaAttachTarget("  ", 5005, 0, false);

        assertEquals(JavaAttachTarget.LOCALHOST, target.host());
        assertEquals(JavaAttachTarget.DEFAULT_TIMEOUT, target.timeoutMillis());
    }

    private static final class FakeAdapter implements AutoCloseable {

        private final ServerSocket server;
        private final Map<String, CompletableFuture<JsonNode>> requests = new ConcurrentHashMap<>();

        private FakeAdapter(ServerSocket server) {
            this.server = server;
        }

        static FakeAdapter start() throws Exception {
            FakeAdapter adapter = new FakeAdapter(new ServerSocket(0));
            Thread thread = new Thread(adapter::serve, "fake-dap");
            thread.setDaemon(true);
            thread.start();
            return adapter;
        }

        int port() {
            return server.getLocalPort();
        }

        JsonNode await(String command) throws Exception {
            return future(command).get(3, TimeUnit.SECONDS);
        }

        private CompletableFuture<JsonNode> future(String command) {
            return requests.computeIfAbsent(command, ignored -> new CompletableFuture<>());
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                int sequence = 1;
                while (true) {
                    JsonNode request = read(input);
                    String command = request.path("command").asText();
                    write(output, """
                            {"seq":%d,"type":"response","request_seq":%d,"command":"%s",\
                            "success":true,"body":{}}
                            """.formatted(sequence++, request.path("seq").asInt(), command));
                    if ("attach".equals(command)) {
                        write(output, """
                                {"seq":900,"type":"event","event":"initialized","body":{}}
                                """);
                    }
                    future(command).complete(request);
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    private static JsonNode read(BufferedInputStream input) throws Exception {
        int length = -1;
        while (true) {
            String line = readLine(input);
            if (line.isEmpty()) {
                break;
            }
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return JSON.readTree(input.readNBytes(length));
    }

    private static String readLine(BufferedInputStream input) throws Exception {
        StringBuilder value = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new java.io.EOFException();
            }
            if (previous == '\r' && current == '\n') {
                value.setLength(Math.max(0, value.length() - 1));
                return value.toString();
            }
            value.append((char) current);
            previous = current;
        }
    }

    private static void write(BufferedOutputStream output, String json) throws Exception {
        byte[] body = json.strip().getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
