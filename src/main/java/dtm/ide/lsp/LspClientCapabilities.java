package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LspClientCapabilities {

    private LspClientCapabilities() {
    }

    static Map<String, Object> build(List<String> tokenTypes, List<String> tokenModifiers) {
        Map<String, Object> textDocument = new LinkedHashMap<>();

        textDocument.put("synchronization", Map.of(
                "dynamicRegistration", false,
                "willSave", false,
                "didSave", true));

        textDocument.put("completion", Map.of(
                "dynamicRegistration", false,
                "completionItem", Map.of(
                        "snippetSupport", true,
                        "labelDetailsSupport", true,
                        "documentationFormat", List.of("markdown", "plaintext")),
                "contextSupport", true));

        textDocument.put("hover", Map.of(
                "dynamicRegistration", false,
                "contentFormat", List.of("markdown", "plaintext")));

        textDocument.put("signatureHelp", Map.of(
                "dynamicRegistration", false,
                "signatureInformation", Map.of(
                        "documentationFormat", List.of("markdown", "plaintext"),
                        "parameterInformation", Map.of("labelOffsetSupport", true))));

        textDocument.put("definition", dynamic(false, "linkSupport", true));
        textDocument.put("typeDefinition", dynamic(false, "linkSupport", true));
        textDocument.put("implementation", dynamic(false, "linkSupport", true));
        textDocument.put("references", Map.of("dynamicRegistration", false));
        textDocument.put("documentHighlight", Map.of("dynamicRegistration", false));
        textDocument.put("codeLens", Map.of("dynamicRegistration", false));

        textDocument.put("documentSymbol", Map.of(
                "dynamicRegistration", false,
                "hierarchicalDocumentSymbolSupport", true));

        textDocument.put("formatting", Map.of("dynamicRegistration", false));
        textDocument.put("rangeFormatting", Map.of("dynamicRegistration", false));
        textDocument.put("rename", Map.of("dynamicRegistration", false, "prepareSupport", true));
        textDocument.put("publishDiagnostics", Map.of("relatedInformation", true));
        textDocument.put("inlayHint", Map.of("dynamicRegistration", false));
        textDocument.put("callHierarchy", Map.of("dynamicRegistration", false));
        textDocument.put("selectionRange", Map.of("dynamicRegistration", false));
        textDocument.put("foldingRange", Map.of("dynamicRegistration", false, "lineFoldingOnly", true));

        textDocument.put("codeAction", Map.of(
                "dynamicRegistration", false,
                "codeActionLiteralSupport", Map.of("codeActionKind", Map.of("valueSet", List.of(
                        "quickfix", "refactor", "refactor.extract", "refactor.inline",
                        "refactor.rewrite", "source", "source.organizeImports"))),
                "resolveSupport", Map.of("properties", List.of("edit")),
                "isPreferredSupport", true));

        textDocument.put("semanticTokens", Map.of(
                "dynamicRegistration", false,
                "requests", Map.of("range", false, "full", Map.of("delta", false)),
                "tokenTypes", tokenTypes,
                "tokenModifiers", tokenModifiers,
                "formats", List.of("relative")));

        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("applyEdit", true);
        workspace.put("configuration", true);
        workspace.put("workspaceFolders", true);
        workspace.put("didChangeWatchedFiles", Map.of(
                "dynamicRegistration", false,
                "relativePatternSupport", false));
        workspace.put("codeLens", Map.of("refreshSupport", true));
        workspace.put("executeCommand", Map.of("dynamicRegistration", false));
        workspace.put("symbol", Map.of("dynamicRegistration", false));
        workspace.put("workspaceEdit", Map.of(
                "documentChanges", true,
                "resourceOperations", List.of("create", "rename", "delete")));

        Map<String, Object> window = Map.of(
                "workDoneProgress", true,
                "showMessage", Map.of("messageActionItem", Map.of("additionalPropertiesSupport", false)));

        return Map.of(
                "textDocument", textDocument,
                "workspace", workspace,
                "window", window);
    }

    private static Map<String, Object> dynamic(boolean dynamicRegistration, String key, Object value) {
        return Map.of("dynamicRegistration", dynamicRegistration, key, value);
    }

    static JdtLsService.ServerCapabilities readServerCapabilities(JsonNode initializeResult) {
        JsonNode caps = initializeResult == null ? null : initializeResult.get("capabilities");
        if (caps == null || caps.isNull()) {
            return JdtLsService.ServerCapabilities.none();
        }
        return new JdtLsService.ServerCapabilities(
                provided(caps, "definitionProvider"),
                provided(caps, "typeDefinitionProvider"),
                provided(caps, "implementationProvider"),
                provided(caps, "referencesProvider"),
                provided(caps, "documentSymbolProvider"),
                provided(caps, "documentHighlightProvider"),
                provided(caps, "codeLensProvider"),
                provided(caps, "renameProvider"),
                provided(caps, "documentFormattingProvider"),
                provided(caps, "documentRangeFormattingProvider"),
                provided(caps, "codeActionProvider"),
                provided(caps, "signatureHelpProvider"),
                provided(caps, "inlayHintProvider"),
                provided(caps, "semanticTokensProvider"),
                provided(caps, "callHierarchyProvider"),
                provided(caps, "executeCommandProvider"),
                caps.path("completionProvider").path("resolveProvider").asBoolean(false),
                syncKind(caps) == 2,
                triggerCharacters(caps.get("completionProvider")),
                triggerCharacters(caps.get("signatureHelpProvider")));
    }

    private static boolean provided(JsonNode capabilities, String name) {
        JsonNode node = capabilities.get(name);
        if (node == null || node.isNull()) {
            return false;
        }
        return node.isObject() || node.asBoolean(false);
    }

    private static int syncKind(JsonNode capabilities) {
        JsonNode sync = capabilities.get("textDocumentSync");
        if (sync == null || sync.isNull()) {
            return 0;
        }
        return sync.isObject() ? sync.path("change").asInt(0) : sync.asInt(0);
    }

    private static Set<Character> triggerCharacters(JsonNode provider) {
        if (provider == null || !provider.isObject()) {
            return Set.of();
        }
        JsonNode triggers = provider.get("triggerCharacters");
        if (triggers == null || !triggers.isArray()) {
            return Set.of();
        }
        Set<Character> characters = new LinkedHashSet<>();
        for (JsonNode trigger : triggers) {
            String value = trigger.asText("");
            if (!value.isEmpty()) {
                characters.add(value.charAt(0));
            }
        }
        return Set.copyOf(characters);
    }
}
