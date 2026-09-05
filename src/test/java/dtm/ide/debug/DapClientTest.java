package dtm.ide.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DapClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void framesRequestsResponsesAndEvents() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> event = new CompletableFuture<>();
            Thread adapter = new Thread(() -> serveOnce(server));
            adapter.setDaemon(true);
            adapter.start();
            try (DapClient client = DapClient.connect("127.0.0.1", server.getLocalPort(),
                    Duration.ofSeconds(2))) {
                client.setEventListener((name, body) -> event.complete(name + ":"
                        + body.path("value").asText()));
                JsonNode response = client.request("threads", java.util.Map.of())
                        .get(2, TimeUnit.SECONDS);
                assertEquals("worker", response.path("name").asText());
                assertEquals("output:ready", event.get(2, TimeUnit.SECONDS));
            }
        }
    }

    private static void serveOnce(ServerSocket server) {
        try (Socket socket = server.accept()) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            JsonNode request = read(input);
            write(output, """
                    {"seq":1,"type":"response","request_seq":%d,"command":"threads","success":true,"body":{"name":"worker"}}
                    """.formatted(request.path("seq").asInt()));
            write(output, """
                    {"seq":2,"type":"event","event":"output","body":{"value":"ready"}}
                    """);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static JsonNode read(BufferedInputStream input) throws Exception {
        int length = -1;
        while (true) {
            String line = readLine(input);
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("content-length:")) {
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
