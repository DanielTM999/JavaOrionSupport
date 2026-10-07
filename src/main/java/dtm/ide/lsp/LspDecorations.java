package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.lsp.api.JavaCodeLens;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.documentId;

final class LspDecorations {

    private static final int MAX_CODE_LENS_RESOLVE = 8;
    private static final long CODE_LENS_RETRY_TIMEOUT_MS = 20_000;
    private static final int MAX_CODE_LENS_RETRIES = 2;
    private static final long CODE_LENS_WORK_REFRESH_COOLDOWN_MS = 2_000;
    private static final long CODE_LENS_EDIT_REFRESH_DELAY_MS = 800;

    static final List<String> TOKEN_TYPES = List.of(
            "namespace", "class", "interface", "enum", "enumMember", "type", "typeParameter",
            "method", "property", "variable", "parameter", "record", "recordComponent",
            "annotation", "annotationMember", "modifier", "keyword", "comment", "string",
            "number", "operator"
    );

    static final List<String> TOKEN_MODIFIERS = List.of(
            "abstract", "static", "final", "deprecated", "declaration", "documentation",
            "public", "private", "protected", "native", "generic", "typeArgument",
            "importDeclaration", "constructor");

    interface Host {
        ServerCapabilities capabilities();

        boolean isReady();

        boolean isWarmingUp();

        boolean syncBeforeRequest(Path filePath, String text);

        boolean isCurrentText(Path filePath, String text);

        long workspaceRevision();

        JdtDocumentStore documents();

        LspJsonRpcClient client();

        void codeLensRefreshed(Path path);

        JavaCodeLens unresolvedLens(JsonNode node, Status status);
    }

    private final LspRequests requests;
    private final Executor executor;
    private final Host host;
    private final Map<String, LensWork> codeLensCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> codeLensRefreshTickets = new ConcurrentHashMap<>();
    private final AtomicLong lastCodeLensWorkRefresh = new AtomicLong();

    LspDecorations(LspRequests requests, Executor executor, Host host) {
        this.requests = requests;
        this.executor = executor;
        this.host = host;
    }

    private static final class LensWork {
        final String text;
        final long revision;
        final int version;
        volatile List<JavaCodeLens> lenses = List.of();
        final Set<CompletableFuture<JsonNode>> pending = ConcurrentHashMap.newKeySet();
        LensWork(String text, long revision, int version) {
            this.text = text; this.revision = revision; this.version = version;
        }
        void cancel() { pending.forEach(f -> f.cancel(false)); }
    }

    CompletableFuture<List<InlayHint>> inlayHintsAsync(Path filePath, String text,
                                                               int firstLine, int lastLine) {
        if (!host.capabilities().inlayHint() || host.isWarmingUp() || !host.syncBeforeRequest(filePath, text)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return requests.requestAsync("textDocument/inlayHint", inlayHintParams(filePath, firstLine, lastLine),
                REQUEST_TIMEOUT_MS, false)
                .thenApply(result -> host.isCurrentText(filePath, text) ? inlayHintsOf(result) : List.<InlayHint>of());
    }

    List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine) {
        if (!host.capabilities().inlayHint()) {
            return List.of();
        }
        if (!host.syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        JsonNode result = requests.requestBackground("textDocument/inlayHint",
                inlayHintParams(filePath, firstLine, lastLine), REQUEST_TIMEOUT_MS);
        if (!host.isCurrentText(filePath, text)) {
            return List.of();
        }
        return inlayHintsOf(result);
    }

    private static Map<String, Object> inlayHintParams(Path filePath, int firstLine, int lastLine) {
        return Map.of(
                "textDocument", documentId(filePath),
                "range", Map.of(
                        "start", Map.of("line", Math.max(0, firstLine), "character", 0),
                        "end", Map.of("line", Math.max(0, lastLine), "character", 0)));
    }

    private static List<InlayHint> inlayHintsOf(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<InlayHint> hints = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            InlayHint hint = LspConversions.inlayHint(node);
            if (hint != null) {
                hints.add(hint);
            }
        }
        return hints;
    }

    List<JavaCodeLens> codeLenses(Path filePath, String text) {
        if (!host.capabilities().codeLens() || !host.isReady() || host.isWarmingUp()
                || !host.syncBeforeRequest(filePath, text)) return List.of();
        String uri = LspConversions.toUri(filePath);
        String snapshot = text == null ? "" : text;
        long revision = host.workspaceRevision();
        int version = host.documents().version(uri);
        AtomicBoolean created = new AtomicBoolean();
        LensWork work = codeLensCache.compute(uri, (key, previous) -> {
            if (previous != null && previous.text.equals(snapshot) && previous.revision == revision
                    && previous.version == version) return previous;
            if (previous != null) previous.cancel();
            created.set(true);
            return new LensWork(snapshot, revision, version);
        });
        if (created.get()) executor.execute(() -> resolveCodeLenses(filePath, uri, work));
        return work.lenses;
    }

    private boolean currentLensWork(String uri, LensWork work) {
        return codeLensCache.get(uri) == work && host.workspaceRevision() == work.revision
                && host.documents().version(uri) == work.version && work.text.equals(host.documents().content(uri)) && host.isReady();
    }

    private void resolveCodeLenses(Path file, String uri, LensWork work) {
        try {
            JsonNode response = requests.requestBackground("textDocument/codeLens",
                    Map.of("textDocument", Map.of("uri", uri)), REQUEST_TIMEOUT_MS * 2);
            if (!currentLensWork(uri, work)) return;
            if (response == null || !response.isArray()) {
                codeLensCache.remove(uri, work);
                return;
            }
            List<JsonNode> raw = new ArrayList<>();
            response.forEach(raw::add);
            List<JavaCodeLens> resolved = new ArrayList<>();
            for (JsonNode node : raw) {
                JavaCodeLens lens = LspConversions.codeLens(node);
                resolved.add(lens == null ? host.unresolvedLens(node, Status.INDEXING) : lens);
            }
            publishLenses(file, uri, work, resolved);
            for (int attempt = 0; attempt <= MAX_CODE_LENS_RETRIES && currentLensWork(uri, work); attempt++) {
                List<Integer> remaining = new ArrayList<>();
                for (int i = 0; i < resolved.size(); i++) {
                    if (resolved.get(i).status() != Status.COMPLETE) remaining.add(i);
                }
                if (remaining.isEmpty()) break;
                for (int start = 0; start < remaining.size() && currentLensWork(uri, work); start += MAX_CODE_LENS_RESOLVE) {
                    Map<Integer, CompletableFuture<JsonNode>> batch = new LinkedHashMap<>();
                    LspJsonRpcClient rpc = host.client();
                    if (rpc == null) return;
                    for (int i = start; i < Math.min(start + MAX_CODE_LENS_RESOLVE, remaining.size()); i++) {
                        int index = remaining.get(i);
                        CompletableFuture<JsonNode> future = rpc.request("codeLens/resolve", raw.get(index));
                        batch.put(index, future); work.pending.add(future);
                    }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CODE_LENS_RETRY_TIMEOUT_MS);
                    for (var entry : batch.entrySet()) {
                        CompletableFuture<JsonNode> future = entry.getValue();
                        try {
                            JsonNode answer = future.isDone() ? future.getNow(null)
                                    : future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                            JavaCodeLens lens = LspConversions.codeLens(answer);
                            resolved.set(entry.getKey(), lens == null
                                    ? host.unresolvedLens(raw.get(entry.getKey()), Status.FAILED) : lens);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); return;
                        } catch (Exception failure) {
                            resolved.set(entry.getKey(), host.unresolvedLens(raw.get(entry.getKey()), Status.FAILED));
                            future.cancel(false);
                        } finally { work.pending.remove(future); }
                    }
                    publishLenses(file, uri, work, resolved);
                }
            }
        } finally { work.cancel(); }
    }

    private void publishLenses(Path file, String uri, LensWork work, List<JavaCodeLens> lenses) {
        if (!currentLensWork(uri, work)) return;
        work.lenses = List.copyOf(lenses);
        host.codeLensRefreshed(file);
    }

    void refreshCodeLensesAfterWork() {
        if (!host.isReady()) return;
        long now = System.currentTimeMillis();
        long previous = lastCodeLensWorkRefresh.get();
        if (now - previous < CODE_LENS_WORK_REFRESH_COOLDOWN_MS
                || !lastCodeLensWorkRefresh.compareAndSet(previous, now)) return;
        host.documents().uris().forEach(uri -> {
            if (!codeLensCache.containsKey(uri)) {
                Path path = LspConversions.toPath(uri);
                if (path != null) host.codeLensRefreshed(path);
            }
        });
    }

    CompletableFuture<List<SemanticToken>> semanticTokensAsync(Path filePath, String text) {
        if (!host.capabilities().semanticTokens() || host.isWarmingUp() || !host.syncBeforeRequest(filePath, text)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return requests.requestAsync("textDocument/semanticTokens/full", Map.of("textDocument", documentId(filePath)),
                REQUEST_TIMEOUT_MS, false)
                .thenApply(result -> result == null || !host.isCurrentText(filePath, text) ? List.<SemanticToken>of()
                        : SemanticTokenDecoder.decode(result.get("data"), TOKEN_TYPES, TOKEN_MODIFIERS));
    }

    List<SemanticToken> semanticTokens(Path filePath, String text) {
        if (!host.capabilities().semanticTokens()) {
            return List.of();
        }
        if (!host.syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        JsonNode result = requests.requestBackground("textDocument/semanticTokens/full",
                Map.of("textDocument", documentId(filePath)), REQUEST_TIMEOUT_MS);
        if (result == null || !host.isCurrentText(filePath, text)) {
            return List.of();
        }
        return SemanticTokenDecoder.decode(result.get("data"), TOKEN_TYPES, TOKEN_MODIFIERS);
    }

    void discardCodeLenses(String uri) {
        LensWork old = codeLensCache.remove(uri);
        if (old != null) old.cancel();
    }

    void scheduleCodeLensRefresh(Path filePath, String uri) {
        if (filePath == null || uri == null) {
            return;
        }
        AtomicLong ticket = codeLensRefreshTickets.computeIfAbsent(uri, ignored -> new AtomicLong());
        long currentTicket = ticket.incrementAndGet();
        CompletableFuture.delayedExecutor(CODE_LENS_EDIT_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS, executor)
                .execute(() -> {
                    AtomicLong latest = codeLensRefreshTickets.get(uri);
                    if (latest != ticket || latest.get() != currentTicket
                            || host.documents().content(uri) == null) {
                        return;
                    }
                    host.codeLensRefreshed(filePath);
                });
    }

    void reset() {
        codeLensCache.values().forEach(LensWork::cancel);
        codeLensCache.clear();
        codeLensRefreshTickets.clear();
    }

    void discardAll() {
        codeLensCache.values().forEach(LensWork::cancel);
        codeLensCache.clear();
    }

    void forgetRefreshTicket(String uri) {
        codeLensRefreshTickets.remove(uri);
    }
}
