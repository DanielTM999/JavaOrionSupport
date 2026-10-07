package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.api.SourceGenerationSupport;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.documentId;

final class JdtSourceGeneration implements SourceGenerationSupport {
    interface Host {
        boolean syncBeforeRequest(Path path, String text);
    }

    private final LspRequests requests;
    private final Host host;

    JdtSourceGeneration(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
    }

    public OverrideStatus overridableMethods(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/listOverridableMethods", filePath, text, line, col, null);
        return parseOverrideStatus(result);
    }

    static OverrideStatus parseOverrideStatus(JsonNode result) {
        if (result == null || result.isNull()) return new OverrideStatus("", List.of());
        List<SourceItem> methods = new ArrayList<>();
        for (JsonNode method : result.path("methods")) {
            String signature = method.path("name").asText("") + "("
                    + joinText(method.path("parameters")) + ")";
            String detail = method.path("declaringClassType").asText("");
            String declaring = method.path("declaringClass").asText("");
            if (!declaring.isBlank()) {
                detail = detail.isBlank() ? declaring : detail + ": " + declaring;
            }
            methods.add(new SourceItem(method, signature, detail,
                    method.path("unimplemented").asBoolean(false)));
        }
        return new OverrideStatus(result.path("type").asText(""), List.copyOf(methods));
    }

    public List<TextEdit> generateOverridableMethods(Path filePath, String text, int line, int col,
                                                       List<SourceItem> methods) {
        return generatedEdits("java/addOverridableMethods", filePath, text, line, col,
                Map.of("overridableMethods", rawValues(methods)));
    }

    public ConstructorsStatus constructorsStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkConstructorsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new ConstructorsStatus(List.of(), List.of());
        }
        return new ConstructorsStatus(methodItems(result.path("constructors"), false),
                variableItems(result.path("fields")));
    }

    public List<TextEdit> generateConstructors(Path filePath, String text, int line, int col,
                                                List<SourceItem> constructors,
                                                List<SourceItem> fields) {
        var insertion = dtm.ide.editor.ConstructorPlacement.afterFields(text, line, col);
        return generatedEdits("java/generateConstructors", filePath, text, insertion.line(), insertion.col(),
                Map.of("constructors", rawValues(constructors), "fields", rawValues(fields)));
    }

    public List<SourceItem> accessorsStatus(Path filePath, String text, int line, int col) {
        Map<String, Object> extraContext = Map.of("kind", 2);
        JsonNode result = sourceRequest("java/resolveUnimplementedAccessors",
                filePath, text, line, col, extraContext);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode accessor : result) {
            List<String> kinds = new ArrayList<>(2);
            if (accessor.path("generateGetter").asBoolean()) kinds.add("getter");
            if (accessor.path("generateSetter").asBoolean()) kinds.add("setter");
            String detail = (accessor.path("isStatic").asBoolean() ? "static " : "")
                    + String.join(", ", kinds);
            items.add(new SourceItem(accessor,
                    accessor.path("fieldName").asText("") + ": "
                            + accessor.path("typeName").asText(""), detail, true));
        }
        return List.copyOf(items);
    }

    public List<TextEdit> generateAccessors(Path filePath, String text, int line, int col,
                                             List<SourceItem> accessors) {
        return generatedEdits("java/generateAccessors", filePath, text, line, col,
                Map.of("accessors", rawValues(accessors), "contextExtra", Map.of("kind", 2)));
    }

    public FieldsStatus hashCodeEqualsStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkHashCodeEqualsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new FieldsStatus("", List.of(), List.of(), false);
        }
        List<String> existing = new ArrayList<>();
        result.path("existingMethods").forEach(node -> existing.add(node.asText()));
        return new FieldsStatus(result.path("type").asText(""), variableItems(result.path("fields")),
                List.copyOf(existing), !existing.isEmpty());
    }

    public List<TextEdit> generateHashCodeEquals(Path filePath, String text, int line, int col,
                                                  List<SourceItem> fields, boolean regenerate) {
        return generatedEdits("java/generateHashCodeEquals", filePath, text, line, col,
                Map.of("fields", rawValues(fields), "regenerate", regenerate));
    }

    public FieldsStatus toStringStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkToStringStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new FieldsStatus("", List.of(), List.of(), false);
        }
        return new FieldsStatus(result.path("type").asText(""), variableItems(result.path("fields")),
                List.of(), result.path("exists").asBoolean(false));
    }

    public List<TextEdit> generateToString(Path filePath, String text, int line, int col,
                                            List<SourceItem> fields) {
        return generatedEdits("java/generateToString", filePath, text, line, col,
                Map.of("fields", rawValues(fields)));
    }

    public List<DelegateTarget> delegateTargets(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkDelegateMethodsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return List.of();
        }
        List<DelegateTarget> targets = new ArrayList<>();
        for (JsonNode delegate : result.path("delegateFields")) {
            JsonNode field = delegate.path("field");
            String label = field.path("name").asText("") + ": " + field.path("type").asText("");
            targets.add(new DelegateTarget(field, label,
                    methodItems(delegate.path("delegateMethods"), true)));
        }
        return List.copyOf(targets);
    }

    public List<TextEdit> generateDelegateMethods(Path filePath, String text, int line, int col,
                                                   DelegateTarget target, List<SourceItem> methods) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SourceItem method : methods) {
            entries.add(Map.of("field", target.field(), "delegateMethod", method.value()));
        }
        return generatedEdits("java/generateDelegateMethods", filePath, text, line, col,
                Map.of("delegateEntries", entries));
    }

    private List<TextEdit> generatedEdits(String method, Path filePath, String text, int line, int col,
                                           Map<String, Object> values) {
        Map<String, Object> extra = new LinkedHashMap<>(values);
        @SuppressWarnings("unchecked")
        Map<String, Object> contextExtra = (Map<String, Object>) extra.remove("contextExtra");
        Map<String, Object> params = new LinkedHashMap<>(extra);
        Map<String, Object> context = sourceActionParams(filePath, text, line, col);
        if (context == null) {
            return List.of();
        }
        if (contextExtra != null) {
            context.putAll(contextExtra);
        }
        params.put("context", context);
        JsonNode result = requests.request(method, params, REQUEST_TIMEOUT_MS * 2);
        return LspConversions.singleDocumentEdits(result);
    }

    private JsonNode sourceRequest(String method, Path filePath, String text, int line, int col,
                                   Map<String, Object> paramsExtra) {
        Map<String, Object> params = sourceActionParams(filePath, text, line, col);
        if (params == null) {
            return null;
        }
        if (paramsExtra != null) {
            params.putAll(paramsExtra);
        }
        return requests.request(method, params, REQUEST_TIMEOUT_MS * 2);
    }

    private Map<String, Object> sourceActionParams(Path filePath, String text, int line, int col) {
        if (!host.syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        Map<String, Object> position = Map.of("line", Math.max(0, line),
                "character", Math.max(0, col));
        params.put("range", Map.of("start", position, "end", position));
        params.put("context", Map.of("diagnostics", List.of()));
        return params;
    }

    static List<SourceItem> variableItems(JsonNode values) {
        if (values == null || !values.isArray()) return List.of();
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode field : values) {
            items.add(new SourceItem(field, field.path("name").asText("") + ": "
                    + field.path("type").asText(""), "",
                    field.path("isSelected").asBoolean(true)));
        }
        return List.copyOf(items);
    }

    static List<SourceItem> methodItems(JsonNode values, boolean selected) {
        if (values == null || !values.isArray()) return List.of();
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode method : values) {
            items.add(new SourceItem(method, method.path("name").asText("") + "("
                    + joinText(method.path("parameters")) + ")", "", selected));
        }
        return List.copyOf(items);
    }

    private static String joinText(JsonNode values) {
        if (values == null || !values.isArray()) return "";
        List<String> parts = new ArrayList<>();
        values.forEach(node -> parts.add(node.asText("")));
        return String.join(", ", parts);
    }

    static List<JsonNode> rawValues(List<SourceItem> items) {
        if (items == null) return List.of();
        return items.stream().map(item -> (JsonNode) item.value()).toList();
    }

    static boolean isSourcePrompt(String id) {
        return OVERRIDE_METHODS_PROMPT.equals(id)
                || HASHCODE_EQUALS_PROMPT.equals(id)
                || GENERATE_TOSTRING_PROMPT.equals(id)
                || GENERATE_ACCESSORS_PROMPT.equals(id)
                || GENERATE_CONSTRUCTORS_PROMPT.equals(id)
                || GENERATE_DELEGATE_METHODS_PROMPT.equals(id);
    }

}
