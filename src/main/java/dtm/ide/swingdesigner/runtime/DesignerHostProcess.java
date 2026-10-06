package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.run.OwnedRunProcesses;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public final class DesignerHostProcess implements AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_FRAME = 256 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final DataOutputStream out;
    private final DataInputStream in;
    private final Consumer<String> output;
    private final Map<Long, CompletableFuture<HostResponse>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<JsonNode> ready = new CompletableFuture<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean closed;

    private DesignerHostProcess(Process process, Consumer<String> output) {
        this.process = process;
        this.out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream(), 64 * 1024));
        this.in = new DataInputStream(new BufferedInputStream(process.getInputStream(), 64 * 1024));
        this.output = output == null ? line -> { } : output;
    }

    public static DesignerHostProcess start(Path javaExecutable, Path hostJar, Consumer<String> output,
                                            Duration readyTimeout) {
        List<String> command = List.of(javaExecutable.toString(), "-Xmx1g", "-Dfile.encoding=UTF-8",
                "-cp", hostJar.toString(), HostJar.MAIN_CLASS);
        Process process;
        try {
            long generation = OwnedRunProcesses.launchGeneration();
            process = new ProcessBuilder(command).start();
            OwnedRunProcesses.register(process, generation);
        } catch (IOException e) {
            throw new DesignerHostException("Nao foi possivel iniciar a JVM do Swing Designer: "
                    + e.getMessage(), e);
        }
        DesignerHostProcess host = new DesignerHostProcess(process, output);
        host.startReaders();
        try {
            host.ready.get(readyTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return host;
        } catch (Exception e) {
            host.close();
            throw new DesignerHostException("A JVM do Swing Designer nao respondeu ao iniciar: "
                    + rootMessage(e), e);
        }
    }

    public ObjectNode params() {
        return JSON.createObjectNode();
    }

    public HostResponse call(String op, ObjectNode params) {
        return call(op, params, DEFAULT_TIMEOUT);
    }

    public HostResponse call(String op, ObjectNode params, Duration timeout) {
        CompletableFuture<HostResponse> future = request(op, params);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            close();
            throw new DesignerHostException("A JVM do Swing Designer nao respondeu a '" + op
                    + "' em " + timeout.toSeconds() + "s e foi reiniciada", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DesignerHostException("Interrompido", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DesignerHostException hostError) {
                throw hostError;
            }
            throw new DesignerHostException(rootMessage(cause), cause);
        }
    }

    public CompletableFuture<HostResponse> request(String op, ObjectNode params) {
        if (closed || !process.isAlive()) {
            return CompletableFuture.failedFuture(new DesignerHostException("A JVM do Swing Designer esta encerrada"));
        }
        long id = ids.incrementAndGet();
        ObjectNode header = params == null ? JSON.createObjectNode() : params.deepCopy();
        header.put("id", id);
        header.put("op", op);
        CompletableFuture<HostResponse> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            byte[] bytes = JSON.writeValueAsBytes(header);
            synchronized (out) {
                out.writeInt(bytes.length);
                out.write(bytes);
                out.writeInt(0);
                out.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(new DesignerHostException("Falha ao falar com a JVM do Swing Designer: "
                    + e.getMessage(), e));
        }
        return future;
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    public JsonNode readyInfo() {
        return ready.getNow(null);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (process.isAlive()) {
                ObjectNode header = JSON.createObjectNode();
                header.put("id", ids.incrementAndGet());
                header.put("op", "shutdown");
                byte[] bytes = JSON.writeValueAsBytes(header);
                synchronized (out) {
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.writeInt(0);
                    out.flush();
                }
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        failPending("A JVM do Swing Designer foi encerrada");
    }

    private void startReaders() {
        Thread reader = new Thread(this::readLoop, "swing-designer-host-reader");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    output.accept(line);
                }
            } catch (IOException ignored) {
            }
        }, "swing-designer-host-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    private void readLoop() {
        try {
            while (true) {
                int headerLength;
                try {
                    headerLength = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                byte[] header = block(headerLength);
                int blobLength = in.readInt();
                byte[] blob = blobLength == 0 ? null : block(blobLength);
                dispatch(JSON.readTree(header), blob);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("Leitura da JVM do Swing Designer interrompida: {}", e.toString());
            }
        } finally {
            closed = true;
            ready.completeExceptionally(new DesignerHostException("A JVM do Swing Designer encerrou"));
            failPending("A JVM do Swing Designer encerrou inesperadamente");
        }
    }

    private void dispatch(JsonNode header, byte[] blob) {
        String event = header.path("event").asText(null);
        if ("ready".equals(event)) {
            ready.complete(header);
            return;
        }
        if ("log".equals(event)) {
            String stream = header.path("stream").asText("out");
            output.accept(("err".equals(stream) ? "[stderr] " : "") + header.path("text").asText(""));
            return;
        }
        long id = header.path("id").asLong(-1);
        CompletableFuture<HostResponse> future = pending.remove(id);
        if (future == null) {
            return;
        }
        if (header.path("ok").asBoolean(false)) {
            future.complete(new HostResponse(header.path("result"), blob));
        } else {
            future.completeExceptionally(new DesignerHostException(header.path("error")
                    .asText("Erro desconhecido na JVM do Swing Designer")));
        }
    }

    private byte[] block(int length) throws IOException {
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("Tamanho de frame invalido: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private void failPending(String message) {
        for (Long id : List.copyOf(pending.keySet())) {
            CompletableFuture<HostResponse> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(new DesignerHostException(message));
            }
        }
    }

    static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause == null) {
            return "erro desconhecido";
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
