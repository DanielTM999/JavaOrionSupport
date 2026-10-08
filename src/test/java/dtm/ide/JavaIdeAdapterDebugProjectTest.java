package dtm.ide;

import dtm.ide.adapter.DebugSupport;
import dtm.ide.project.JavaModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaIdeAdapterDebugProjectTest {

    @TempDir
    Path root;

    @Test
    void testFilesResolveToTheirOwnModuleInsteadOfTheAggregator() {
        JavaModule aggregator = module(root, "cautcar_laudos", "pom");
        JavaModule consulta = module(root.resolve("consulta"), "consulta", "jar");
        JavaModule laudo = module(root.resolve("laudo"), "laudo", "jar");
        Path test = root.resolve("consulta/src/test/java/cautcar/laudos/mappers/MapperTest.java");

        JavaModule resolved = JavaIdeAdapter.mostSpecificModule(
                List.of(aggregator, laudo, consulta), test);

        assertEquals(consulta, resolved);
        assertEquals("consulta", DebugSupport.debugProjectName(resolved));
    }

    @Test
    void anAggregatorIsNeverSentAsTheDebugProject() {
        assertNull(DebugSupport.debugProjectName(module(root, "cautcar_laudos", "pom")));
        assertNull(DebugSupport.debugProjectName(null));
        assertNull(JavaIdeAdapter.mostSpecificModule(
                List.of(module(root, "cautcar_laudos", "pom")), root.resolve("Outro.java")));
    }

    private static JavaModule module(Path root, String artifactId, String packaging) {
        return new JavaModule(root, artifactId, "cautcar.laudos", artifactId, packaging,
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
    }
}
