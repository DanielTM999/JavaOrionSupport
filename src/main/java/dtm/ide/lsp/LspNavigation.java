package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static dtm.ide.lsp.LspRequests.INTERACTIVE_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.documentId;
import static dtm.ide.lsp.LspRequests.positionParams;

@Slf4j
final class LspNavigation {

    private static final ObjectMapper JSON = new ObjectMapper();

    interface Host {
        ServerCapabilities capabilities();

        boolean isInteractive();

        boolean isReady();

        boolean isWarmingUp();

        boolean hasWorkspaceWork();

        boolean syncBeforeRequest(Path filePath, String text, boolean authoritative);

        boolean isCurrentText(Path filePath, String text);

        long workspaceRevision();

        JdtDocumentStore documents();
    }

    private record SymbolCache(String text, List<DocumentSymbol> symbols) { }

    private final LspRequests requests;
    private final Host host;
    private final Map<String, SymbolCache> symbolCache = new ConcurrentHashMap<>();
    private final Map<String, List<Location>> navigationCache = new ConcurrentHashMap<>();

    LspNavigation(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
    }

    CompletableFuture<HoverInfo> hoverAsync(Path filePath, String text, int line, int col) {
        return requests.requestAtInteractiveAsync("textDocument/hover", filePath, text, line, col)
                .thenApply(result -> host.isCurrentText(filePath, text) ? LspConversions.hover(result) : null);
    }

    CompletableFuture<SignatureHelp> signatureHelpAsync(Path filePath, String text, int line, int col) {
        if (!host.capabilities().signatureHelp()) {
            return CompletableFuture.completedFuture(null);
        }
        return requests.requestAtInteractiveAsync("textDocument/signatureHelp", filePath, text, line, col)
                .thenApply(result -> host.isCurrentText(filePath, text) ? LspConversions.signatureHelp(result) : null);
    }

    CompletableFuture<List<Range>> selectionRangesAsync(Path filePath, String text, int line, int col) {
        if (!host.syncBeforeRequest(filePath, text, false)) {
            return CompletableFuture.completedFuture(List.of());
        }
        Map<String, Object> params = Map.of(
                "textDocument", documentId(filePath),
                "positions", List.of(Map.of("line", Math.max(0, line), "character", Math.max(0, col))));
        return requests.requestAsync("textDocument/selectionRange", params, requests.interactiveTimeoutMs(), true)
                .thenApply(result -> host.isCurrentText(filePath, text)
                        ? LspConversions.selectionChain(result) : List.<Range>of());
    }

    HoverInfo hover(Path filePath, String text, int line, int col) {
        JsonNode result = requests.requestAtInteractive("textDocument/hover", filePath, text, line, col);
        return host.isCurrentText(filePath, text) ? LspConversions.hover(result) : null;
    }

    SignatureHelp signatureHelp(Path filePath, String text, int line, int col) {
        if (!host.capabilities().signatureHelp()) {
            return null;
        }
        return LspConversions.signatureHelp(
                requests.requestAtInteractive("textDocument/signatureHelp", filePath, text, line, col));
    }

    List<Location> definitions(Path filePath, String text, int line, int col) {
        return definitions(filePath, text, line, col, false);
    }

    List<Location> definitionsInteractive(Path filePath, String text, int line, int col) {
        return definitions(filePath, text, line, col, true);
    }

    private List<Location> definitions(Path filePath, String text, int line, int col,
                                       boolean interactive) {
        if (!host.capabilities().definition()) {
            return List.of();
        }
        return navigate("textDocument/definition", filePath, text, line, col, interactive, null);
    }

    private List<Location> navigate(String method, Path filePath, String text, int line, int col,
                                    boolean interactive, Map<String, Object> extraParams) {
        return navigateResult(method, filePath, text, line, col, interactive, extraParams).locations();
    }

    Result navigation(Kind kind, Path file, String text, int line, int col) {
        return navigation(kind, file, text, line, col, REQUEST_TIMEOUT_MS * 3);
    }

    Result navigation(Kind kind, Path file, String text, int line, int col, long timeoutMs) {
        boolean supported = switch (kind) {
            case DEFINITION -> host.capabilities().definition();
            case IMPLEMENTATION -> host.capabilities().implementation();
            case REFERENCES -> host.capabilities().references();
        };
        if (!supported || !host.isInteractive()) return Result.of(Status.UNAVAILABLE);
        return navigateResult(kind.method(), file, text, line, col, true,
                kind == Kind.REFERENCES ? Map.of("context", Map.of("includeDeclaration", false)) : null,
                timeoutMs, true);
    }

    private Result navigateResult(String method, Path filePath, String text, int line, int col,
                                  boolean interactive, Map<String, Object> extraParams) {
        return navigateResult(method, filePath, text, line, col, interactive, extraParams, REQUEST_TIMEOUT_MS, false);
    }

    private Result navigateResult(String method, Path filePath, String text, int line, int col,
                                  boolean interactive, Map<String, Object> extraParams, long timeoutMs,
                                  boolean authoritative) {
        if (filePath == null) return Result.of(Status.UNAVAILABLE);
        String uri = LspConversions.toUri(filePath);
        if (!host.syncBeforeRequest(filePath, text, authoritative)) return Result.of(Status.STALE);
        int version = host.documents().version(uri);
        long revision = host.workspaceRevision();
        String key = method + "|" + uri + "|" + version + "|" + line + "|" + col + "|" + revision;
        List<Location> cached = navigationCache.get(key);
        if (cached != null && host.isReady() && !host.isWarmingUp() && !host.hasWorkspaceWork()) {
            log.debug("navegacao {} servida do cache para {}", method, uri);
            return new Result(Status.COMPLETE, cached);
        }
        Map<String, Object> params = positionParams(filePath, line, col);
        if (extraParams != null) params.putAll(extraParams);
        long requestStart = System.nanoTime();
        JsonNode response = requests.requestCoalesced(method, params, timeoutMs, key, interactive);
        if (log.isDebugEnabled()) {
            log.debug("navegacao {} para {} respondeu em {}ms (cache miss)",
                    method, uri, (System.nanoTime() - requestStart) / 1_000_000L);
        }
        if (host.documents().version(uri) != version
                || text != null && !text.equals(host.documents().content(uri))) {
            return Result.of(Status.STALE);
        }
        boolean indexing = !host.isReady() || host.isWarmingUp() || host.hasWorkspaceWork();
        if (response == null) return Result.of(indexing ? Status.INDEXING : Status.FAILED);
        List<Location> locations = JavaNavigation.unique(LspConversions.locations(response));
        if (indexing) return new Result(Status.INDEXING, locations);
        if (host.workspaceRevision() == revision) navigationCache.put(key, locations);
        return new Result(Status.COMPLETE, locations);
    }

    boolean supportsTypeHierarchy() {
        return host.capabilities().typeHierarchy();
    }

    boolean supportsFoldingRanges() {
        return host.capabilities().foldingRange();
    }

    boolean supportsCallHierarchy() {
        return host.capabilities().callHierarchy();
    }

    List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int col) {
        if (!host.capabilities().callHierarchy()) {
            return List.of();
        }
        if (!host.syncBeforeRequest(filePath, text, false)) {
            return List.of();
        }
        JsonNode result = requests.request("textDocument/prepareCallHierarchy",
                positionParams(filePath, line, col), REQUEST_TIMEOUT_MS);
        return callHierarchyItems(result);
    }

    CompletableFuture<List<FoldRange>> foldingRangesAsync(Path filePath, String text) {
        if (!host.capabilities().foldingRange() || !host.isReady() || !host.syncBeforeRequest(filePath, text, false)) {
            return CompletableFuture.completedFuture(null);
        }
        return requests.requestAsync("textDocument/foldingRange", Map.of("textDocument", documentId(filePath)),
                REQUEST_TIMEOUT_MS, false)
                .thenApply(result -> result == null || !host.isCurrentText(filePath, text)
                        ? null : LspConversions.foldRanges(result));
    }

    List<TypeHierarchyItem> prepareTypeHierarchy(Path filePath, String text, int line, int col) {
        if (!host.capabilities().typeHierarchy() || !host.syncBeforeRequest(filePath, text, false)) {
            return List.of();
        }
        return LspConversions.typeHierarchyItems(requests.request("textDocument/prepareTypeHierarchy",
                positionParams(filePath, line, col), REQUEST_TIMEOUT_MS));
    }

    List<TypeHierarchyItem> supertypes(TypeHierarchyItem item) {
        return typeHierarchy("typeHierarchy/supertypes", item);
    }

    List<TypeHierarchyItem> subtypes(TypeHierarchyItem item) {
        return typeHierarchy("typeHierarchy/subtypes", item);
    }

    private List<TypeHierarchyItem> typeHierarchy(String method, TypeHierarchyItem item) {
        if (!host.capabilities().typeHierarchy() || item == null) {
            return List.of();
        }
        Object lspItem = item.data() instanceof JsonNode original ? original : serializeTypeHierarchyItem(item);
        return LspConversions.typeHierarchyItems(requests.request(method, Map.of("item", lspItem), REQUEST_TIMEOUT_MS));
    }

    private static Map<String, Object> serializeTypeHierarchyItem(TypeHierarchyItem item) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("name", item.name());
        serialized.put("kind", item.kind());
        serialized.put("uri", LspConversions.toUri(item.file()));
        serialized.put("range", LspConversions.toLspRange(item.range()));
        serialized.put("selectionRange", LspConversions.toLspRange(item.selectionRange()));
        if (item.detail() != null && !item.detail().isBlank()) {
            serialized.put("detail", item.detail());
        }
        return serialized;
    }

    List<CallHierarchyCall> incomingCalls(CallHierarchyItem item) {
        return calls("callHierarchy/incomingCalls", item, "from");
    }

    List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item) {
        return calls("callHierarchy/outgoingCalls", item, "to");
    }

    private List<CallHierarchyCall> calls(String method, CallHierarchyItem item, String itemField) {
        if (!host.capabilities().callHierarchy() || item == null) {
            return List.of();
        }
        Map<String, Object> serialized = serializeCallHierarchyItem(item);
        if (serialized == null) {
            return List.of();
        }
        JsonNode result = requests.request(method, Map.of("item", serialized), REQUEST_TIMEOUT_MS);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CallHierarchyCall> calls = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            CallHierarchyCall call = LspConversions.callHierarchyCall(node, itemField);
            if (call != null) {
                calls.add(call);
            }
        }
        return calls;
    }

    private List<CallHierarchyItem> callHierarchyItems(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CallHierarchyItem> items = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            CallHierarchyItem item = LspConversions.callHierarchyItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> serializeCallHierarchyItem(CallHierarchyItem item) {
        Map<String, Object> data = item.data();
        Object uri = data == null ? null : data.get("uri");
        if (uri == null) {
            uri = LspConversions.toUri(item.filePath());
        }
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("name", item.name());
        serialized.put("kind", LspConversions.toLspSymbolKind(item.kind()));
        serialized.put("uri", uri.toString());
        serialized.put("range", LspConversions.toLspRange(item.range()));
        serialized.put("selectionRange", LspConversions.toLspRange(item.selectionRange()));
        if (item.detail() != null && !item.detail().isBlank()) {
            serialized.put("detail", item.detail());
        }
        Object raw = data == null ? null : data.get("data");
        if (raw != null && !raw.toString().isBlank()) {
            try {
                serialized.put("data", JSON.readTree(raw.toString()));
            } catch (Exception e) {
                log.debug("Campo data da hierarquia ilegivel: {}", e.getMessage());
            }
        }
        return serialized;
    }

    List<DocumentSymbol> documentSymbols(Path filePath, String text) {
        return documentSymbols(filePath, text, false);
    }

    List<DocumentSymbol> documentSymbolsInteractive(Path filePath, String text) {
        return documentSymbols(filePath, text, true);
    }

    private List<DocumentSymbol> documentSymbols(Path filePath, String text, boolean interactive) {
        if (!host.capabilities().documentSymbol()) {
            return List.of();
        }
        String uri = LspConversions.toUri(filePath);
        String requestedText = text == null ? "" : text;
        if (!host.syncBeforeRequest(filePath, text, false)) {
            return List.of();
        }
        SymbolCache cached = symbolCache.get(uri);
        if (cached != null && cached.text().equals(requestedText)) {
            return cached.symbols();
        }
        int version = host.documents().version(uri);
        String key = "symbols|" + uri + "|" + version;
        JsonNode result = interactive
                ? requests.requestCoalescedInteractive("textDocument/documentSymbol",
                        Map.of("textDocument", documentId(filePath)), INTERACTIVE_TIMEOUT_MS, key)
                : requests.requestCoalescedBackground("textDocument/documentSymbol",
                        Map.of("textDocument", documentId(filePath)), REQUEST_TIMEOUT_MS, key);
        List<DocumentSymbol> symbols = List.copyOf(LspConversions.documentSymbols(result));
        if (requestedText.equals(host.documents().content(uri))) {
            symbolCache.put(uri, new SymbolCache(requestedText, symbols));
            return symbols;
        }
        return List.of();
    }

    List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col) {
        return documentHighlights(filePath, text, line, col, false);
    }

    List<DocumentHighlight> documentHighlightsInteractive(Path filePath, String text,
                                                                 int line, int col) {
        return documentHighlights(filePath, text, line, col, true);
    }

    private List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col,
                                                       boolean interactive) {
        if (!host.capabilities().documentHighlight()) {
            return List.of();
        }
        JsonNode result = interactive
                ? requests.requestAtInteractive("textDocument/documentHighlight", filePath, text, line, col)
                : requests.requestAt("textDocument/documentHighlight", filePath, text, line, col);
        if (result == null || !result.isArray() || !host.isCurrentText(filePath, text)) {
            return List.of();
        }
        List<DocumentHighlight> highlights = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            Range range = LspConversions.range(node.get("range"));
            DocumentHighlight.Kind kind = switch (node.path("kind").asInt(1)) {
                case 2 -> DocumentHighlight.Kind.READ;
                case 3 -> DocumentHighlight.Kind.WRITE;
                default -> DocumentHighlight.Kind.TEXT;
            };
            highlights.add(new DocumentHighlight(range, kind));
        }
        return highlights;
    }

    void forgetSymbols(String uri) {
        symbolCache.remove(uri);
    }

    void clearSymbols() {
        symbolCache.clear();
    }

    void clearCache() {
        navigationCache.clear();
    }

    void clearCacheFor(String uri) {
        navigationCache.keySet().removeIf(key -> key.contains("|" + uri + "|"));
    }
}
