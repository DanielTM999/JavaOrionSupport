package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.api.StatusListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

final class LspSession {
    private static final long INITIALIZE_CEILING_MS = 900_000;
    private static final long INITIALIZE_WAIT_SLICE_MS = 5_000;
    interface Host {
        void onPublishDiagnostics(JsonNode params);
        void onLanguageStatus(JsonNode params);
        void onServerLogMessage(JsonNode params);
        void onProgress(JsonNode params);
        void onProgressReport(JsonNode params);
        void invalidateWorkspaceNavigation();
        StatusListener statusListener();
        Map<String, Object> effectiveSettings();
    }

    private final Host host;

    LspSession(Host host) {
        this.host = host;
    }

    ServerCapabilities initialize(LspJsonRpcClient rpc, Process server, Path root,
                                  Map<String, Object> settings, List<String> bundlePaths,
                                  ServerDialect dialect, BooleanSupplier current) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", LspConversions.toUri(root));
        params.put("workspaceFolders", List.of(Map.of(
                "uri", LspConversions.toUri(root),
                "name", root.getFileName() == null ? "workspace" : root.getFileName().toString())));
        params.put("capabilities", LspClientCapabilities.build(LspDecorations.TOKEN_TYPES, LspDecorations.TOKEN_MODIFIERS));
        params.put("initializationOptions", dialect.initializationOptions(settings, bundlePaths));

        JsonNode result = awaitWhileAlive(rpc.request("initialize", params),
                () -> server.isAlive() && current.getAsBoolean(),
                INITIALIZE_WAIT_SLICE_MS, INITIALIZE_CEILING_MS,
                elapsed -> host.statusListener().onStatus("Java: iniciando o JDT LS... "
                        + TimeUnit.MILLISECONDS.toSeconds(elapsed) + " s", -1));
        ServerCapabilities capabilities = LspClientCapabilities.readServerCapabilities(result);
        rpc.notify("initialized", Map.of());
        rpc.notify("workspace/didChangeConfiguration", Map.of("settings", settings));
        return capabilities;
    }

    static JsonNode awaitWhileAlive(CompletableFuture<JsonNode> response, BooleanSupplier keepWaiting,
                                    long sliceMs, long ceilingMs, LongConsumer onWaiting) throws Exception {
        long started = System.nanoTime();
        while (true) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long remaining = ceilingMs - elapsed;
            if (remaining <= 0) {
                response.cancel(false);
                throw new TimeoutException("o JDT LS nao respondeu em " + ceilingMs + " ms");
            }
            try {
                return response.get(Math.min(sliceMs, remaining), TimeUnit.MILLISECONDS);
            } catch (TimeoutException slice) {
                if (!keepWaiting.getAsBoolean()) {
                    response.cancel(false);
                    throw new IllegalStateException("o processo do JDT LS encerrou durante a inicializacao");
                }
                onWaiting.accept(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    void registerHandlers(LspJsonRpcClient rpc) {
        if (rpc == null) {
            return;
        }
        rpc.onNotification("textDocument/publishDiagnostics", host::onPublishDiagnostics);
        rpc.onNotification("language/status", host::onLanguageStatus);
        rpc.onNotification("window/logMessage", host::onServerLogMessage);
        rpc.onNotification("window/showMessage", params -> {
            if (params != null) {
                host.statusListener().onStatus("Java: " + params.path("message").asText(""), -1);
            }
        });
        rpc.onNotification("$/progress", host::onProgress);
        rpc.onNotification("language/progressReport", host::onProgressReport);
        rpc.onRequest("window/workDoneProgress/create", params -> null);

        rpc.onRequest("workspace/configuration", params -> {
            int items = params != null && params.has("items") ? params.get("items").size() : 1;
            List<Object> answer = new ArrayList<>(items);
            for (int i = 0; i < items; i++) {
                String section = params != null && params.has("items")
                        ? params.get("items").get(i).path("section").asText("")
                        : "";
                answer.add(configurationValue(host.effectiveSettings(), section));
            }
            return answer;
        });
        rpc.onRequest("client/registerCapability", params -> Map.of());
        rpc.onRequest("client/unregisterCapability", params -> Map.of());
        rpc.onRequest("workspace/applyEdit", params -> Map.of("applied", false));
        rpc.onRequest("workspace/codeLens/refresh", params -> {
            host.invalidateWorkspaceNavigation();
            return null;
        });
    }

    static Object configurationValue(Map<String, Object> settings, String section) {
        if (section == null || section.isBlank()) {
            return settings;
        }
        Object current = settings;
        for (String part : section.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
