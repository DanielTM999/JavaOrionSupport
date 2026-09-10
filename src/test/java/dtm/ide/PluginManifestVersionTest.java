package dtm.ide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PluginManifestVersionTest {

    @Test
    void filteredManifestUsesOneVersionForTheCollectionAndEveryPlugin() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/META-INF/plugin-configs.json")) {
            assertNotNull(input);
            JsonNode manifest = new ObjectMapper().readTree(input);
            String version = manifest.path("pluginCollectionVersion").asText();

            assertFalse(version.isBlank());
            assertFalse(version.contains("${"));
            for (JsonNode plugin : manifest.path("plugins")) {
                assertEquals(version, plugin.path("version").asText());
            }
        }
    }
}
