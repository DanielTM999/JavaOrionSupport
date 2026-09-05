package dtm.ide.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.lsp.JdtLsService;
import dtm.ide.project.JavaProjectConventions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSemanticTestDiscoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void enrichesProvisionalTreeWithSemanticMethods() throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>x</groupId><artifactId>x</artifactId></project>");
        Path file = root.resolve("src/test/java/x/DemoTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package x; class DemoTest { @Test void works() {} }");
        JsonNode response = JSON.readTree("""
                [{"fullName":"x.DemoTest","testLevel":5,"children":[
                  {"fullName":"x.DemoTest#works","label":"works()","testLevel":6,
                   "range":{"start":{"line":4}}}
                ]}]
                """);
        JdtLsService lsp = new JdtLsService(null, null, null, null) {
            @Override
            public boolean isTestRunnerAvailable() {
                return true;
            }

            @Override
            public JsonNode findTestTypesAndMethods(Path ignored) {
                return response;
            }
        };

        List<JavaTest> tests = JavaSemanticTestDiscovery.enrich(
                JavaProjectConventions.describe(root), lsp, List.of());

        assertEquals(1, tests.size());
        assertEquals("x.DemoTest", tests.getFirst().className());
        assertEquals("works", tests.getFirst().methodName());
        assertEquals(5, tests.getFirst().line());
    }
}
