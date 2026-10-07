package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.lsp.api.PrepareRenameResult;
import dtm.ide.lsp.api.ResolvedCodeAction;
import dtm.ide.lsp.api.TypeSymbol;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.documentId;
import static dtm.ide.lsp.LspRequests.positionParams;
import static dtm.ide.lsp.LspRequests.rangeParam;
import static dtm.ide.lsp.LspRequests.rootMessage;
import static dtm.ide.lsp.LspText.offsetIn;

@Slf4j
final class LspRefactoring {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long RENAME_TIMEOUT_MS = 60_000;

    interface Host {
        LspJsonRpcClient client();
        ServerCapabilities capabilities();
        boolean isReady();
        boolean isInteractive();
        boolean syncBeforeRequest(Path path, String text);
        boolean isCurrentText(Path path, String text);
        void drainPendingWatchedFiles();
        JdtDocumentStore documents();
        List<JsonNode> rawDiagnostics(Path path);
        String applyCodeActionCommand();
    }

    private final LspRequests requests;
    private final Host host;
    private final String applyCodeActionCommand;
    private volatile String lastRenameProblem;

    LspRefactoring(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
        this.applyCodeActionCommand = host.applyCodeActionCommand();
    }

    public List<TextEdit> rename(Path filePath, String text, int line, int col, String newName) {
        IdeWorkspaceEdit workspaceEdit = renameWorkspace(filePath, text, line, col, newName);
        return workspaceEdit == null ? List.of() : workspaceEdit.editsFor(filePath);
    }

    public IdeWorkspaceEdit renameWorkspace(Path filePath, String text, int line, int col, String newName) {
        lastRenameProblem = null;
        if (!host.capabilities().rename()) {
            return IdeWorkspaceEdit.empty();
        }
        Map<String, Object> params = positionParams(filePath, line, col);
        params.put("newName", newName);
        if (!host.syncBeforeRequest(filePath, text)) {
            return IdeWorkspaceEdit.empty();
        }
        host.drainPendingWatchedFiles();
        String oldName = renamedName(filePath, text, line, col);
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !host.isReady()) {
            lastRenameProblem = "o servidor Java nao esta pronto";
            return IdeWorkspaceEdit.empty();
        }
        CompletableFuture<JsonNode> future = rpc.request("textDocument/rename", params);
        JsonNode result;
        try {
            result = future.get(RENAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return IdeWorkspaceEdit.empty();
        } catch (Exception e) {
            future.cancel(false);
            requests.logRequestFailure("textDocument/rename", e);
            String message = LspConversions.errorMessage(e);
            lastRenameProblem = message == null ? "o servidor Java nao conseguiu renomear" : message;
            return IdeWorkspaceEdit.empty();
        }
        IdeWorkspaceEdit edit = LspConversions.workspaceEdit(result);
        if (oldName == null) {
            log.debug("Rename sem nome original conhecido em {}:{}:{}; edicoes nao verificadas", filePath, line, col);
            return edit;
        }
        Path current = normalizePath(filePath);
        RenameEditVerifier.Result verified = RenameEditVerifier.verify(edit, oldName, newName,
                file -> renameSourceOf(file, current, text));
        if (verified.rejected()) {
            lastRenameProblem = verified.problem() + (verified.rejectedFile() == null ? ""
                    : " (" + verified.rejectedFile().getFileName() + ")");
            log.warn("Rename '{}' -> '{}' cancelado: {} file={}", oldName, newName, verified.problem(),
                    verified.rejectedFile());
            return IdeWorkspaceEdit.empty();
        }
        return verified.edit();
    }

    public String lastRenameProblem() {
        return lastRenameProblem;
    }

    private String renamedName(Path filePath, String text, int line, int col) {
        PrepareRenameResult prepared = prepareRename(filePath, text, line, col);
        if (prepared != null && prepared.renameable() && prepared.range() != null) {
            String name = textIn(text, prepared.range());
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (prepared != null && prepared.placeholder() != null && !prepared.placeholder().isBlank()) {
            return prepared.placeholder();
        }
        return identifierAt(text, line, col);
    }

    private String renameSourceOf(Path file, Path current, String currentText) {
        if (file == null) {
            return null;
        }
        Path target = normalizePath(file);
        if (target.equals(current)) {
            return currentText;
        }
        String open = host.documents().content(LspConversions.toUri(target));
        if (open != null) {
            return open;
        }
        try {
            String disk = Files.readString(target, StandardCharsets.UTF_8);
            return disk.startsWith("﻿") ? disk.substring(1) : disk;
        } catch (IOException e) {
            return null;
        }
    }

    static String textIn(String text, Range range) {
        if (text == null || range == null || range.start() == null || range.end() == null) {
            return null;
        }
        String normalized = LspConversions.normalizeLineBreaks(text);
        int start = offsetIn(normalized, range.start());
        int end = offsetIn(normalized, range.end());
        return start < 0 || end < start ? null : normalized.substring(start, end);
    }

    static String identifierAt(String text, int line, int col) {
        if (text == null) {
            return null;
        }
        String normalized = LspConversions.normalizeLineBreaks(text);
        int offset = offsetIn(normalized, new Position(line, col));
        if (offset < 0) {
            return null;
        }
        int start = offset;
        int end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(normalized.charAt(start - 1))) {
            start--;
        }
        while (end < normalized.length() && Character.isJavaIdentifierPart(normalized.charAt(end))) {
            end++;
        }
        return end > start ? normalized.substring(start, end) : null;
    }

    public PrepareRenameResult prepareRename(Path filePath, String text, int line, int col) {
        if (!host.capabilities().rename() || !host.capabilities().prepareRename()) {
            return null;
        }
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !host.isInteractive()) {
            return null;
        }
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        CompletableFuture<JsonNode> future = rpc.request("textDocument/prepareRename",
                positionParams(filePath, line, col));
        JsonNode result;
        try {
            result = future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            future.cancel(false);
            return null;
        } catch (Exception e) {
            return PrepareRenameResult.rejected(LspConversions.errorMessage(e));
        }
        if (!host.isCurrentText(filePath, text)) {
            return null;
        }
        return LspConversions.prepareRename(result);
    }

    public List<CodeAction> codeActions(Path filePath, String text, Range range,
                                        List<Diagnostic> diagnostics) {
        if (!host.capabilities().codeAction()) {
            return List.of();
        }
        List<JsonNode> known = host.rawDiagnostics(filePath);
        if (!host.syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("range", rangeParam(range));
        params.put("context", Map.of("diagnostics", diagnosticsIntersecting(known, range)));

        JsonNode result = requests.requestInteractive("textDocument/codeAction", params,
                requests.interactiveTimeoutMs());
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CodeAction> actions = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            CodeAction action = LspConversions.codeAction(node, applyCodeActionCommand);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    static List<JsonNode> diagnosticsIntersecting(List<JsonNode> diagnostics, Range range) {
        if (diagnostics == null || diagnostics.isEmpty() || range == null) {
            return List.of();
        }
        int first = Math.min(range.start().line(), range.end().line());
        int last = Math.max(range.start().line(), range.end().line());
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode diagnostic : diagnostics) {
            JsonNode span = diagnostic.path("range");
            int start = span.path("start").path("line").asInt(-1);
            int end = span.path("end").path("line").asInt(start);
            if (start >= 0 && start <= last && end >= first) {
                matching.add(diagnostic);
            }
        }
        return matching;
    }

    public ResolvedCodeAction resolveCodeAction(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        JsonNode action;
        try {
            action = JSON.readTree(rawJson);
        } catch (Exception e) {
            log.debug("Code action Java invalida: {}", rootMessage(e));
            return null;
        }
        if (!action.hasNonNull("edit") && !action.hasNonNull("command")
                && action.hasNonNull("data")) {
            action = requests.requestInteractive("codeAction/resolve", action, REQUEST_TIMEOUT_MS);
            if (action == null || action.isNull()) {
                return null;
            }
        }
        return new ResolvedCodeAction(LspConversions.workspaceEdit(action.get("edit")),
                action.hasNonNull("command") ? action.toString() : null);
    }

    public List<TypeSymbol> workspaceTypes(String query) {
        if (!host.capabilities().workspaceSymbol() || query == null || query.isBlank()) {
            return List.of();
        }
        return parseWorkspaceTypes(requests.requestInteractive("workspace/symbol",
                Map.of("query", query.trim()), REQUEST_TIMEOUT_MS));
    }

    static List<TypeSymbol> parseWorkspaceTypes(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        Map<String, TypeSymbol> types = new LinkedHashMap<>();
        for (JsonNode symbol : result) {
            int kind = symbol.path("kind").asInt(0);
            if (kind != 5 && kind != 11) {
                continue;
            }
            String name = symbol.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String container = symbol.path("containerName").asText("");
            String qualified = container.isBlank() ? name : container + "." + name;
            types.putIfAbsent(qualified, new TypeSymbol(qualified, kind == 11));
        }
        return List.copyOf(types.values());
    }

    public void executeCodeAction(String rawJson) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !host.isInteractive() || rawJson == null || rawJson.isBlank()) {
            return;
        }
        try {
            JsonNode action = JSON.readTree(rawJson);
            JsonNode command = action.path("command");
            if (command.isTextual()) {
                command = action;
            }
            String id = command.path("command").asText("");
            if (id.isBlank()) {
                return;
            }
            List<Object> arguments = command.hasNonNull("arguments")
                    ? JSON.convertValue(command.get("arguments"), List.class) : List.of();
            requests.requestInteractive("workspace/executeCommand",
                    Map.of("command", id, "arguments", arguments), REQUEST_TIMEOUT_MS * 2);
        } catch (Exception e) {
            log.debug("Falha ao executar code action Java: {}", rootMessage(e));
        }
    }

    public String format(Path filePath, String text, int tabSize, boolean insertSpaces) {
        if (!host.capabilities().formatting()) {
            return null;
        }
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = Map.of(
                "textDocument", documentId(filePath),
                "options", Map.of(
                        "tabSize", Math.max(1, tabSize),
                        "insertSpaces", insertSpaces));

        JsonNode result = requests.request("textDocument/formatting", params, REQUEST_TIMEOUT_MS * 2);
        List<TextEdit> edits = LspConversions.textEdits(result);
        return edits.isEmpty() ? null : TextEditApplier.apply(text, edits);
    }

    public String organizeImports(Path filePath, String text) {
        if (!host.capabilities().codeAction()) {
            return null;
        }
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("range", rangeParam(Range.point(0, 0)));
        params.put("context", Map.of(
                "diagnostics", List.of(),
                "only", List.of("source.organizeImports")));

        JsonNode result = requests.request("textDocument/codeAction", params, REQUEST_TIMEOUT_MS);
        if (result == null || !result.isArray()) {
            return null;
        }
        for (JsonNode action : result) {
            List<TextEdit> edits = LspConversions.singleDocumentEdits(action.get("edit"));
            if (!edits.isEmpty()) {
                return TextEditApplier.apply(text, edits);
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
