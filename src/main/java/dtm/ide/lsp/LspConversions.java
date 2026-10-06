package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.inspection.DiagnosticTags;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.inlay.InlayHintKind;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import dtm.stools.component.panels.editor.code.signature.ParameterInformation;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.signature.SignatureInformation;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LspConversions {

    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
    static final int DIAGNOSTIC_TAG_UNNECESSARY = 1;

    private LspConversions() {
    }

    static Position position(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Position(0, 0);
        }
        return new Position(
                Math.max(0, node.path("line").asInt(0)),
                Math.max(0, node.path("character").asInt(0)));
    }

    static Range range(JsonNode node) {
        if (node == null || node.isNull()) {
            return Range.point(0, 0);
        }
        return new Range(position(node.get("start")), position(node.get("end")));
    }

    /** Flattens an LSP SelectionRange (range + parent chain) into ranges from inner to outer. */
    static List<Range> selectionChain(JsonNode result) {
        JsonNode node = result != null && result.isArray() && !result.isEmpty() ? result.get(0) : result;
        List<Range> chain = new ArrayList<>();
        for (int depth = 0; node != null && node.isObject() && node.hasNonNull("range") && depth < 256; depth++) {
            Range range = range(node.get("range"));
            if (chain.isEmpty() || !chain.getLast().equals(range)) {
                chain.add(range);
            }
            node = node.get("parent");
        }
        return List.copyOf(chain);
    }

    static Location location(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String uri = node.hasNonNull("uri")
                ? node.get("uri").asText()
                : node.path("targetUri").asText("");
        JsonNode rangeNode = node.hasNonNull("range")
                ? node.get("range")
                : node.hasNonNull("targetSelectionRange")
                    ? node.get("targetSelectionRange")
                    : node.get("targetRange");
        if (uri.isBlank()) {
            return null;
        }
        return new Location(uri, range(rangeNode));
    }

    static List<Location> locations(JsonNode node) {
        List<Location> locations = new ArrayList<>();
        if (node == null || node.isNull()) {
            return locations;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                Location location = location(element);
                if (location != null) {
                    locations.add(location);
                }
            }
        } else {
            Location single = location(node);
            if (single != null) {
                locations.add(single);
            }
        }
        return locations;
    }

    static TextEdit textEdit(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new TextEdit(range(node.get("range")), normalizeLineBreaks(node.path("newText").asText("")));
    }

    static String normalizeLineBreaks(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static List<TextEdit> textEdits(JsonNode node) {
        List<TextEdit> edits = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return edits;
        }
        for (JsonNode element : node) {
            TextEdit edit = textEdit(element);
            if (edit != null) {
                edits.add(edit);
            }
        }
        return edits;
    }

    static Diagnostic diagnostic(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Range range = range(node.get("range"));
        String message = node.path("message").asText("");
        if (message.isBlank()) {
            return null;
        }
        return DiagnosticTags.unnecessary(new Diagnostic(
                range.start().line(), range.start().col(),
                range.end().line(), range.end().col(),
                severity(node.path("severity").asInt(1)),
                message,
                node.path("source").asText("java"),
                null), hasTag(node.get("tags"), DIAGNOSTIC_TAG_UNNECESSARY));
    }

    static boolean hasTag(JsonNode tags, int tag) {
        if (tags == null || !tags.isArray()) {
            return false;
        }
        for (JsonNode value : tags) {
            if (value.asInt(-1) == tag) {
                return true;
            }
        }
        return false;
    }

    static DiagnosticSeverity severity(int lspSeverity) {
        return switch (lspSeverity) {
            case 2 -> DiagnosticSeverity.WARNING;
            case 3 -> DiagnosticSeverity.INFO;
            case 4 -> DiagnosticSeverity.HINT;
            default -> DiagnosticSeverity.ERROR;
        };
    }

    static AutoCompleteItem completionItem(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String label = node.path("label").asText("");
        if (label.isBlank()) {
            return null;
        }

        String fallback = simpleLabel(label);
        String insert = fallback;
        JsonNode edit = node.get("textEdit");
        if (edit != null && edit.hasNonNull("newText")) {
            insert = edit.get("newText").asText(fallback);
        } else if (node.hasNonNull("insertText")) {
            insert = node.get("insertText").asText(fallback);
        }

        int lspKind = node.path("kind").asInt(1);
        boolean snippet = node.path("insertTextFormat").asInt(1) == 2;
        AutoCompleteItem.Kind kind = completionKind(lspKind, snippet);
        if (snippet && kind != AutoCompleteItem.Kind.SNIPPET) {
            insert = plainSnippet(insert);
        }

        String detail = node.path("detail").asText(null);
        String docs = documentation(node.get("documentation"));
        boolean resolvable = docs == null && node.has("data");
        return new AutoCompleteItem(
                insert,
                label,
                labelDetails(node.get("labelDetails")),
                completionDescription(detail, docs),
                null,
                kind,
                textEdits(node.get("additionalTextEdits")),
                false,
                resolvable ? node.deepCopy() : null,
                edit == null ? null : range(edit.has("replace") ? edit.get("replace") : edit.get("range")));
    }

    static String labelDetails(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String detail = node.path("detail").asText("").trim();
        String description = node.path("description").asText("").trim();
        String merged = (detail + " " + description).trim();
        return merged.isEmpty() ? null : merged;
    }

    static String simpleLabel(String label) {
        if (label == null) {
            return null;
        }
        int separator = label.indexOf(" - ");
        String head = separator < 0 ? label : label.substring(0, separator);
        int signature = head.indexOf(" : ");
        return (signature < 0 ? head : head.substring(0, signature)).trim();
    }

    static AutoCompleteItem.Kind completionKind(int lspKind, boolean snippet) {
        if (lspKind == 15 || (snippet && lspKind <= 1)) {
            return AutoCompleteItem.Kind.SNIPPET;
        }
        return switch (lspKind) {
            case 2 -> AutoCompleteItem.Kind.METHOD;
            case 3 -> AutoCompleteItem.Kind.FUNCTION;
            case 4 -> AutoCompleteItem.Kind.CONSTRUCTOR;
            case 5 -> AutoCompleteItem.Kind.FIELD;
            case 6 -> AutoCompleteItem.Kind.VARIABLE;
            case 7 -> AutoCompleteItem.Kind.CLASS;
            case 8 -> AutoCompleteItem.Kind.INTERFACE;
            case 9 -> AutoCompleteItem.Kind.MODULE;
            case 10 -> AutoCompleteItem.Kind.PROPERTY;
            case 11 -> AutoCompleteItem.Kind.UNIT;
            case 12 -> AutoCompleteItem.Kind.VALUE;
            case 13 -> AutoCompleteItem.Kind.ENUM;
            case 14 -> AutoCompleteItem.Kind.KEYWORD;
            case 15 -> AutoCompleteItem.Kind.SNIPPET;
            case 16 -> AutoCompleteItem.Kind.COLOR;
            case 17 -> AutoCompleteItem.Kind.FILE;
            case 18 -> AutoCompleteItem.Kind.REFERENCE;
            case 19 -> AutoCompleteItem.Kind.FOLDER;
            case 20 -> AutoCompleteItem.Kind.ENUM_MEMBER;
            case 21 -> AutoCompleteItem.Kind.CONSTANT;
            case 22 -> AutoCompleteItem.Kind.STRUCT;
            case 23 -> AutoCompleteItem.Kind.EVENT;
            case 24 -> AutoCompleteItem.Kind.OPERATOR;
            case 25 -> AutoCompleteItem.Kind.TYPE_PARAMETER;
            default -> AutoCompleteItem.Kind.TEXT;
        };
    }

    static String plainSnippet(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String expanded = value.replaceAll("\\$\\{\\d+:([^}]*)}", "$1");
        return expanded.replaceAll("\\$\\{?\\d+}?", "");
    }

    /** Copies the documentation of a {@code completionItem/resolve} answer into the item. */
    static AutoCompleteItem withResolvedDocumentation(AutoCompleteItem item, JsonNode resolved) {
        if (item == null || resolved == null || !resolved.isObject()) {
            return item;
        }
        String docs = documentation(resolved.get("documentation"));
        if (docs == null || docs.isBlank()) {
            return item;
        }
        String detail = resolved.path("detail").asText(null);
        return new AutoCompleteItem(item.insertText(), item.label(), item.detail(),
                completionDescription(detail, docs), item.icon(), item.kind(), item.additionalTextEdits(),
                item.unused(), null, item.replacementRange());
    }

    static String completionDescription(String detail, String documentation) {
        String signature = detail == null ? "" : detail.trim();
        String docs = documentation == null ? "" : documentation.trim();
        if (docs.isBlank()) return signature.isBlank() ? null : signature;
        if (signature.isBlank() || docs.contains(signature)) return docs;
        return signature + "\n" + docs;
    }

    static HoverInfo hover(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String content = documentation(node.get("contents"));
        if (content == null || content.isBlank()) {
            return null;
        }
        JsonNode rangeNode = node.get("range");
        if (rangeNode == null || rangeNode.isNull()) {
            return HoverInfo.markdown(content);
        }
        Range range = range(rangeNode);
        return new HoverInfo(content, HoverInfo.ContentType.MARKDOWN,
                range.start().line(), range.start().col(),
                range.end().line(), range.end().col());
    }

    static SignatureHelp signatureHelp(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode signaturesNode = node.get("signatures");
        if (signaturesNode == null || !signaturesNode.isArray() || signaturesNode.isEmpty()) {
            return null;
        }
        List<SignatureInformation> signatures = new ArrayList<>();
        for (JsonNode signature : signaturesNode) {
            List<ParameterInformation> parameters = new ArrayList<>();
            JsonNode parametersNode = signature.get("parameters");
            if (parametersNode != null && parametersNode.isArray()) {
                for (JsonNode parameter : parametersNode) {
                    parameters.add(new ParameterInformation(
                            parameterLabel(parameter, signature.path("label").asText("")),
                            documentation(parameter.get("documentation"))));
                }
            }
            signatures.add(new SignatureInformation(
                    signature.path("label").asText(""),
                    documentation(signature.get("documentation")),
                    parameters,
                    signature.path("activeParameter").asInt(-1)));
        }
        return new SignatureHelp(signatures,
                node.path("activeSignature").asInt(0),
                node.path("activeParameter").asInt(-1));
    }

    private static String parameterLabel(JsonNode parameter, String signatureLabel) {
        JsonNode label = parameter.get("label");
        if (label == null || label.isNull()) {
            return "";
        }
        if (label.isTextual()) {
            return label.asText();
        }
        if (label.isArray() && label.size() == 2) {
            int start = label.get(0).asInt(0);
            int end = label.get(1).asInt(0);
            if (start >= 0 && end <= signatureLabel.length() && start < end) {
                return signatureLabel.substring(start, end);
            }
        }
        return "";
    }

    static List<DocumentSymbol> documentSymbols(JsonNode node) {
        List<DocumentSymbol> symbols = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return symbols;
        }
        for (JsonNode element : node) {
            DocumentSymbol symbol = documentSymbol(element);
            if (symbol != null) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    static DocumentSymbol documentSymbol(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String name = node.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        SymbolKind kind = symbolKind(node.path("kind").asInt(0));

        JsonNode rangeNode = node.get("range");
        JsonNode selectionNode = node.get("selectionRange");
        if (rangeNode == null && node.hasNonNull("location")) {
            rangeNode = node.get("location").get("range");
        }
        Range range = range(rangeNode);
        Range selection = selectionNode == null ? range : range(selectionNode);

        List<DocumentSymbol> children = documentSymbols(node.get("children"));
        return new DocumentSymbol(name, node.path("detail").asText(null), kind, range, selection, children);
    }

    static SymbolKind symbolKind(int lspKind) {
        return switch (lspKind) {
            case 1 -> SymbolKind.FILE;
            case 2 -> SymbolKind.MODULE;
            case 3 -> SymbolKind.NAMESPACE;
            case 4 -> SymbolKind.PACKAGE;
            case 5 -> SymbolKind.CLASS;
            case 6 -> SymbolKind.METHOD;
            case 7 -> SymbolKind.PROPERTY;
            case 8 -> SymbolKind.FIELD;
            case 9 -> SymbolKind.CONSTRUCTOR;
            case 10 -> SymbolKind.ENUM;
            case 11 -> SymbolKind.INTERFACE;
            case 12 -> SymbolKind.FUNCTION;
            case 13 -> SymbolKind.VARIABLE;
            case 14 -> SymbolKind.CONSTANT;
            case 15 -> SymbolKind.STRING;
            case 16 -> SymbolKind.NUMBER;
            case 17 -> SymbolKind.BOOLEAN;
            case 18 -> SymbolKind.ARRAY;
            case 19 -> SymbolKind.OBJECT;
            case 20 -> SymbolKind.KEY;
            case 21 -> SymbolKind.NULL;
            case 22 -> SymbolKind.ENUM_MEMBER;
            case 23 -> SymbolKind.STRUCT;
            case 24 -> SymbolKind.EVENT;
            case 25 -> SymbolKind.OPERATOR;
            case 26 -> SymbolKind.TYPE_PARAMETER;
            default -> SymbolKind.OTHER;
        };
    }

    static CallHierarchyItem callHierarchyItem(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String name = node.path("name").asText("");
        String uri = node.path("uri").asText("");
        if (name.isBlank() || uri.isBlank()) {
            return null;
        }
        Range range = range(node.get("range"));
        JsonNode selectionNode = node.get("selectionRange");
        Range selection = selectionNode == null ? range : range(selectionNode);
        return new CallHierarchyItem(
                name,
                node.path("detail").asText(null),
                symbolKind(node.path("kind").asInt(0)),
                toPath(uri),
                range,
                selection,
                Map.of("uri", uri, "data", rawData(node.get("data"))));
    }

    /** LSP folding ranges; the import block starts collapsed, like in IntelliJ. */
    static List<FoldRange> foldRanges(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<FoldRange> ranges = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            int start = node.path("startLine").asInt(-1);
            int end = node.path("endLine").asInt(-1);
            if (start < 0 || end <= start) {
                continue;
            }
            String kind = node.hasNonNull("kind") ? node.get("kind").asText() : null;
            ranges.add(new FoldRange(start, end, kind, "imports".equals(kind)));
        }
        return List.copyOf(ranges);
    }

    /** Keeps the original LSP item in {@code data} so it can be sent back unchanged. */
    static TypeHierarchyItem typeHierarchyItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = node.path("name").asText("");
        String uri = node.path("uri").asText("");
        if (name.isBlank() || uri.isBlank()) {
            return null;
        }
        Range range = range(node.get("range"));
        JsonNode selectionNode = node.get("selectionRange");
        return new TypeHierarchyItem(name, node.path("detail").asText(null), node.path("kind").asInt(0),
                toPath(uri), range, selectionNode == null ? range : range(selectionNode), node.deepCopy());
    }

    static List<TypeHierarchyItem> typeHierarchyItems(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<TypeHierarchyItem> items = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            TypeHierarchyItem item = typeHierarchyItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static Object rawData(JsonNode data) {
        return data == null || data.isNull() ? "" : data.toString();
    }

    static CallHierarchyCall callHierarchyCall(JsonNode node, String itemField) {
        if (node == null || node.isNull()) {
            return null;
        }
        CallHierarchyItem item = callHierarchyItem(node.get(itemField));
        if (item == null) {
            return null;
        }
        List<Range> fromRanges = new ArrayList<>();
        JsonNode ranges = node.get("fromRanges");
        if (ranges != null && ranges.isArray()) {
            for (JsonNode range : ranges) {
                fromRanges.add(range(range));
            }
        }
        return new CallHierarchyCall(item, fromRanges);
    }

    static Map<String, Object> toLspRange(Range range) {
        Range effective = range == null ? Range.point(0, 0) : range;
        return Map.of(
                "start", toLspPosition(effective.start()),
                "end", toLspPosition(effective.end()));
    }

    static Map<String, Object> toLspPosition(Position position) {
        Position effective = position == null ? new Position(0, 0) : position;
        return Map.of("line", effective.line(), "character", effective.col());
    }

    static int toLspSymbolKind(SymbolKind kind) {
        if (kind == null) {
            return 1;
        }
        for (int lspKind = 1; lspKind <= 26; lspKind++) {
            if (symbolKind(lspKind) == kind) {
                return lspKind;
            }
        }
        return 1;
    }

    static JdtLsService.JavaCodeLens codeLens(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode command = node.get("command");
        String title = command == null ? "" : command.path("title").asText("");
        String commandId = command == null ? "" : command.path("command").asText("");
        if (title.isBlank()) {
            return null;
        }

        List<Location> targets = List.of();
        JsonNode arguments = command.get("arguments");
        if (arguments != null && arguments.isArray() && arguments.size() > 2) {
            targets = locations(arguments.get(2));
        }
        return new JdtLsService.JavaCodeLens(range(node.get("range")), title, commandId, targets);
    }

    static InlayHint inlayHint(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Position position = position(node.get("position"));
        String label = inlayLabel(node.get("label"));
        if (label.isBlank()) {
            return null;
        }
        InlayHintKind kind = switch (node.path("kind").asInt(0)) {
            case 1 -> InlayHintKind.TYPE;
            case 2 -> InlayHintKind.PARAMETER;
            default -> InlayHintKind.OTHER;
        };
        boolean parameter = kind == InlayHintKind.PARAMETER
                || (kind == InlayHintKind.OTHER && label.endsWith(":"));
        if (parameter) {
            kind = InlayHintKind.PARAMETER;
        }
        return new InlayHint(position.line(), position.col(), label, kind,
                parameter, false, null, null,
                parameter, true, parameter);
    }

    private static String inlayLabel(JsonNode label) {
        if (label == null || label.isNull()) {
            return "";
        }
        if (label.isTextual()) {
            return label.asText();
        }
        if (!label.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : label) {
            text.append(part.path("value").asText(""));
        }
        return text.toString();
    }

    static CodeAction codeAction(JsonNode node, String applyCommandId) {
        if (node == null || node.isNull()) {
            return null;
        }
        String title = node.path("title").asText("");
        if (title.isBlank()) {
            return null;
        }
        CodeAction.CodeActionKind kind = codeActionKind(node.path("kind").asText(""));
        boolean preferred = node.path("isPreferred").asBoolean(false);

        List<TextEdit> edits = singleDocumentEdits(node.get("edit"));
        if (!edits.isEmpty()) {
            return new CodeAction(title, kind, edits, null, preferred);
        }
        return new CodeAction(title, kind, List.of(),
                new Command(applyCommandId, title, List.of(node.toString())), preferred);
    }

    static CodeAction.CodeActionKind codeActionKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return CodeAction.CodeActionKind.OTHER;
        }
        if (kind.startsWith("quickfix")) {
            return CodeAction.CodeActionKind.QUICK_FIX;
        }
        if (kind.startsWith("source.organizeImports")) {
            return CodeAction.CodeActionKind.SOURCE_ORGANIZE_IMPORTS;
        }
        if (kind.startsWith("source.fixAll")) {
            return CodeAction.CodeActionKind.SOURCE_FIX_ALL;
        }
        if (kind.startsWith("source")) {
            return CodeAction.CodeActionKind.SOURCE;
        }
        if (kind.startsWith("refactor.extract")) {
            return CodeAction.CodeActionKind.REFACTOR_EXTRACT;
        }
        if (kind.startsWith("refactor.inline")) {
            return CodeAction.CodeActionKind.REFACTOR_INLINE;
        }
        if (kind.startsWith("refactor.rewrite")) {
            return CodeAction.CodeActionKind.REFACTOR_REWRITE;
        }
        if (kind.startsWith("refactor")) {
            return CodeAction.CodeActionKind.REFACTOR;
        }
        return CodeAction.CodeActionKind.OTHER;
    }

    static IdeWorkspaceEdit workspaceEdit(JsonNode workspaceEdit) {
        if (workspaceEdit == null || workspaceEdit.isNull()) {
            return IdeWorkspaceEdit.empty();
        }
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        JsonNode documentChanges = workspaceEdit.get("documentChanges");
        if (documentChanges != null && documentChanges.isArray()) {
            for (JsonNode change : documentChanges) {
                String kind = change.path("kind").asText("");
                if ("rename".equals(kind)) {
                    Path oldPath = toPath(change.path("oldUri").asText(null));
                    Path newPath = toPath(change.path("newUri").asText(null));
                    if (oldPath != null && newPath != null) {
                        operations.add(new IdeWorkspaceEdit.RenameFile(oldPath, newPath));
                    }
                    continue;
                }
                if (!kind.isEmpty() || !change.hasNonNull("textDocument")) {
                    continue;
                }
                Path file = toPath(change.path("textDocument").path("uri").asText(null));
                List<TextEdit> edits = textEdits(change.get("edits"));
                if (file != null && !edits.isEmpty()) {
                    operations.add(new IdeWorkspaceEdit.TextEdits(file, edits));
                }
            }
            return new IdeWorkspaceEdit(operations);
        }
        JsonNode changes = workspaceEdit.get("changes");
        if (changes != null && changes.isObject()) {
            changes.fields().forEachRemaining(entry -> {
                Path file = toPath(entry.getKey());
                List<TextEdit> edits = textEdits(entry.getValue());
                if (file != null && !edits.isEmpty()) {
                    operations.add(new IdeWorkspaceEdit.TextEdits(file, edits));
                }
            });
        }
        return new IdeWorkspaceEdit(operations);
    }

    static JdtLsService.PrepareRenameResult prepareRename(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JdtLsService.PrepareRenameResult.rejected(null);
        }
        if (node.path("defaultBehavior").asBoolean(false)) {
            return JdtLsService.PrepareRenameResult.of(null, null);
        }
        if (node.hasNonNull("range")) {
            String placeholder = node.hasNonNull("placeholder") ? node.get("placeholder").asText() : null;
            return JdtLsService.PrepareRenameResult.of(range(node.get("range")), placeholder);
        }
        if (node.hasNonNull("start") && node.hasNonNull("end")) {
            return JdtLsService.PrepareRenameResult.of(range(node), null);
        }
        return JdtLsService.PrepareRenameResult.rejected(null);
    }

    static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.strip();
        if (trimmed.startsWith("{")) {
            try {
                String inner = ERROR_JSON.readTree(trimmed).path("message").asText("");
                if (!inner.isBlank()) {
                    return inner;
                }
            } catch (Exception ignored) {
            }
        }
        return message;
    }

    static List<TextEdit> singleDocumentEdits(JsonNode workspaceEdit) {
        if (workspaceEdit == null || workspaceEdit.isNull()) {
            return List.of();
        }
        JsonNode changes = workspaceEdit.get("changes");
        if (changes != null && changes.isObject() && changes.size() == 1) {
            return textEdits(changes.fields().next().getValue());
        }
        JsonNode documentChanges = workspaceEdit.get("documentChanges");
        if (documentChanges != null && documentChanges.isArray() && documentChanges.size() == 1) {
            JsonNode change = documentChanges.get(0);
            if (change.hasNonNull("edits") && change.hasNonNull("textDocument")) {
                return textEdits(change.get("edits"));
            }
        }
        return List.of();
    }

    static String documentation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            if (node.hasNonNull("value")) {
                return node.get("value").asText();
            }
            return null;
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode element : node) {
                String part = documentation(element);
                if (part != null && !part.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(part);
                }
            }
            return text.isEmpty() ? null : text.toString();
        }
        return null;
    }

    static String toUri(Path path) {
        if (path == null) {
            return null;
        }
        return canonicalDrive(path.toAbsolutePath().normalize()).toUri().toString();
    }

    static Path canonicalDrive(Path path) {
        Path root = path.getRoot();
        if (root == null) {
            return path;
        }
        String rootText = root.toString();
        if (rootText.length() < 2 || rootText.charAt(1) != ':'
                || !Character.isLowerCase(rootText.charAt(0))) {
            return path;
        }
        String upper = Character.toUpperCase(rootText.charAt(0)) + rootText.substring(1);
        Path canonicalRoot = path.getFileSystem().getPath(upper);
        return path.getNameCount() == 0 ? canonicalRoot : canonicalRoot.resolve(root.relativize(path));
    }

    static Path toPath(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            return Path.of(parsed).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
