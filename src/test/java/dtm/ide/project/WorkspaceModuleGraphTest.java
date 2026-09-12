package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceModuleGraphTest {

    @TempDir
    Path root;

    @Test
    void dependenciesAreBuiltBeforeTheModuleThatNeedsThem() {
        pom("web", List.of("user", "persistence"));
        pom("user", List.of("persistence"));
        pom("persistence", List.of());

        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor()).buildOrderFor(module("web"));

        assertEquals(List.of("persistence", "user", "web"), names(order));
    }

    @Test
    void modulesOutsideTheDependencyTreeAreNotBuilt() {
        pom("web", List.of("user"));
        pom("user", List.of());
        pom("persistence", List.of());

        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor()).buildOrderFor(module("web"));

        assertEquals(List.of("user", "web"), names(order));
    }

    @Test
    void externalDependenciesAreIgnored() {
        pomContent("web", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>web</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        pom("user", List.of());
        pom("persistence", List.of());

        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor()).buildOrderFor(module("web"));

        assertEquals(List.of("web"), names(order));
    }

    @Test
    void aCycleDoesNotHang() {
        pom("web", List.of("user"));
        pom("user", List.of("web"));
        pom("persistence", List.of());

        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor()).buildOrderFor(module("web"));

        assertTrue(order.size() <= 2);
        assertEquals("web", order.getLast().artifactId());
    }

    @Test
    void anAggregatorTargetBuildsEveryModule() {
        pom("web", List.of("user"));
        pom("user", List.of("persistence"));
        pom("persistence", List.of());

        JavaModule aggregator = new JavaModule(root, "parent", "com.example", "parent", "pom",
                List.of(), List.of(), root.resolve("target/classes"));

        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor()).buildOrderFor(aggregator);

        assertEquals(List.of("persistence", "user", "web"), names(order));
    }

    private List<String> names(List<JavaModule> modules) {
        return modules.stream().map(JavaModule::artifactId).toList();
    }

    private JavaProjectDescriptor descriptor() {
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN_MULTIMODULE,
                List.of(module("web"), module("user"), module("persistence")), true, false, 21,
                null);
    }

    private JavaModule module(String artifactId) {
        Path moduleRoot = root.resolve(artifactId);
        return new JavaModule(moduleRoot, artifactId, "com.example", artifactId, "jar",
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                moduleRoot.resolve("target/classes"));
    }

    private void pom(String artifactId, List<String> dependencies) {
        StringBuilder xml = new StringBuilder();
        xml.append("<project>\n  <groupId>com.example</groupId>\n  <artifactId>")
                .append(artifactId).append("</artifactId>\n  <dependencies>\n");
        for (String dependency : dependencies) {
            xml.append("    <dependency>\n      <groupId>com.example</groupId>\n")
                    .append("      <artifactId>").append(dependency)
                    .append("</artifactId>\n    </dependency>\n");
        }
        xml.append("  </dependencies>\n</project>\n");
        pomContent(artifactId, xml.toString());
    }

    private void pomContent(String artifactId, String xml) {
        try {
            Path pom = root.resolve(artifactId).resolve("pom.xml");
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, xml, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
