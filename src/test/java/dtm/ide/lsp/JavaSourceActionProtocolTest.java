package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceActionProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesAndSeparatesOverrideAndImplementMethods() throws Exception {
        JsonNode response = JSON.readTree("""
                {"type":"demo.Child","methods":[
                  {"key":"a","name":"run","parameters":[],"unimplemented":true,
                   "declaringClass":"Runnable","declaringClassType":"interface"},
                  {"key":"b","name":"close","parameters":["boolean"],"unimplemented":false,
                   "declaringClass":"Base","declaringClassType":"class"}
                ]}
                """);

        JdtLsService.OverrideStatus status = JdtLsService.parseOverrideStatus(response);

        assertEquals("demo.Child", status.type());
        assertEquals("run()", status.methods().get(0).label());
        assertTrue(status.methods().get(0).selected());
        assertFalse(status.methods().get(1).selected());
        assertEquals("class: Base", status.methods().get(1).detail());
    }

    @Test
    void workspaceSymbolsKeepOnlyClassesAndInterfaces() throws Exception {
        JsonNode response = JSON.readTree("""
                [
                  {"name":"CommandLineRunner","kind":11,"containerName":"org.springframework.boot"},
                  {"name":"BaseService","kind":5,"containerName":"demo.base"},
                  {"name":"Status","kind":10,"containerName":"demo"},
                  {"name":"run","kind":6,"containerName":"demo.Main"},
                  {"name":"BaseService","kind":5,"containerName":"demo.base"},
                  {"name":"SemPacote","kind":5}
                ]
                """);

        List<JdtLsService.TypeSymbol> types = JdtLsService.parseWorkspaceTypes(response);

        assertEquals(List.of(
                new JdtLsService.TypeSymbol("org.springframework.boot.CommandLineRunner", true),
                new JdtLsService.TypeSymbol("demo.base.BaseService", false),
                new JdtLsService.TypeSymbol("SemPacote", false)), types);
        assertTrue(JdtLsService.parseWorkspaceTypes(null).isEmpty());
    }

    @Test
    void preservesRawBindingsForGenerationPayloads() throws Exception {
        JsonNode fields = JSON.readTree("""
                [{"bindingKey":"x","name":"id","type":"String","isSelected":false}]
                """);
        List<JdtLsService.SourceItem> items = JdtLsService.variableItems(fields);

        assertEquals("id: String", items.getFirst().label());
        assertFalse(items.getFirst().selected());
        assertEquals("x", JdtLsService.rawValues(items).getFirst().path("bindingKey").asText());
    }

    @Test
    void recognizesOnlyClientSideSourcePrompts() {
        assertTrue(JdtLsService.isSourcePrompt(JdtLsService.GENERATE_CONSTRUCTORS_PROMPT));
        assertTrue(JdtLsService.isSourcePrompt(JdtLsService.GENERATE_DELEGATE_METHODS_PROMPT));
        assertFalse(JdtLsService.isSourcePrompt("java.edit.organizeImports"));
    }
}
