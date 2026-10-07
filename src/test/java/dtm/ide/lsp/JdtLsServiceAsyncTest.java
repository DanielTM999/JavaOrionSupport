package dtm.ide.lsp;

import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.lsp.api.LanguageServerState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsServiceAsyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private JdtLsService service;
    private FakeServer server;

    @BeforeEach
    void connect() throws Exception {
        service = new JdtLsService(null, null, null, ignored -> {
        });
        server = new FakeServer();
        LspJsonRpcClient client = new LspJsonRpcClient(server.clientInput, server.clientOutput, "async-test");
        set("client", client);
        set("state", LanguageServerState.READY);
    }

    @AfterEach
    void close() throws Exception {
        server.close();
        service.shutdown();
    }

    @Test
    void completionReturnsBeforeTheServerAnswersAndCompletesWithItsItems() throws Exception {
        Path source = root.resolve("A.java");
        String text = "class A { void m() { System.out.pr } }";

        CompletableFuture<List<AutoCompleteItem>> pending = service.completeAsync(source, text, 0, 33,
                CompletionTrigger.INVOKED, null, -1);

        assertFalse(pending.isDone(), "a completion nao pode esperar pelo servidor");
        JsonNode request = server.next("textDocument/completion");
        server.reply(request, MAPPER.readTree("""
                {"isIncomplete":false,"items":[{"label":"println","kind":2,"insertText":"println"}]}
                """));

        List<AutoCompleteItem> items = pending.get(2, TimeUnit.SECONDS);
        assertEquals(List.of("println"), items.stream().map(AutoCompleteItem::label).toList());
    }

    @Test
    void cancellingTheCompletionCancelsTheRequestOnTheServer() throws Exception {
        Path source = root.resolve("B.java");
        CompletableFuture<List<AutoCompleteItem>> pending = service.completeAsync(source,
                "class B { }", 0, 9, CompletionTrigger.INVOKED, null, -1);
        JsonNode request = server.next("textDocument/completion");

        pending.cancel(false);

        JsonNode cancel = server.next("$/cancelRequest");
        assertEquals(request.get("id").asLong(), cancel.path("params").path("id").asLong());
    }

    @Test
    void editingTheDocumentDropsAPendingHover() throws Exception {
        Path source = root.resolve("C.java");
        String text = "class C { int value; }";
        CompletableFuture<HoverInfo> hover = service.hoverAsync(source, text, 0, 15);
        server.next("textDocument/hover");

        service.changeDocument(source, "class C { int renamed; }");

        assertNull(hover.get(2, TimeUnit.SECONDS));
        assertNotNull(server.next("$/cancelRequest"));
    }

    @Test
    void aHoverThatArrivesAfterAnEditIsDiscarded() throws Exception {
        Path source = root.resolve("D.java");
        String text = "class D { int value; }";
        CompletableFuture<HoverInfo> hover = service.hoverAsync(source, text, 0, 15);
        JsonNode request = server.next("textDocument/hover");
        service.changeDocument(source, "class D { int value2; }");

        server.reply(request, MAPPER.readTree("""
                {"contents":{"kind":"markdown","value":"int value"}}
                """));

        assertNull(hover.get(2, TimeUnit.SECONDS));
        assertTrue(hover.isDone());
    }

    private void set(String field, Object value) throws Exception {
        var declared = JdtLsService.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(service, value);
    }

    private static final class FakeServer {

        final PipedInputStream clientInput = new PipedInputStream(1 << 16);
        final PipedOutputStream toClient;
        final PipedOutputStream clientOutput = new PipedOutputStream();
        private final PipedInputStream fromClient;
        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final Thread reader;

        FakeServer() throws IOException {
            toClient = new PipedOutputStream(clientInput);
            fromClient = new PipedInputStream(clientOutput, 1 << 16);
            reader = new Thread(this::readLoop, "fake-jdtls");
            reader.setDaemon(true);
            reader.start();
        }

        JsonNode next(String method) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                JsonNode message = received.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && method.equals(message.path("method").asText())) {
                    return message;
                }
            }
            throw new AssertionError("o cliente nao enviou " + method);
        }

        void reply(JsonNode request, JsonNode result) throws IOException {
            var response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] body = MAPPER.writeValueAsBytes(response);
            synchronized (toClient) {
                toClient.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                toClient.write(body);
                toClient.flush();
            }
        }

        void close() throws IOException {
            toClient.close();
            reader.interrupt();
        }

        private void readLoop() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int length = contentLength(fromClient);
                    if (length < 0) {
                        return;
                    }
                    byte[] body = fromClient.readNBytes(length);
                    received.add(MAPPER.readTree(body));
                }
            } catch (IOException ignored) {
            }
        }

        private static int contentLength(InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = input.read();
                if (next < 0) {
                    return -1;
                }
                header.write(next);
                matched = (next == '\r' && (matched == 0 || matched == 2))
                        || (next == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : 0;
            }
            for (String line : header.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return -1;
        }
    }

}
