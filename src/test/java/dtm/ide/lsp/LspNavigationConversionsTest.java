package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.editor.TextOffsets;
import dtm.ide.settings.InlayHintsMode;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspNavigationConversionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void selectionRangesGoFromTheInnermostToTheOutermostWithoutRepeats() throws Exception {
        JsonNode result = JSON.readTree("""
                [{"range":{"start":{"line":1,"character":8},"end":{"line":1,"character":13}},
                  "parent":{"range":{"start":{"line":1,"character":8},"end":{"line":1,"character":13}},
                    "parent":{"range":{"start":{"line":1,"character":4},"end":{"line":1,"character":20}},
                      "parent":{"range":{"start":{"line":0,"character":0},"end":{"line":2,"character":1}}}}}}]
                """);

        List<Range> chain = LspConversions.selectionChain(result);

        assertEquals(3, chain.size());
        assertEquals(8, chain.getFirst().start().col());
        assertEquals(4, chain.get(1).start().col());
        assertEquals(0, chain.getLast().start().line());
        assertTrue(LspConversions.selectionChain(null).isEmpty());
    }

    @Test
    void offsetsAndPositionsRoundTrip() {
        String text = "class A {\n    int value;\n}";
        assertEquals(1, TextOffsets.position(text, text.indexOf("value")).line());
        assertEquals(8, TextOffsets.position(text, text.indexOf("value")).col());

        List<int[]> offsets = TextOffsets.offsets(text, List.of(
                new Range(TextOffsets.position(text, 14), TextOffsets.position(text, 19)),
                new Range(TextOffsets.position(text, 0), TextOffsets.position(text, text.length()))));
        assertArrayEquals(new int[]{14, 19}, offsets.getFirst());
        assertArrayEquals(new int[]{0, text.length()}, offsets.getLast());
    }

    @Test
    void typeHierarchyItemsKeepTheOriginalLspItem() throws Exception {
        JsonNode result = JSON.readTree("""
                [{"name":"ArrayList","kind":5,"detail":"java.util","uri":"file:///tmp/ArrayList.java",
                  "range":{"start":{"line":10,"character":0},"end":{"line":90,"character":1}},
                  "selectionRange":{"start":{"line":10,"character":13},"end":{"line":10,"character":22}},
                  "data":{"handle":"=x"}},
                 {"name":"","uri":"file:///tmp/Bad.java"}]
                """);

        List<TypeHierarchyItem> items = LspConversions.typeHierarchyItems(result);

        assertEquals(1, items.size());
        TypeHierarchyItem item = items.getFirst();
        assertEquals("ArrayList", item.name());
        assertEquals(5, item.kind());
        assertEquals(13, item.selectionRange().start().col());
        assertEquals("=x", ((JsonNode) item.data()).path("data").path("handle").asText());
    }

    @Test
    void foldingRangesCollapseImportsByDefault() throws Exception {
        JsonNode result = JSON.readTree("""
                [{"startLine":2,"endLine":5,"kind":"imports"},
                 {"startLine":7,"endLine":30},
                 {"startLine":9,"endLine":9,"kind":"comment"}]
                """);

        List<FoldRange> ranges = LspConversions.foldRanges(result);

        assertEquals(List.of(new FoldRange(2, 5, "imports", true), new FoldRange(7, 30, null, false)), ranges);
    }

    @Test
    void resolvedDocumentationReplacesTheDescription() throws Exception {
        JsonNode raw = JSON.readTree("{\"label\":\"println\",\"data\":{\"id\":1}}");
        AutoCompleteItem item = new AutoCompleteItem("println", "println", "void", null, null,
                AutoCompleteItem.Kind.METHOD, List.of(), false, raw);

        AutoCompleteItem resolved = LspConversions.withResolvedDocumentation(item, JSON.readTree("""
                {"label":"println","detail":"void println(String x)",
                 "documentation":{"kind":"markdown","value":"Prints a line."}}
                """));

        assertTrue(resolved.description().contains("Prints a line."));
        assertNull(resolved.data());
        assertSame(item, LspConversions.withResolvedDocumentation(item, JSON.readTree("{\"label\":\"x\"}")));
    }

    @Test
    void completionItemsWithoutDocumentationCarryTheirResolveData() throws Exception {
        AutoCompleteItem item = LspConversions.completionItem(JSON.readTree("""
                {"label":"println","kind":2,"insertText":"println","data":{"id":7}}
                """));

        assertEquals(7, ((JsonNode) item.data()).path("data").path("id").asInt());
    }

    @Test
    void inlayHintModesMapToJdtSettings() {
        assertEquals(Map.of("enabled", "none"), JdtLsSettings.inlayHints(InlayHintsMode.NONE).get("parameterNames"));
        assertEquals(Map.of("enabled", true), JdtLsSettings.inlayHints(InlayHintsMode.ALL).get("variableTypes"));
        Map<String, Object> updated = JdtLsSettings.withInlayHints(
                Map.of("java", Map.of("autobuild", Map.of("enabled", false))), InlayHintsMode.ALL);
        @SuppressWarnings("unchecked")
        Map<String, Object> java = (Map<String, Object>) updated.get("java");
        assertEquals(Map.of("enabled", false), java.get("autobuild"));
        assertEquals(JdtLsSettings.inlayHints(InlayHintsMode.ALL), java.get("inlayHints"));
    }
}
