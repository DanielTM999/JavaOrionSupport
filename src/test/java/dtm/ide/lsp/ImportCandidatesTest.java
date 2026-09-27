package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void acceptsAPastedAnnotation() throws Exception {
        String source = """
                package demo;

                class Demo {
                    @Getter
                    @Transactional(readOnly = true)
                    String name;
                }
                """;
        List<JsonNode> diagnostics = List.of(
                diagnostic("16777218", "Getter cannot be resolved to a type", 3, 5, 3, 11),
                diagnostic("16777218", "Transactional cannot be resolved to a type", 4, 4, 4, 18));

        List<JsonNode> unresolved = ImportCandidates.unresolvedIn(diagnostics, source,
                Range.of(3, 4, 4, 35));

        assertEquals(2, unresolved.size());
    }

    @Test
    void keepsOneDiagnosticPerNameSoEachGetsItsOwnCodeActionRequest() throws Exception {
        String source = """
                package demo;

                class Demo {
                    @Id
                    @EqualsAndHashCode.Include
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                    @Id
                    private Long other;
                }
                """;
        List<JsonNode> diagnostics = List.of(
                diagnostic("16777218", "Id cannot be resolved to a type", 3, 5, 3, 7),
                diagnostic("16777218", "EqualsAndHashCode cannot be resolved to a type", 4, 5, 4, 22),
                diagnostic("16777218", "GeneratedValue cannot be resolved to a type", 5, 5, 5, 19),
                diagnostic("570425394", "GenerationType cannot be resolved to a variable", 5, 31, 5, 45),
                diagnostic("16777218", "Id cannot be resolved to a type", 7, 5, 7, 7));

        Map<String, JsonNode> byName = ImportCandidates.unresolvedByName(diagnostics, source,
                Range.of(3, 0, 8, 23), Set.of());

        assertEquals(List.of("Id", "EqualsAndHashCode", "GeneratedValue", "GenerationType"),
                List.copyOf(byName.keySet()));
        assertEquals(3, byName.get("Id").path("range").path("start").path("line").asInt());
        assertEquals(List.of("EqualsAndHashCode", "GenerationType"), List.copyOf(
                ImportCandidates.unresolvedByName(diagnostics, source, Range.of(3, 0, 8, 23),
                        Set.of("Id", "GeneratedValue")).keySet()));
    }

    @Test
    void mergesCandidatesFromSeparateRequestsWithoutDuplicates() throws Exception {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        ImportCandidates.merge(merged, ImportCandidates.fromActions(JSON.readTree("""
                [{"title": "Import 'Id' (jakarta.persistence)"},
                 {"title": "Import 'Id' (org.springframework.data.annotation)"}]
                """)));
        ImportCandidates.merge(merged, ImportCandidates.fromActions(JSON.readTree("""
                [{"title": "Import 'GeneratedValue' (jakarta.persistence)"},
                 {"title": "Import 'Id' (jakarta.persistence)"}]
                """)));

        assertEquals(List.of("Id", "GeneratedValue"), List.copyOf(merged.keySet()));
        assertEquals(List.of("jakarta.persistence.Id", "org.springframework.data.annotation.Id"),
                merged.get("Id"));
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
