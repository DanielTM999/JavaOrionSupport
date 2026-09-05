package dtm.ide.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class DapClient implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, JsonNode> eventListener = (name, body) -> {
    };

    private DapClient(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
        this.output = new BufferedOutputStream(socket.getOutputStream());
        Thread reader = new Thread(this::readLoop, "java-dap-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public static DapClient connect(String host, int port, Duration timeout) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
        socket.setTcpNoDelay(true);
        return new DapClient(socket);
    }

    public void setEventListener(BiConsumer<String, JsonNode> listener) {
        eventListener = listener == null ? (name, body) -> {
        } : listener;
    }

    public CompletableFuture<JsonNode> request(String command, Object arguments) {
        int id = sequence.getAndIncrement();
        ObjectNode message = JSON.createObjectNode();
        message.put("seq", id);
        message.put("type", "request");
        message.put("command", command);
        if (arguments != null) {
            message.set("arguments", JSON.valueToTree(arguments));
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(message);
        } catch (IOException error) {
            pending.remove(id);
            future.completeExceptionally(error);
        }
        return future;
    }

    private void write(JsonNode message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        synchronized (output) {
            output.write(header);
            output.write(body);
            output.flush();
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                JsonNode message = readMessage();
                String type = message.path("type").asText();
                if ("response".equals(type)) {
                    completeResponse(message);
                } else if ("event".equals(type)) {
                    eventListener.accept(message.path("event").asText(), message.path("body"));
                } else if ("request".equals(type)) {
                    rejectReverseRequest(message);
                }
            }
        } catch (Exception error) {
            if (!closed.get()) {
                failPending(error);
                eventListener.accept("connectionClosed", JSON.valueToTree(error.getMessage()));
            }
        } finally {
            close();
        }
    }

    private JsonNode readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String header = readHeaderLine();
            if (header.isEmpty()) {
                break;
            }
            int colon = header.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(header.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(header.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IOException("DAP response without Content-Length");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new EOFException("DAP connection ended during a message");
        }
        return JSON.readTree(body);
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("DAP connection closed");
            }
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(value);
            previous = value;
        }
    }

    private void completeResponse(JsonNode message) {
        CompletableFuture<JsonNode> future = pending.remove(message.path("request_seq").asInt());
        if (future == null) {
            return;
        }
        if (message.path("success").asBoolean(false)) {
            future.complete(message.path("body"));
        } else {
            future.completeExceptionally(new IOException(message.path("message")
                    .asText("DAP request failed")));
        }
    }

    private void rejectReverseRequest(JsonNode request) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("seq", sequence.getAndIncrement());
        response.put("type", "response");
        response.put("request_seq", request.path("seq").asInt());
        response.put("command", request.path("command").asText());
        response.put("success", false);
        response.put("message", "Unsupported reverse request");
        write(response);
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending(new IOException("DAP connection closed"));
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
