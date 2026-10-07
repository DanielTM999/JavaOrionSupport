package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.stools.component.panels.editor.code.api.Range;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Slf4j
final class LspRequests {

    static final long REQUEST_TIMEOUT_MS = 4_000;
    static final long INTERACTIVE_TIMEOUT_MS = 800;
    static final long INDEXING_INTERACTIVE_TIMEOUT_MS = 2_000;

    private static final Set<String> CANCELLED_ON_EDIT = Set.of(
            "textDocument/hover", "textDocument/signatureHelp", "textDocument/semanticTokens/full",
            "textDocument/inlayHint", "textDocument/codeLens", "textDocument/documentHighlight",
            "textDocument/foldingRange", "textDocument/selectionRange");

    interface Host {
        LspJsonRpcClient client();

        boolean isInteractive();

        boolean isReady();

        boolean isWarmingUp();

        boolean syncBeforeRequest(Path filePath, String text);
    }

    private final Host host;
    private final Map<String, CompletableFuture<JsonNode>> inFlightRequests = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastFailureLog = new ConcurrentHashMap<>();
    private final AtomicLong editScopedSequence = new AtomicLong();

    LspRequests(Host host) {
        this.host = host;
    }

    JsonNode requestAt(String method, Path filePath, String text, int line, int col) {
        return requestAt(method, filePath, text, line, col, REQUEST_TIMEOUT_MS);
    }

    JsonNode requestAt(String method, Path filePath, String text, int line, int col,
                       long timeoutMs) {
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        return request(method, positionParams(filePath, line, col), timeoutMs);
    }

    JsonNode requestAtInteractive(String method, Path filePath, String text,
                                  int line, int col) {
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        return requestInteractive(method, positionParams(filePath, line, col),
                interactiveTimeoutMs());
    }

    CompletableFuture<JsonNode> requestAtInteractiveAsync(String method, Path filePath, String text,
                                                          int line, int col) {
        if (!host.syncBeforeRequest(filePath, text)) {
            return CompletableFuture.completedFuture(null);
        }
        return requestAsync(method, positionParams(filePath, line, col), interactiveTimeoutMs(), true);
    }

    CompletableFuture<JsonNode> requestAsync(String method, Object params, long timeoutMs,
                                             boolean interactive) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || (interactive ? !host.isInteractive() : !host.isReady())) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<JsonNode> future = rpc.request(method, params);
        String key = CANCELLED_ON_EDIT.contains(method) ? editScopedKey(method, params) : null;
        if (key != null) {
            inFlightRequests.put(key, future);
            future.whenComplete((result, error) -> inFlightRequests.remove(key, future));
        }
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
                .execute(() -> future.cancel(false));
        return future.handle((result, error) -> {
            if (error != null && !future.isCancelled()) {
                logRequestFailure(method, error instanceof Exception exception
                        ? exception : new Exception(error));
            }
            return error == null ? result : null;
        });
    }

    long interactiveTimeoutMs() {
        return host.isReady() && !host.isWarmingUp() ? INTERACTIVE_TIMEOUT_MS : INDEXING_INTERACTIVE_TIMEOUT_MS;
    }

    JsonNode request(String method, Object params, long timeoutMs) {
        return request(method, params, timeoutMs, false);
    }

    JsonNode requestInteractive(String method, Object params, long timeoutMs) {
        return request(method, params, timeoutMs, true);
    }

    JsonNode requestBackground(String method, Object params, long timeoutMs) {
        return host.isWarmingUp() ? null : request(method, params, timeoutMs, false);
    }

    private JsonNode request(String method, Object params, long timeoutMs, boolean interactive) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || (interactive ? !host.isInteractive() : !host.isReady())) {
            return null;
        }
        CompletableFuture<JsonNode> future = rpc.request(method, params);
        String key = CANCELLED_ON_EDIT.contains(method) ? editScopedKey(method, params) : null;
        if (key != null) {
            inFlightRequests.put(key, future);
            future.whenComplete((result, error) -> inFlightRequests.remove(key, future));
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            future.cancel(false);
            logRequestFailure(method, e);
            return null;
        } finally {
            if (key != null) {
                inFlightRequests.remove(key, future);
            }
        }
    }

    private String editScopedKey(String method, Object params) {
        if (!(params instanceof Map<?, ?> map) || !(map.get("textDocument") instanceof Map<?, ?> document)
                || !(document.get("uri") instanceof String uri)) {
            return null;
        }
        return method + "|" + uri + "|#" + editScopedSequence.incrementAndGet();
    }

    JsonNode requestCoalesced(String method, Object params, long timeoutMs, String key) {
        return requestCoalesced(method, params, timeoutMs, key, false);
    }

    JsonNode requestCoalescedInteractive(String method, Object params, long timeoutMs,
                                         String key) {
        return requestCoalesced(method, params, timeoutMs, key, true);
    }

    JsonNode requestCoalescedBackground(String method, Object params, long timeoutMs,
                                        String key) {
        return host.isWarmingUp() ? null : requestCoalesced(method, params, timeoutMs, key, false);
    }

    JsonNode requestCoalesced(String method, Object params, long timeoutMs, String key,
                              boolean interactive) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || (interactive ? !host.isInteractive() : !host.isReady())) {
            return null;
        }
        CompletableFuture<JsonNode> future = inFlightRequests.computeIfAbsent(key, ignored ->
                rpc.request(method, params));
        future.whenComplete((result, error) -> inFlightRequests.remove(key, future));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            inFlightRequests.remove(key, future);
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            inFlightRequests.remove(key, future);
            future.cancel(false);
            logRequestFailure(method, e);
            return null;
        }
    }

    CompletableFuture<JsonNode> shareInFlight(String key,
                                              Function<String, CompletableFuture<JsonNode>> start) {
        CompletableFuture<JsonNode> request = inFlightRequests.computeIfAbsent(key, start);
        request.whenComplete((result, error) -> inFlightRequests.remove(key, request));
        return request;
    }

    void logRequestFailure(String method, Exception error) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastFailureLog.computeIfAbsent(method, ignored -> new AtomicLong());
        long previous = last.get();
        if (now - previous >= 10_000 && last.compareAndSet(previous, now)) {
            String reason = error instanceof TimeoutException || error.getCause() instanceof TimeoutException
                    ? "tempo limite excedido" : rootMessage(error);
            log.debug("Requisicao {} falhou: {}", method, reason);
        }
    }

    void cancelInFlightForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        inFlightRequests.forEach((key, future) -> {
            if (key.contains("|" + uri + "|") && inFlightRequests.remove(key, future)) {
                future.cancel(false);
            }
        });
    }

    void clearInFlight() {
        inFlightRequests.clear();
    }

    static Map<String, Object> positionParams(Path filePath, int line, int col) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("position", Map.of("line", Math.max(0, line), "character", Math.max(0, col)));
        return params;
    }

    static Map<String, Object> documentId(Path filePath) {
        return Map.of("uri", LspConversions.toUri(filePath));
    }

    static Map<String, Object> rangeParam(Range range) {
        Range safe = range == null ? Range.point(0, 0) : range;
        return Map.of(
                "start", Map.of("line", safe.start().line(), "character", safe.start().col()),
                "end", Map.of("line", safe.end().line(), "character", safe.end().col()));
    }

    static String rootMessage(Throwable error) {
        Throwable cause = rootCause(error);
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
