package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FILE = Path.of("src/main/java/demo/Demo.java").toAbsolutePath();
    private static final String TEXT = "package demo;\nclass Demo { void run() { } }\n";

    private JdtLsService service;
    private LspJsonRpcClient client;
    private PipedOutputStream toClient;
    private PipedInputStream fromClient;
    private Thread server;
    private volatile boolean running = true;

    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, String> responses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    @BeforeEach
    void startFakeServer() throws Exception {
        PipedInputStream clientInput = new PipedInputStream(1 << 16);
        toClient = new PipedOutputStream(clientInput);
        PipedOutputStream clientOutput = new PipedOutputStream();
        fromClient = new PipedInputStream(clientOutput, 1 << 16);
        client = new LspJsonRpcClient(clientInput, clientOutput, "jdtls-protocol-test");

        service = new JdtLsService(null, null, null, null);
        set("client", client);
        set("state", JdtLsService.State.READY);
        set("capabilities", allCapabilities());

        server = new Thread(this::serve, "fake-jdtls");
        server.setDaemon(true);
        server.start();
    }

    @AfterEach
    void stopFakeServer() throws Exception {
        running = false;
        client.close();
        server.interrupt();
    }

    @Test
    void completionCarriesTheTriggerContextOfTheEditor() throws Exception {
        responses.put("textDocument/completion",
                "{\"isIncomplete\":false,\"items\":[{\"label\":\"println\",\"kind\":2}]}");
        service.openDocument(FILE, TEXT);
        drainNotifications();

        List<AutoCompleteItem> items = service.complete(FILE, TEXT, 1, 20,
                JdtLsService.CompletionTrigger.TRIGGER_CHARACTER, '.', JdtLsService.ANY_VERSION);

        JsonNode request = awaitRequest("textDocument/completion");
        assertEquals(2, request.path("params").path("context").path("triggerKind").asInt());
        assertEquals(".", request.path("params").path("context").path("triggerCharacter").asText());
        assertEquals(List.of("println"), items.stream().map(AutoCompleteItem::label).toList());
    }

    @Test
    void manualCompletionAnnouncesAnInvokedTrigger() throws Exception {
        responses.put("textDocument/completion", "{\"isIncomplete\":false,\"items\":[]}");
        service.openDocument(FILE, TEXT);
        drainNotifications();

        service.complete(FILE, TEXT, 1, 12);

        JsonNode request = awaitRequest("textDocument/completion");
        assertEquals(1, request.path("params").path("context").path("triggerKind").asInt());
        assertTrue(request.path("params").path("context").path("triggerCharacter").isMissingNode());
    }

    @Test
    void completionNeverAsksTheServerToResolveAnItem() throws Exception {
        responses.put("textDocument/completion",
                "{\"isIncomplete\":false,\"items\":[{\"label\":\"format\",\"kind\":2,"
                        + "\"data\":{\"rid\":1},\"additionalTextEdits\":[]}]}");
        service.openDocument(FILE, TEXT);
        drainNotifications();

        service.complete(FILE, TEXT, 1, 12);
        awaitRequest("textDocument/completion");

        assertEquals(0, count("completionItem/resolve"));
    }

    @Test
    void answersForAnOlderDocumentVersionAreDiscarded() {
        service.openDocument(FILE, TEXT);
        int version = service.documentVersion(FILE);

        List<AutoCompleteItem> items = service.complete(FILE, TEXT, 1, 12,
                JdtLsService.CompletionTrigger.INVOKED, null, version + 5);

        assertTrue(items.isEmpty());
        assertEquals(0, count("textDocument/completion"));
    }

    @Test
    void navigationIsAnsweredOncePerDocumentVersion() throws Exception {
        responses.put("textDocument/definition",
                "[{\"uri\":\"file:///demo/Target.java\",\"range\":{\"start\":{\"line\":4,"
                        + "\"character\":2},\"end\":{\"line\":4,\"character\":8}}}]");
        service.openDocument(FILE, TEXT);
        drainNotifications();

        List<Location> first = service.definitionsInteractive(FILE, TEXT, 1, 12);
        awaitRequest("textDocument/definition");
        List<Location> second = service.definitionsInteractive(FILE, TEXT, 1, 12);

        assertEquals(1, first.size());
        assertEquals(first, second);
        assertEquals(1, count("textDocument/definition"));

        service.changeDocument(FILE, TEXT + "\n");
        drainNotifications();
        service.definitionsInteractive(FILE, TEXT + "\n", 1, 12);
        awaitRequest("textDocument/definition");

        assertEquals(2, count("textDocument/definition"));
    }

    @Test
    void anUnansweredNavigationGivesUpInsideTheInteractiveBudget() {
        service.openDocument(FILE, TEXT);
        long started = System.nanoTime();

        List<Location> locations = service.definitionsInteractive(FILE, TEXT, 1, 12);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(locations.isEmpty());
        assertTrue(elapsedMs < 2_000, "a navegacao esperou " + elapsedMs + " ms");
    }

    private void serve() {
        try {
            while (running) {
                JsonNode message = readFrame(fromClient);
                if (message == null) {
                    return;
                }
                String method = message.path("method").asText("");
                requestCounts.computeIfAbsent(method, ignored -> new AtomicInteger())
                        .incrementAndGet();
                received.add(message);
                if (message.hasNonNull("id") && responses.containsKey(method)) {
                    writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                            + ",\"result\":" + responses.get(method) + "}");
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JsonNode awaitRequest(String method) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = received.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && method.equals(message.path("method").asText(""))) {
                return message;
            }
        }
        throw new AssertionError("o servidor nao recebeu " + method);
    }

    private void drainNotifications() throws InterruptedException {
        Thread.sleep(100);
        received.clear();
    }

    private int count(String method) {
        AtomicInteger counter = requestCounts.get(method);
        return counter == null ? 0 : counter.get();
    }

    private void set(String field, Object value) throws Exception {
        Field target = JdtLsService.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(service, value);
    }

    private static JdtLsService.ServerCapabilities allCapabilities() throws Exception {
        JsonNode handshake = JSON.readTree("""
                {"capabilities":{"definitionProvider":true,"typeDefinitionProvider":true,
                 "implementationProvider":true,"referencesProvider":true,
                 "documentSymbolProvider":true,"documentHighlightProvider":true,
                 "completionProvider":{"resolveProvider":true,"triggerCharacters":[".","@"]},
                 "textDocumentSync":{"change":2}}}
                """);
        return LspClientCapabilities.readServerCapabilities(handshake);
    }

    private static JsonNode readFrame(InputStream input) throws Exception {
        int length = -1;
        StringBuilder header = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            header.append((char) value);
            if (header.toString().endsWith("\r\n\r\n")) {
                for (String line : header.toString().split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                break;
            }
        }
        if (length < 0) {
            return null;
        }
        byte[] body = input.readNBytes(length);
        assertNotNull(body);
        return JSON.readTree(new String(body, StandardCharsets.UTF_8));
    }

    private static void writeFrame(OutputStream output, String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }
}
