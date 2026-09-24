package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportCandidatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SOURCE = """
            package demo;

            class Demo {
                List<String> names = new ArrayList<>();
                Foo other;
            }
            """;

    @Test
    void keepsOnlyUnresolvedTypesInsideThePastedRange() throws Exception {
        List<JsonNode> diagnostics = List.of(
                diagnostic("16777218", "List cannot be resolved to a type", 3, 4, 3, 8),
                diagnostic("16777218", "ArrayList cannot be resolved to a type", 3, 29, 3, 38),
                diagnostic("16777218", "Foo cannot be resolved to a type", 4, 4, 4, 7),
                diagnostic("1234", "Something else", 3, 4, 3, 8));

        List<JsonNode> unresolved = ImportCandidates.unresolvedIn(diagnostics, SOURCE,
                Range.of(3, 0, 3, 43));

        assertEquals(List.of("List cannot be resolved to a type", "ArrayList cannot be resolved to a type"),
                unresolved.stream().map(node -> node.path("message").asText()).toList());
    }

    @Test
    void ignoresStaleDiagnosticsWhoseRangeNoLongerPointsAtTheName() throws Exception {
        List<JsonNode> diagnostics = List.of(
                diagnostic("16777218", "List cannot be resolved to a type", 3, 5, 3, 9));

        assertTrue(ImportCandidates.unresolvedIn(diagnostics, SOURCE, Range.of(3, 0, 3, 43)).isEmpty());
    }

    @Test
    void groupsImportQuickFixesBySimpleName() throws Exception {
        JsonNode actions = JSON.readTree("""
                [
                  {"title": "Import 'List' (java.util)", "kind": "quickfix"},
                  {"title": "Import 'List' (java.awt)", "kind": "quickfix"},
                  {"title": "Import 'ArrayList' (java.util)", "kind": "quickfix"},
                  {"title": "Import 'Entry' (java.util.Map)", "kind": "quickfix"},
                  {"title": "Import 'List' (java.util)", "kind": "quickfix"},
                  {"title": "Create class 'List'", "kind": "quickfix"}
                ]
                """);

        Map<String, List<String>> candidates = ImportCandidates.fromActions(actions);

        assertEquals(List.of("List", "ArrayList", "Entry"), List.copyOf(candidates.keySet()));
        assertEquals(List.of("java.util.List", "java.awt.List"), candidates.get("List"));
        assertEquals(List.of("java.util.ArrayList"), candidates.get("ArrayList"));
        assertEquals(List.of("java.util.Map.Entry"), candidates.get("Entry"));
    }

    private static JsonNode diagnostic(String code, String message, int startLine, int startCol,
                                       int endLine, int endCol) throws Exception {
        return JSON.readTree("""
                {"code": "%s", "message": "%s", "severity": 1,
                 "range": {"start": {"line": %d, "character": %d}, "end": {"line": %d, "character": %d}}}
                """.formatted(code, message, startLine, startCol, endLine, endCol));
    }
}
