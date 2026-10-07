package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.api.CompletionTrigger;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dtm.ide.lsp.LspRequests.positionParams;
import static dtm.ide.lsp.LspText.offsetIn;
import static dtm.ide.lsp.api.JavaLanguageServer.ANY_VERSION;

@Slf4j
final class LspCompletion {

    private static final long INDEXING_COMPLETION_TIMEOUT_MS = 750;
    private static final long READY_COMPLETION_TIMEOUT_MS = 1_500;
    private static final int MAX_COMPLETION_ITEMS = 80;

    interface Host {
        ServerCapabilities capabilities();

        boolean isInteractive();

        boolean isReady();

        boolean syncBeforeRequest(Path filePath, String text);

        JdtDocumentStore documents();

        LspJsonRpcClient client();

        void lateCompletion(Path filePath, int line, int col);
    }

    private record CompletionCache(String text, int line, int col, String linePrefix,
                                   boolean incomplete, List<AutoCompleteItem> items) {
    }

    record CompletionAnswer(List<AutoCompleteItem> items, boolean incomplete) {
    }

    private final LspRequests requests;
    private final ExecutorService executor;
    private final Host host;
    private final Map<String, CompletionCache> completionCache = new ConcurrentHashMap<>();

    LspCompletion(LspRequests requests, ExecutorService executor, Host host) {
        this.requests = requests;
        this.executor = executor;
        this.host = host;
    }

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col) {
        return complete(filePath, text, line, col, CompletionTrigger.INVOKED, null, ANY_VERSION);
    }

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col,
                                           CompletionTrigger trigger, Character triggerCharacter,
                                           int expectedVersion) {
        return complete(filePath, text, line, col, trigger, triggerCharacter, expectedVersion, false);
    }

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col,
                                           CompletionTrigger trigger, Character triggerCharacter,
                                           int expectedVersion, boolean announceLateResult) {
        CompletableFuture<List<AutoCompleteItem>> pending = completeAsync(filePath, text, line, col,
                trigger, triggerCharacter, expectedVersion);
        long timeout = host.isReady() ? READY_COMPLETION_TIMEOUT_MS : INDEXING_COMPLETION_TIMEOUT_MS;
        try {
            return pending.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            log.debug("Completion sem resposta em {} ms; aguardando em segundo plano", timeout);
            if (announceLateResult) {
                pending.thenAccept(late -> announceLateCompletion(filePath, line, col, late));
            }
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception failure) {
            requests.logRequestFailure("textDocument/completion", failure);
            return List.of();
        }
    }

    CompletableFuture<List<AutoCompleteItem>> completeAsync(Path filePath, String text, int line,
                                                                   int col, CompletionTrigger trigger,
                                                                   Character triggerCharacter,
                                                                   int expectedVersion) {
        if (!host.isInteractive()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String uri = LspConversions.toUri(filePath);
        String requestedText = text == null ? "" : text;
        int versionBeforeSync = host.documents().version(uri);
        if (!host.syncBeforeRequest(filePath, text)) {
            return CompletableFuture.completedFuture(List.of());
        }
        int expected = expectedVersion == versionBeforeSync ? host.documents().version(uri) : expectedVersion;
        CompletionCache cached = completionCache.get(uri);
        if (cached != null && cached.text().equals(requestedText)
                && cached.line() == line && cached.col() == col) {
            return CompletableFuture.completedFuture(cached.items());
        }
        int version = host.documents().version(uri);
        if (isStaleVersion(expected, version)) {
            log.debug("Completion descartada antes do envio: versao {} esperada, {} atual",
                    expected, version);
            return CompletableFuture.completedFuture(List.of());
        }
        CompletionTrigger kind = trigger == null ? CompletionTrigger.INVOKED : trigger;
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !host.isInteractive()) {
            return CompletableFuture.completedFuture(List.of());
        }
        long started = System.nanoTime();
        String key = "completion|" + uri + "|" + version + "|" + line + "|" + col;
        CompletableFuture<JsonNode> request = requests.shareInFlight(key, ignored ->
                rpc.request("textDocument/completion",
                        completionParams(filePath, line, col, kind, triggerCharacter)));
        CompletableFuture<List<AutoCompleteItem>> items = request.handle((result, error) -> {
            if (error != null) {
                if (!(error instanceof java.util.concurrent.CancellationException)
                        && !(error.getCause() instanceof java.util.concurrent.CancellationException)) {
                    requests.logRequestFailure("textDocument/completion",
                            error instanceof Exception exception ? exception : new Exception(error));
                }
                return List.<AutoCompleteItem>of();
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            int current = host.documents().version(uri);
            if (!requestedText.equals(host.documents().content(uri)) || current != version
                    || isStaleVersion(expected, current)) {
                log.debug("Completion descartada: documento mudou durante a requisicao ({} ms)",
                        elapsedMs);
                return List.<AutoCompleteItem>of();
            }
            CompletionAnswer answer = completionItems(result);
            completionCache.put(uri, new CompletionCache(requestedText, line, col,
                    linePrefixAtWordStart(requestedText, line, col), answer.incomplete(), answer.items()));
            log.debug("Completion com {} item(ns) em {} ms (acionamento {}, incompleta={})",
                    answer.items().size(), elapsedMs, kind, answer.incomplete());
            return answer.items();
        });
        items.whenComplete((ignored, error) -> {
            if (items.isCancelled()) {
                request.cancel(false);
            }
        });
        return items;
    }

    CompletableFuture<AutoCompleteItem> resolveCompletionAsync(AutoCompleteItem item) {
        if (item == null || !host.capabilities().completionResolve() || !(item.data() instanceof JsonNode raw)) {
            return CompletableFuture.completedFuture(item);
        }
        return requests.requestAsync("completionItem/resolve", raw, requests.interactiveTimeoutMs(), true)
                .thenApply(resolved -> LspConversions.withResolvedDocumentation(item, resolved));
    }

    private void announceLateCompletion(Path filePath, int line, int col, List<AutoCompleteItem> late) {
        if (late == null || late.isEmpty()) {
            return;
        }
        log.debug("Completion atrasada chegou com {} item(ns)", late.size());
        try {
            host.lateCompletion(filePath, line, col);
        } catch (Exception e) {
            log.debug("Falha ao reabrir a completion atrasada: {}", e.getMessage());
        }
    }

    static CompletionAnswer completionItems(JsonNode result) {
        if (result == null) {
            return new CompletionAnswer(List.of(), false);
        }
        JsonNode items = result.isArray() ? result : result.get("items");
        boolean incomplete = !result.isArray() && result.path("isIncomplete").asBoolean(false);
        if (items == null || !items.isArray()) {
            return new CompletionAnswer(List.of(), incomplete);
        }
        List<SortableCompletion> sortable = new ArrayList<>(items.size());
        for (JsonNode node : items) {
            if (node == null || node.path("label").asText("").isBlank()) {
                continue;
            }
            AutoCompleteItem item = LspConversions.completionItem(node);
            if (item != null) sortable.add(new SortableCompletion(completionSortKey(node), item));
        }
        sortable.sort(Comparator.comparing(SortableCompletion::key));
        if (sortable.size() > MAX_COMPLETION_ITEMS) {
            incomplete = true;
        }
        List<AutoCompleteItem> completions = sortable.stream()
                .limit(MAX_COMPLETION_ITEMS)
                .map(SortableCompletion::item)
                .toList();
        return new CompletionAnswer(completions, incomplete);
    }

    private record SortableCompletion(String key, AutoCompleteItem item) {
    }

    static String completionSortKey(JsonNode node) {
        for (String field : List.of("sortText", "filterText", "label")) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static String linePrefixAtWordStart(String text, int line, int col) {
        String lineText = lineOf(text, line);
        int end = Math.max(0, Math.min(col, lineText.length()));
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(lineText.charAt(start - 1))) {
            start--;
        }
        return lineText.substring(0, start);
    }

    private static String lineOf(String text, int line) {
        if (text == null || line < 0) {
            return "";
        }
        int start = 0;
        for (int current = 0; current < line; current++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) {
                return "";
            }
            start = newline + 1;
        }
        int end = text.indexOf('\n', start);
        String lineText = end < 0 ? text.substring(start) : text.substring(start, end);
        return lineText.endsWith("\r") ? lineText.substring(0, lineText.length() - 1) : lineText;
    }

    static boolean isStaleVersion(int expectedVersion, int currentVersion) {
        return expectedVersion != ANY_VERSION && expectedVersion != currentVersion;
    }

    private static Map<String, Object> completionParams(Path filePath, int line, int col,
                                                        CompletionTrigger trigger,
                                                        Character triggerCharacter) {
        Map<String, Object> params = positionParams(filePath, line, col);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("triggerKind", trigger.lspKind());
        if (trigger == CompletionTrigger.TRIGGER_CHARACTER && triggerCharacter != null) {
            context.put("triggerCharacter", String.valueOf(triggerCharacter.charValue()));
        }
        params.put("context", context);
        return params;
    }

    List<AutoCompleteItem> cachedCompletions(Path filePath, String text, int line, int col) {
        if (filePath == null) return List.of();
        CompletionCache cached = completionCache.get(LspConversions.toUri(filePath));
        return isReusable(cached, text, line, col) ? cached.items() : List.of();
    }

    private static boolean isReusable(CompletionCache cached, String text, int line, int col) {
        if (cached == null || cached.line() != line || col < cached.col()) {
            return false;
        }
        if (cached.text().equals(text) && cached.col() == col) {
            return true;
        }
        return !cached.incomplete() && extendsCachedWord(cached.text(), line, cached.col(), text, col)
                && cached.items().stream().allMatch(item -> item.replacementRange() == null && !item.hasAdditionalTextEdits());
    }

    List<AutoCompleteItem> reusableCompletions(Path filePath, String text, int line, int col) {
        if (filePath == null || text == null) return List.of();
        CompletionCache cached = completionCache.get(LspConversions.toUri(filePath));
        if (cached == null || cached.incomplete() || cached.line() != line) return List.of();
        return extendsCachedWord(cached.text(), line, cached.col(), text, col)
                && cached.items().stream().allMatch(item -> item.replacementRange() == null && !item.hasAdditionalTextEdits())
                ? cached.items()
                : List.of();
    }

    static boolean extendsCachedWord(String cachedText, int line, int cachedCol, String text, int col) {
        if (cachedText == null || text == null || col < cachedCol) {
            return false;
        }
        int cachedOffset = offsetIn(cachedText, new Position(line, cachedCol));
        int offset = offsetIn(text, new Position(line, col));
        if (cachedOffset < 0 || offset < 0) {
            return false;
        }
        int typed = offset - cachedOffset;
        if (typed != col - cachedCol || text.length() - cachedText.length() != typed) {
            return false;
        }
        if (!text.regionMatches(0, cachedText, 0, cachedOffset)) {
            return false;
        }
        for (int index = cachedOffset; index < offset; index++) {
            if (!Character.isJavaIdentifierPart(text.charAt(index))) {
                return false;
            }
        }
        return text.regionMatches(offset, cachedText, cachedOffset, cachedText.length() - cachedOffset);
    }

    void warmCompletion(Path filePath, String text, int line, int col) {
        if (!host.isInteractive() || host.isReady() || filePath == null) return;
        executor.submit(() -> complete(filePath, text, line, col));
    }

    void forget(String uri) {
        completionCache.remove(uri);
    }

    void clearCache() {
        completionCache.clear();
    }
}
