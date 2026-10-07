package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.api.StatusListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LspSession {
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
