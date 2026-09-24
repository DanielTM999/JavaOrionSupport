package dtm.ide.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomPropertiesTest {

    private static final String POM = """
            <project>
                <groupId>com.acme</groupId>
                <artifactId>app</artifactId>
                <version>1.2.3</version>
                <!-- <properties><ghost>1</ghost></properties> -->
                <properties>
                    <lombok.version>1.18.42</lombok.version>
                    <java.release>21</java.release>
                </properties>
                <dependencies>
                    <dependency>
                        <version>${lombok.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    @Test
    void findsThePlaceholderAroundAnyPartOfIt() {
        int start = POM.indexOf("${lombok.version}");
        for (int offset = start; offset < start + "${lombok.version}".length(); offset++) {
            PomProperties.Placeholder placeholder = PomProperties.placeholderAt(POM, offset).orElseThrow();
            assertEquals("lombok.version", placeholder.name());
            assertEquals(start, placeholder.start());
        }
    }

    @Test
    void ignoresTextOutsideAPlaceholder() {
        assertTrue(PomProperties.placeholderAt(POM, POM.indexOf("1.2.3")).isEmpty());
        assertTrue(PomProperties.placeholderAt(POM, POM.indexOf("</version>", POM.indexOf("${"))).isEmpty());
    }

    @Test
    void locatesTheDeclarationOfAProperty() {
        PomProperties properties = new PomProperties(() -> null);
        PomProperties.Declaration declaration = properties
                .find(Path.of("pom.xml"), POM, "lombok.version").orElseThrow();

        assertEquals("1.18.42", declaration.value());
        assertEquals(6, declaration.line());
        assertEquals(9, declaration.col());
    }

    @Test
    void commentedPropertiesAreIgnored() {
        PomProperties properties = new PomProperties(() -> null);
        assertTrue(properties.find(Path.of("pom.xml"), POM, "ghost").isEmpty());
    }

    @Test
    void projectFieldsPointToTheirTags() {
        PomProperties properties = new PomProperties(() -> null);
        PomProperties.Declaration version = properties
                .find(Path.of("pom.xml"), POM, "project.version").orElseThrow();

        assertEquals("1.2.3", version.value());
        assertEquals(3, version.line());
        assertEquals(5, version.col());
        assertEquals("1.2.3", properties.find(Path.of("pom.xml"), POM, "pom.version")
                .orElseThrow().value());
    }

    @Test
    void followsAParentInTheLocalRepository(@TempDir Path repository) throws Exception {
        Path parentPom = Files.createDirectories(repository.resolve("org/springframework/boot/"
                        + "spring-boot-dependencies/3.4.0"))
                .resolve("spring-boot-dependencies-3.4.0.pom");
        Files.writeString(parentPom, """
                <project>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <properties>
                    <jackson.version>2.18.1</jackson.version>
                  </properties>
                </project>
                """);
        String child = """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.4.0</version>
                        <relativePath/>
                    </parent>
                    <artifactId>app</artifactId>
                    <properties>
                        <mapper.version>${jackson.version}</mapper.version>
                    </properties>
                </project>
                """;
        PomProperties properties = new PomProperties(() -> repository);

        PomProperties.Declaration jackson = properties
                .find(Path.of("pom.xml"), child, "jackson.version").orElseThrow();

        assertEquals(parentPom.toAbsolutePath().normalize(), jackson.file());
        assertEquals(3, jackson.line());
        assertEquals("2.18.1", properties.resolve("${mapper.version}",
                properties.declarations(Path.of("pom.xml"), child)));

        PomProperties.ParentReference parent = properties.parentAt(Path.of("pom.xml"), child,
                child.indexOf("spring-boot-dependencies")).orElseThrow();
        assertEquals(parentPom.toAbsolutePath().normalize(), parent.file());
    }

    @Test
    void theChildOverridesItsParent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <artifactId>parent</artifactId>
                    <properties><shared>parent</shared></properties>
                </project>
                """);
        Path module = Files.createDirectories(root.resolve("module")).resolve("pom.xml");
        String child = """
                <project>
                    <parent><artifactId>parent</artifactId></parent>
                    <properties><shared>child</shared></properties>
                </project>
                """;
        PomProperties properties = new PomProperties(() -> null);

        assertEquals("child", properties.find(module, child, "shared").orElseThrow().value());
    }
}
