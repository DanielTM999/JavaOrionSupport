package dtm.ide.lsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspClientCapabilitiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void advertisesCodeLensAndRefreshSupport() {
        Map<String, Object> capabilities = LspClientCapabilities.build(List.of(), List.of());
        Map<String, Object> textDocument =
                (Map<String, Object>) capabilities.get("textDocument");
        Map<String, Object> workspace = (Map<String, Object>) capabilities.get("workspace");

        assertTrue(textDocument.containsKey("codeLens"));
        assertEquals(Map.of("refreshSupport", true), workspace.get("codeLens"));
    }

    @Test
    void readsCodeLensProviderFromServerHandshake() throws Exception {
        var result = MAPPER.readTree("""
                {"capabilities":{"codeLensProvider":{"resolveProvider":true},
                 "completionProvider":{"resolveProvider":true,"triggerCharacters":["."]},
                 "textDocumentSync":{"change":2}}}
                """);

        var capabilities = LspClientCapabilities.readServerCapabilities(result);

        assertTrue(capabilities.codeLens());
        assertTrue(capabilities.completionResolve());
        assertTrue(capabilities.incrementalSync());
    }
}
