package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static dtm.ide.lsp.LspRequests.INTERACTIVE_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;

final class JdtClassFiles {
    private final LspRequests requests;
    private final BooleanSupplier definitionsAvailable;

    JdtClassFiles(LspRequests requests, BooleanSupplier definitionsAvailable) {
        this.requests = requests;
        this.definitionsAvailable = definitionsAvailable;
    }

    List<Location> definitionsAtUri(String uri, int line, int col) {
        if (!definitionsAvailable.getAsBoolean() || !JavaClassFileNavigation.isClassFileUri(uri)) {
            return List.of();
        }
        Map<String, Object> params = Map.of(
                "textDocument", Map.of("uri", uri),
                "position", Map.of(
                        "line", Math.max(0, line),
                        "character", Math.max(0, col)));
        return LspConversions.locations(requests.requestInteractive(
                "textDocument/definition", params, INTERACTIVE_TIMEOUT_MS));
    }

    HoverInfo hoverAtUri(String uri, int line, int col) {
        if (!JavaClassFileNavigation.isClassFileUri(uri)) {
            return null;
        }
        Map<String, Object> params = Map.of(
                "textDocument", Map.of("uri", uri),
                "position", Map.of(
                        "line", Math.max(0, line),
                        "character", Math.max(0, col)));
        return LspConversions.hover(requests.requestInteractive(
                "textDocument/hover", params, INTERACTIVE_TIMEOUT_MS));
    }

    boolean isClassFileUri(String uri) {
        return JavaClassFileNavigation.isClassFileUri(uri);
    }

    String classFileSourceName(String uri) {
        return JavaClassFileNavigation.sourceFileName(uri);
    }

    String classFileTabKey(String uri) {
        return JavaClassFileNavigation.tabKey(uri);
    }

    String classFileContents(String uri) {
        if (!JavaClassFileNavigation.isClassFileUri(uri)) {
            return null;
        }
        JsonNode result = requests.requestInteractive(
                "java/classFileContents", Map.of("uri", uri), REQUEST_TIMEOUT_MS * 2);
        if (result == null || result.isNull()) {
            return null;
        }
        if (result.isTextual()) {
            return result.asText();
        }
        String content = result.path("contents").asText("");
        if (content.isBlank()) {
            content = result.path("content").asText("");
        }
        return content.isBlank() ? null : content;
    }
}
