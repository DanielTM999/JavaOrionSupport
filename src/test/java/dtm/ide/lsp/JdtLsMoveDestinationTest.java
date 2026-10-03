package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdtLsMoveDestinationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void picksTheDestinationWhoseFolderIsTheDropTarget() throws Exception {
        Path root = Path.of("workspace").toAbsolutePath().normalize().resolve("src/main/java");
        Path target = root.resolve("demo/c");
        JsonNode destinations = JSON.readTree("[" + node("demo.b", root.resolve("demo/b")) + ","
                + node("demo.c", target) + "]");

        JsonNode chosen = JdtLsService.moveDestinationFor(destinations, target);

        assertEquals("demo.c", chosen.path("displayName").asText());
    }

    @Test
    void returnsNothingWhenTheTargetIsNotAPackage() throws Exception {
        Path root = Path.of("workspace").toAbsolutePath().normalize().resolve("src/main/java");
        JsonNode destinations = JSON.readTree("[" + node("demo.b", root.resolve("demo/b")) + "]");

        assertNull(JdtLsService.moveDestinationFor(destinations, root.resolve("resources")));
    }

    private static String node(String name, Path folder) {
        return "{\"displayName\":\"" + name + "\",\"uri\":\"" + LspConversions.toUri(folder) + "\"}";
    }
}
