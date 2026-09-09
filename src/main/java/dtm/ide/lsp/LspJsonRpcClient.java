package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public final class LspJsonRpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InputStream input;
    private final OutputStream output;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> notifications = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, Object>> requests = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor;
    private final ExecutorService notificationExecutor;
    private final ExecutorService requestExecutor;
    private final ExecutorService writerExecutor;
    private final Future<?> reader;
    private final Object writeLock = new Object();

    private volatile boolean closed;

    public LspJsonRpcClient(InputStream input, OutputStream output, String threadName) {
        this.input = new BufferedInputStream(input, 1 << 16);
        this.output = output;
        this.readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.notificationExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName + "-notifications");
            thread.setDaemon(true);
            return thread;
        });
        this.requestExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadName + "-requests-", 0).factory());
        this.writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName + "-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.reader = readerExecutor.submit(this::readLoop);
    }

    public CompletableFuture<JsonNode> request(String method, Object params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IOException("Language server encerrado"));
            return future;
        }
        pending.put(id, future);
        future.whenComplete((result, error) -> {
            pending.remove(id, future);
            if (future.isCancelled() && !closed) {
                notify("$/cancelRequest", Map.of("id", id));
            }
        });

        try {
            writerExecutor.execute(() -> {
                try {
                    ObjectNode message = MAPPER.createObjectNode();
                    message.put("jsonrpc", "2.0");
                    message.put("id", id);
                    message.put("method", method);
                    message.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
                    write(message);
                } catch (Exception e) {
                    pending.remove(id);
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public void notify(String method, Object params) {
        if (closed) {
            return;
        }
        try {
            writerExecutor.execute(() -> {
                if (closed) return;
                ObjectNode message = MAPPER.createObjectNode();
                message.put("jsonrpc", "2.0");
                message.put("method", method);
                message.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
                try {
                    write(message);
                } catch (Exception e) {
                    log.debug("Falha ao enviar {} ao language server: {}", method, e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // O cliente foi encerrado entre a verificacao de closed e o enfileiramento.
        }
    }

    public void onNotification(String method, Consumer<JsonNode> handler) {
        notifications.put(method, handler);
    }

    public void onRequest(String method, Function<JsonNode, Object> handler) {
        requests.put(method, handler);
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(input);
        closeQuietly(output);
        IOException reason = new IOException("Language server encerrado");
        pending.values().forEach(future -> future.completeExceptionally(reason));
        pending.clear();
        reader.cancel(true);
        readerExecutor.shutdownNow();
        notificationExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        writerExecutor.shutdownNow();
    }

    private void readLoop() {
        try {
            while (!closed) {
                JsonNode message = read();
                if (message == null) {
                    break;
                }
                dispatch(message);
            }
        } catch (Exception e) {
            if (!closed) {
                log.debug("Leitor do language server encerrado: {}", e.getMessage());
            }
        } finally {
            IOException reason = new IOException("Language server encerrou a conexao");
            pending.values().forEach(future -> future.completeExceptionally(reason));
            pending.clear();
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        JsonNode methodNode = message.get("method");

        boolean isResponse = id != null && methodNode == null
                && (message.has("result") || message.has("error"));
        if (isResponse) {
            completePending(id, message);
            return;
        }
        if (methodNode == null) {
            return;
        }

        String method = methodNode.asText();
        if (id != null) {
            requestExecutor.execute(() -> respondTo(method, id, message.get("params")));
            return;
        }
        Consumer<JsonNode> handler = notifications.get(method);
        if (handler != null) {
            notificationExecutor.execute(() -> {
                try {
                    handler.accept(message.get("params"));
                } catch (Exception e) {
                    log.debug("Handler da notificacao {} falhou: {}", method, e.getMessage());
                }
            });
        }
    }

    private void completePending(JsonNode id, JsonNode message) {
        if (!id.canConvertToLong()) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(id.asLong());
        if (future == null) {
            return;
        }
        if (message.has("error") && !message.get("error").isNull()) {
            future.completeExceptionally(new IOException(message.get("error").toString()));
        } else {
            future.complete(message.get("result"));
        }
    }

    private void respondTo(String method, JsonNode id, JsonNode params) {
        Object result = null;
        Function<JsonNode, Object> handler = requests.get(method);
        if (handler != null) {
            try {
                result = handler.apply(params);
            } catch (Exception e) {
                log.debug("Requisicao {} do servidor falhou no cliente: {}", method, e.getMessage());
            }
        }
        Object responseResult = result;
        try {
            writerExecutor.execute(() -> {
                ObjectNode response = MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", id);
                if (responseResult == null) {
                    response.putNull("result");
                } else {
                    response.set("result", MAPPER.valueToTree(responseResult));
                }
                try {
                    write(response);
                } catch (IOException e) {
                    log.debug("Falha ao responder {}: {}", method, e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Cliente encerrado; nao ha servidor esperando essa resposta.
        }
    }

    private void write(ObjectNode message) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            output.write(header);
            output.write(body);
            output.flush();
        }
    }

    private JsonNode read() throws IOException {
        int length = -1;
        StringBuilder line = new StringBuilder(64);

        while (true) {
            int c = input.read();
            if (c < 0) {
                return null;
            }
            if (c != '\r') {
                line.append((char) c);
                continue;
            }
            int next = input.read();
            if (next < 0) {
                return null;
            }
            if (next != '\n') {
                line.append('\r').append((char) next);
                continue;
            }
            String header = line.toString();
            line.setLength(0);
            if (header.isEmpty()) {
                break;
            }
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim()
                    .toLowerCase(Locale.ROOT).equals("content-length")) {
                try {
                    length = Integer.parseInt(header.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Content-Length invalido: " + header);
                }
            }
        }

        if (length < 0) {
            throw new IOException("Mensagem LSP sem Content-Length");
        }
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
            throw new IOException("Mensagem LSP incompleta");
        }
        return MAPPER.readTree(body);
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
