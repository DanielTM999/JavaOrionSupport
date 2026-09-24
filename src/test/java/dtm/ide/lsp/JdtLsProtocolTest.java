package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
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
import java.util.Map;
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
    private static final Path OTHER_FILE =
            Path.of("src/main/java/demo/Foo.java").toAbsolutePath();
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
    void aStaleFeatureRequestNeverRollsBackTheServerDocument() throws Exception {
        responses.put("textDocument/definition", "[]");
        service.openDocument(FILE, TEXT);
        drainNotifications();

        String latest = TEXT + "// latest\n";
        service.changeDocument(FILE, latest);
        awaitRequest("textDocument/didChange");

        List<Location> locations = service.definitionsInteractive(FILE, TEXT, 1, 12);

        assertTrue(locations.isEmpty());
        assertEquals(1, count("textDocument/didChange"));
        assertEquals(0, count("textDocument/definition"));
    }

    @Test
    void diagnosticsFollowTheCurrentDocumentVersion() throws Exception {
        service.openDocument(FILE, TEXT);
        drainNotifications();
        int firstVersion = service.documentVersion(FILE);
        service.onPublishDiagnostics(diagnostics(firstVersion, "current", 1, 0, 1, 5));
        assertEquals("current", service.diagnostics(FILE).iterator().next().message());

        String latest = TEXT + "// latest\n";
        service.changeDocument(FILE, latest);
        awaitRequest("textDocument/didChange");
        int latestVersion = service.documentVersion(FILE);
        assertTrue(service.diagnostics(FILE).isEmpty());

        service.onPublishDiagnostics(diagnostics(firstVersion, "stale", 0, 0, 2, 0));
        assertTrue(service.diagnostics(FILE).isEmpty());

        service.onPublishDiagnostics(diagnostics(latestVersion, "latest", 1, 0, 1, 5));
        assertEquals("latest", service.diagnostics(FILE).iterator().next().message());

        service.onPublishDiagnostics(diagnostics(null, "unversioned", 1, 0, 1, 5));
        assertEquals("unversioned", service.diagnostics(FILE).iterator().next().message());
    }

    @Test
    void diagnosticsPublishedWhileTheProjectLoadsWaitForTheSettledAnalysis() throws Exception {
        List<Path> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        service = new JdtLsService(null, null, null, published::add);
        set("client", client);
        set("state", JdtLsService.State.READY);
        set("capabilities", allCapabilities());
        set("diagnosticsSettled", false);
        service.openDocument(FILE, TEXT);
        drainNotifications();

        service.onPublishDiagnostics(diagnostics(service.documentVersion(FILE),
                "cannot be resolved", 1, 0, 1, 5));
        service.onPublishDiagnostics(otherFileDiagnostics("unused import"));

        assertTrue(published.isEmpty());
        assertTrue(service.diagnostics(FILE).isEmpty());
        assertTrue(service.diagnostics(OTHER_FILE).isEmpty());

        service.settleDiagnostics();

        assertTrue(service.isDiagnosticsSettled());
        assertTrue(service.diagnostics(FILE).isEmpty());
        assertEquals("unused import", service.diagnostics(OTHER_FILE).iterator().next().message());
        assertTrue(published.contains(FILE));
        assertTrue(published.contains(OTHER_FILE));
        JsonNode reopened = awaitRequest("textDocument/didOpen");
        assertEquals(FILE.toUri().toString(),
                reopened.path("params").path("textDocument").path("uri").asText());

        service.onPublishDiagnostics(diagnostics(service.documentVersion(FILE), "fresh", 1, 0, 1, 5));
        assertEquals("fresh", service.diagnostics(FILE).iterator().next().message());
    }

    @Test
    void broadDiagnosticsAreCompactInTheEditorButRawForCodeActions() throws Exception {
        responses.put("textDocument/codeAction", "[]");
        service.openDocument(FILE, TEXT);
        drainNotifications();
        service.onPublishDiagnostics(diagnostics(service.documentVersion(FILE),
                "Syntax error on token(s), misplaced construct(s)", 0, 0, 2, 0));

        Diagnostic visible = service.diagnostics(FILE).iterator().next();
        assertEquals(0, visible.startLine());
        assertEquals(0, visible.endLine());
        assertEquals("package demo;".length(), visible.endCol());

        service.codeActions(FILE, TEXT, Range.point(0, 0), List.of(visible));
        JsonNode request = awaitRequest("textDocument/codeAction");
        JsonNode raw = request.path("params").path("context").path("diagnostics").get(0);
        assertEquals(2, raw.path("range").path("end").path("line").asInt());
    }

    @Test
    void anUnansweredNavigationHasABoundedTimeout() {
        service.openDocument(FILE, TEXT);
        long started = System.nanoTime();

        List<Location> locations = service.definitionsInteractive(FILE, TEXT, 1, 12);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(locations.isEmpty());
        assertTrue(elapsedMs < 5_500, "a navegacao esperou " + elapsedMs + " ms");
    }

    @Test
    void deletingAnotherFileRefreshesLensesAndInvalidatesReferences() {
        responses.put("textDocument/references", "[]");
        service.openDocument(FILE, TEXT);
        service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 12);
        AtomicInteger refreshed = new AtomicInteger();
        service.setCodeLensRefreshListener(path -> refreshed.incrementAndGet());

        service.pathDeleted(FILE.resolveSibling("Other.java"));
        service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 12);

        assertTrue(refreshed.get() > 0);
        assertEquals(2, count("textDocument/references"));
    }

    @Test
    void closingASynchronizedDocumentSendsDidCloseAndDropsItsVersion() throws Exception {
        service.openDocument(FILE, TEXT);
        drainNotifications();

        service.closeDocument(FILE);

        JsonNode notification = awaitRequest("textDocument/didClose");
        assertEquals(FILE.toUri().toString(),
                notification.path("params").path("textDocument").path("uri").asText());
        assertEquals(JdtLsService.ANY_VERSION, service.documentVersion(FILE));
    }

    @Test
    void anEmptySemanticResultIsSuccessfulAndInvalidatedByAnotherFile() throws Exception {
        responses.put("textDocument/references", "[]");
        service.openDocument(FILE, TEXT);
        assertEquals(Status.COMPLETE, service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6).status());
        service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6);
        assertEquals(1, count("textDocument/references"));
        service.openDocument(FILE.resolveSibling("Other.java"), "class Other { Demo value; }");
        service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6);
        assertEquals(2, count("textDocument/references"));
        assertEquals(Status.COMPLETE,
                service.navigation(Kind.REFERENCES, FILE, TEXT + "old", 1, 6).status());
        assertEquals(1, count("textDocument/didChange"));
    }

    @Test
    void implementationUsesItsOwnEndpointAndKeepsAllDestinations() {
        responses.put("textDocument/implementation", "[{\"uri\":\"file:///demo/A.java\",\"range\":{\"start\":{\"line\":0,\"character\":1},\"end\":{\"line\":0,\"character\":2}}},"
                + "{\"uri\":\"file:///demo/A.java\",\"range\":{\"start\":{\"line\":0,\"character\":4},\"end\":{\"line\":0,\"character\":5}}}]");
        var result = service.navigation(Kind.IMPLEMENTATION, FILE, TEXT, 1, 6);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(2, result.locations().size());
        assertEquals(0, count("textDocument/definition"));
        assertEquals(0, count("textDocument/references"));
    }

    @Test
    void aWorkspaceBuildDoesNotTurnAnEmptySearchIntoACachedSuccess() throws Exception {
        responses.put("textDocument/references", "[]");
        service.openDocument(FILE, TEXT);
        assertEquals(Status.COMPLETE, service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6).status());
        var progress = JdtLsService.class.getDeclaredMethod("onProgress", JsonNode.class);
        progress.setAccessible(true);
        progress.invoke(service, JSON.readTree("{\"token\":\"build-1\",\"value\":{\"kind\":\"begin\",\"title\":\"Building workspace\"}}"));
        assertEquals(Status.INDEXING, service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6).status());
        progress.invoke(service, JSON.readTree("{\"token\":\"build-1\",\"value\":{\"kind\":\"end\"}}"));
        assertEquals(Status.COMPLETE, service.navigation(Kind.REFERENCES, FILE, TEXT, 1, 6).status());
        assertEquals(3, count("textDocument/references"));
    }

    @Test
    void resolvesEveryLensBeyondSixtyAndRefreshesAfterAnotherFileChanges() throws Exception {
        var lenses = JSON.createArrayNode();
        for (int i = 0; i < 130; i++) {
            var lens = lenses.addObject();
            lens.set("range", JSON.valueToTree(Map.of("start", Map.of("line", i, "character", 0),
                    "end", Map.of("line", i, "character", 1))));
            lens.set("data", JSON.valueToTree(List.of(FILE.toUri().toString(),
                    Map.of("line", i, "character", 0), i % 2 == 0 ? "references" : "implementations")));
        }
        responses.put("textDocument/codeLens", lenses.toString());
        responses.put("codeLens/resolve", "echo-lens");
        service.openDocument(FILE, TEXT);
        List<JdtLsService.JavaCodeLens> result = awaitLenses(130);
        assertEquals(65, result.stream().filter(l -> l.command().equals("java.show.implementations")).count());
        assertEquals(130, count("codeLens/resolve"));
        assertEquals(1, count("textDocument/codeLens"));
        service.openDocument(FILE.resolveSibling("Other.java"), "class Other {}");
        awaitLenses(130);
        assertEquals(260, count("codeLens/resolve"));
        service.closeDocument(FILE);
    }

    private List<JdtLsService.JavaCodeLens> awaitLenses(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var result = service.codeLenses(FILE, TEXT);
            if (result.size() == expected && result.stream().allMatch(l -> l.status() == Status.COMPLETE)) return result;
            Thread.sleep(20);
        }
        throw new AssertionError("not all lenses resolved: " + count("codeLens/resolve"));
    }

    @Test
    void organizeImportsIsSynchronizedBeforeFormatting() throws Exception {
        responses.put("textDocument/codeAction", "[{\"title\":\"Organize\",\"edit\":{\"changes\":{\""
                + FILE.toUri() + "\":[{\"range\":{\"start\":{\"line\":0,\"character\":0},"
                + "\"end\":{\"line\":0,\"character\":0}},\"newText\":\"// organized\\n\"}]}}}]");
        responses.put("textDocument/formatting", "[]");
        service.openDocument(FILE, TEXT);
        drainNotifications();
        String result = service.prepareSave(FILE, TEXT, true, true, 4, true);
        assertEquals("// organized\n" + TEXT, result);
        assertEquals(1, count("textDocument/formatting"));
        JsonNode change = awaitRequest("textDocument/didChange");
        assertEquals(2, change.path("params").path("textDocument").path("version").asInt());
        awaitRequest("textDocument/formatting");
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
                    String response = responses.get(method);
                    if (response.equals("echo-lens")) {
                        var lens = message.path("params").deepCopy();
                        String kind = lens.path("data").get(2).asText();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) lens).set("command", JSON.valueToTree(Map.of(
                                "title", "1 " + kind, "command", "java.show." + kind,
                                "arguments", List.of(FILE.toUri().toString(), Map.of("line", 0, "character", 0),
                                        List.of(Map.of("uri", FILE.toUri().toString(), "range", lens.get("range")))))));
                        response = lens.toString();
                    }
                    writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                            + ",\"result\":" + response + "}");
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    void delayedDefinitionCannotSurviveADocumentEdit() throws Exception {
        service.openDocument(FILE, TEXT);
        var pending = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                service.navigation(Kind.DEFINITION, FILE, TEXT, 1, 6));
        JsonNode oldRequest = awaitRequest("textDocument/definition");
        String edited = TEXT + "// new version\n";
        service.changeDocument(FILE, edited);
        assertEquals(Status.STALE, pending.get(2, TimeUnit.SECONDS).status());
        synchronized (toClient) {
            writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + oldRequest.get("id") + ",\"result\":[]}");
        }
        responses.put("textDocument/definition", "[]");
        assertEquals(Status.COMPLETE, service.navigation(Kind.DEFINITION, FILE, edited, 1, 6).status());
        assertEquals(2, count("textDocument/definition"));
    }

    @Test
    void navigationAfterUndoIsSentWithTheRestoredText() throws Exception {
        responses.put("textDocument/definition", "[]");
        service.openDocument(FILE, TEXT);
        service.changeDocument(FILE, TEXT + "// typed\n");

        assertEquals(Status.COMPLETE, service.navigation(Kind.DEFINITION, FILE, TEXT, 1, 6).status());
        assertEquals(TEXT, service.documentContent(FILE));
        assertEquals(1, count("textDocument/definition"));
    }

    @Test
    void anEditInAnotherFileDoesNotDiscardANavigationAnswer() throws Exception {
        service.openDocument(FILE, TEXT);
        service.openDocument(OTHER_FILE, "package demo;\nclass Foo { }\n");
        var pending = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                service.navigation(Kind.DEFINITION, FILE, TEXT, 1, 6));
        JsonNode request = awaitRequest("textDocument/definition");
        service.changeDocument(OTHER_FILE, "package demo;\nclass Foo { int x; }\n");
        synchronized (toClient) {
            writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":[{\"uri\":\""
                    + FILE.toUri() + "\",\"range\":{\"start\":{\"line\":1,\"character\":6},"
                    + "\"end\":{\"line\":1,\"character\":10}}}]}");
        }
        var result = pending.get(2, TimeUnit.SECONDS);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(1, result.locations().size());

        responses.put("textDocument/definition", "[]");
        service.navigation(Kind.DEFINITION, FILE, TEXT, 1, 6);
        assertEquals(2, count("textDocument/definition"));
    }

    @Test
    void aLateLensResolutionCannotReappearAfterClose() throws Exception {
        responses.put("textDocument/codeLens", "[{\"range\":{\"start\":{\"line\":1,\"character\":6},"
                + "\"end\":{\"line\":1,\"character\":10}},\"data\":[\"" + FILE.toUri()
                + "\",{\"line\":1,\"character\":6},\"implementations\"]}]");
        AtomicInteger refreshed = new AtomicInteger();
        service.setCodeLensRefreshListener(path -> refreshed.incrementAndGet());
        service.openDocument(FILE, TEXT);
        service.codeLenses(FILE, TEXT);
        JsonNode oldRequest = awaitRequest("codeLens/resolve");
        service.closeDocument(FILE);
        int afterClose = refreshed.get();
        synchronized (toClient) {
            writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + oldRequest.get("id")
                    + ",\"result\":{\"range\":{\"start\":{\"line\":1,\"character\":6},"
                    + "\"end\":{\"line\":1,\"character\":10}},\"command\":{\"title\":\"1 implementation\","
                    + "\"command\":\"java.show.implementations\"}}}");
        }
        Thread.sleep(150);
        assertEquals(afterClose, refreshed.get());
        assertEquals(JdtLsService.ANY_VERSION, service.documentVersion(FILE));
    }

    @Test
    void aConcurrentEditDuringSaveIsNeverReplacedByTheTransformation() throws Exception {
        service.openDocument(FILE, TEXT);
        var saving = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                service.prepareSave(FILE, TEXT, false, true, 4, true));
        JsonNode formatting = awaitRequest("textDocument/formatting");
        String edited = TEXT + "// typed during save\n";
        service.changeDocument(FILE, edited);
        synchronized (toClient) {
            writeFrame(toClient, "{\"jsonrpc\":\"2.0\",\"id\":" + formatting.get("id")
                    + ",\"result\":[{\"range\":{\"start\":{\"line\":0,\"character\":0},"
                    + "\"end\":{\"line\":0,\"character\":0}},\"newText\":\"// formatted\\n\"}]}");
        }
        saving.get(2, TimeUnit.SECONDS);
        responses.put("textDocument/definition", "[]");
        assertEquals(Status.COMPLETE, service.navigation(Kind.DEFINITION, FILE, edited, 1, 6).status());
        assertEquals(2, service.documentVersion(FILE));
    }

    @Test
    void umaClasseCriadaNoDiscoForcaAReaberturaDosBuffers() throws Exception {
        service.setExternalResyncDelayMs(40);
        service.openDocument(FILE, TEXT);
        int opened = service.documentVersion(FILE);
        drainNotifications();

        service.pathCreated(OTHER_FILE);

        JsonNode watched = awaitRequest("workspace/didChangeWatchedFiles");
        JsonNode change = watched.path("params").path("changes").get(0);
        assertEquals(OTHER_FILE.toUri().toString().toLowerCase(java.util.Locale.ROOT),
                change.path("uri").asText().toLowerCase(java.util.Locale.ROOT));
        assertEquals(1, change.path("type").asInt());

        awaitRequest("textDocument/didClose");
        JsonNode reopened = awaitRequest("textDocument/didOpen");
        assertEquals(FILE.toUri().toString(),
                reopened.path("params").path("textDocument").path("uri").asText());
        assertTrue(reopened.path("params").path("textDocument").path("version").asInt() > opened);
    }

    @Test
    void mudancasExternasSeguidasViramUmaUnicaNotificacao() throws Exception {
        service.setExternalResyncDelayMs(120);
        service.openDocument(FILE, TEXT);
        drainNotifications();

        for (int index = 0; index < 5; index++) {
            service.pathChanged(FILE.getParent().resolve("Outro" + index + ".java"));
        }

        JsonNode watched = awaitRequest("workspace/didChangeWatchedFiles");
        assertEquals(5, watched.path("params").path("changes").size());
        assertEquals(1, count("workspace/didChangeWatchedFiles"));
    }

    @Test
    void aRessincronizacaoExternaNaoApagaOsDiagnosticosPublicados() throws Exception {
        service.setExternalResyncDelayMs(40);
        service.openDocument(FILE, TEXT);
        drainNotifications();
        service.onPublishDiagnostics(diagnostics(service.documentVersion(FILE),
                "Foo cannot be resolved", 1, 0, 1, 5));

        service.pathCreated(OTHER_FILE);
        awaitRequest("textDocument/didOpen");

        assertEquals("Foo cannot be resolved",
                service.diagnostics(FILE).iterator().next().message());
    }

    @Test
    void eventosRecebidosAntesDoServidorFicarProntoSaoReenviados() throws Exception {
        service.setExternalResyncDelayMs(40);
        set("state", JdtLsService.State.STARTING);

        service.pathCreated(OTHER_FILE);
        Thread.sleep(200);
        assertEquals(0, count("workspace/didChangeWatchedFiles"));

        set("state", JdtLsService.State.READY);
        service.drainPendingWatchedFiles();

        JsonNode watched = awaitRequest("workspace/didChangeWatchedFiles");
        assertEquals(OTHER_FILE.toUri().toString().toLowerCase(java.util.Locale.ROOT),
                watched.path("params").path("changes").get(0).path("uri").asText()
                        .toLowerCase(java.util.Locale.ROOT));
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

    private static JsonNode diagnostics(Integer version, String message,
                                        int startLine, int startCol, int endLine, int endCol) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("uri", FILE.toUri().toString());
        if (version != null) {
            params.put("version", version);
        }
        params.put("diagnostics", List.of(Map.of(
                "range", Map.of(
                        "start", Map.of("line", startLine, "character", startCol),
                        "end", Map.of("line", endLine, "character", endCol)),
                "severity", 1,
                "message", message,
                "source", "Java")));
        return JSON.valueToTree(params);
    }

    private static JsonNode otherFileDiagnostics(String message) {
        return JSON.valueToTree(Map.of(
                "uri", OTHER_FILE.toUri().toString(),
                "diagnostics", List.of(Map.of(
                        "range", Map.of(
                                "start", Map.of("line", 0, "character", 0),
                                "end", Map.of("line", 0, "character", 4)),
                        "severity", 2,
                        "message", message,
                        "source", "Java"))));
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
                 "codeActionProvider":true,"codeLensProvider":{"resolveProvider":true},"documentFormattingProvider":true,
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
