package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.lsp.api.TypeMoveSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class JdtMove implements TypeMoveSupport {
    private static final long RENAME_TIMEOUT_MS = 60_000;

    interface Host {
        LspJsonRpcClient client();
        boolean isReady();
        void drainPendingWatchedFiles();
    }

    private final LspRequests requests;
    private final Host host;
    private volatile String lastMoveProblem;

    JdtMove(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
    }

    public IdeWorkspaceEdit moveTypesWorkspace(List<Path> sources, Path targetDirectory) {
        lastMoveProblem = null;
        if (sources == null || sources.isEmpty() || targetDirectory == null) {
            return IdeWorkspaceEdit.empty();
        }
        LspJsonRpcClient rpc = readyClientForMove();
        if (rpc == null) {
            return IdeWorkspaceEdit.empty();
        }
        List<String> sourceUris = sources.stream().map(LspConversions::toUri).toList();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("moveKind", "moveResource");
        query.put("sourceUris", sourceUris);
        query.put("params", null);
        JsonNode destinations = moveRequest(rpc, "java/getMoveDestinations", query);
        if (destinations == null) {
            return IdeWorkspaceEdit.empty();
        }
        JsonNode destination = moveDestinationFor(destinations.path("destinations"), targetDirectory);
        if (destination == null) {
            String error = destinations.path("errorMessage").asText(null);
            lastMoveProblem = error != null && !error.isBlank()
                    ? error : "o destino nao e um pacote Java conhecido pelo servidor";
            return IdeWorkspaceEdit.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>(query);
        params.put("destination", destination);
        params.put("updateReferences", true);
        JsonNode result = moveRequest(rpc, "java/move", params);
        if (result == null) {
            return IdeWorkspaceEdit.empty();
        }
        String error = result.path("errorMessage").asText(null);
        if (error != null && !error.isBlank()) {
            lastMoveProblem = error;
            return IdeWorkspaceEdit.empty();
        }
        return LspConversions.workspaceEdit(result.path("edit"));
    }

    public IdeWorkspaceEdit willRenameFilesWorkspace(Map<Path, Path> renames) {
        lastMoveProblem = null;
        if (renames == null || renames.isEmpty()) {
            return IdeWorkspaceEdit.empty();
        }
        LspJsonRpcClient rpc = readyClientForMove();
        if (rpc == null) {
            return IdeWorkspaceEdit.empty();
        }
        List<Map<String, Object>> files = new ArrayList<>();
        renames.forEach((oldPath, newPath) -> files.add(Map.of(
                "oldUri", LspConversions.toUri(oldPath),
                "newUri", LspConversions.toUri(newPath))));
        JsonNode result = moveRequest(rpc, "workspace/willRenameFiles", Map.of("files", files));
        return result == null ? IdeWorkspaceEdit.empty() : LspConversions.workspaceEdit(result);
    }

    public String lastMoveProblem() {
        return lastMoveProblem;
    }

    private LspJsonRpcClient readyClientForMove() {
        host.drainPendingWatchedFiles();
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !host.isReady()) {
            lastMoveProblem = "o servidor Java nao esta pronto";
            return null;
        }
        return rpc;
    }

    private JsonNode moveRequest(LspJsonRpcClient rpc, String method, Object params) {
        CompletableFuture<JsonNode> future = rpc.request(method, params);
        try {
            JsonNode result = future.get(RENAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null || result.isNull() || result.isMissingNode()) {
                lastMoveProblem = "o servidor Java nao devolveu nada para " + method;
                return null;
            }
            return result;
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            future.cancel(false);
            requests.logRequestFailure(method, e);
            String message = LspConversions.errorMessage(e);
            lastMoveProblem = message == null ? "o servidor Java recusou " + method : message;
            return null;
        }
    }

    static JsonNode moveDestinationFor(JsonNode destinations, Path targetDirectory) {
        if (destinations == null || !destinations.isArray() || targetDirectory == null) {
            return null;
        }
        Path target = normalizePath(targetDirectory);
        for (JsonNode destination : destinations) {
            String uri = destination.path("uri").asText(null);
            Path path = uri == null ? null : LspConversions.toPath(uri);
            if (path != null && normalizePath(path).equals(target)) {
                return destination;
            }
        }
        return null;
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            return Path.of(normalized.toString().toLowerCase(java.util.Locale.ROOT));
        }
        return normalized;
    }
}
