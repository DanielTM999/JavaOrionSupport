package dtm.ide.spring.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringConfigIndexTest {

    @Test
    void readsKeysFromAPropertiesFile(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("application.properties"), """
                server.port=8080
                spring.application.name=pedidos
                """);

        SpringConfigIndex index = SpringConfigIndex.scan(List.of(resources));

        assertTrue(index.knows("server.port"));
        assertTrue(index.knows("spring.application.name"));
        assertFalse(index.knows("server.address"));
    }

    @Test
    void readsKeysFromAYamlFile(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("application.yml"), """
                server:
                  port: 8080
                spring:
                  application:
                    name: pedidos
                """);

        SpringConfigIndex index = SpringConfigIndex.scan(List.of(resources));

        assertTrue(index.knows("server.port"));
        assertTrue(index.knows("spring.application.name"));
    }

    @Test
    void pointsToTheFileAndLineOfEachKey(@TempDir Path resources) throws Exception {
        Path file = resources.resolve("application.properties");
        Files.writeString(file, """
                server.port=8080
                app.timeout=30
                """);

        List<SpringConfigIndex.Entry> entries =
                SpringConfigIndex.scan(List.of(resources)).definitionsOf("app.timeout");

        assertEquals(1, entries.size());
        assertEquals(file, entries.getFirst().file());
        assertEquals(2, entries.getFirst().line());
        assertEquals("30", entries.getFirst().value());
    }

    @Test
    void keepsEveryProfileDefinitionOfTheSameKey(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("application.properties"), "server.port=8080\n");
        Files.writeString(resources.resolve("application-dev.properties"), "server.port=9090\n");

        List<SpringConfigIndex.Entry> entries =
                SpringConfigIndex.scan(List.of(resources)).definitionsOf("server.port");

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(entry -> "dev".equals(entry.profile())));
        assertTrue(entries.stream().anyMatch(entry -> entry.profile().isBlank()));
    }

    @Test
    void collectsProfilesFromFileNamesAndFromTheActiveKey(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("application.properties"),
                "spring.profiles.active=local\n");
        Files.writeString(resources.resolve("application-dev.properties"), "server.port=9090\n");
        Files.writeString(resources.resolve("application-prod.yml"), "server:\n  port: 80\n");

        List<String> profiles = SpringConfigIndex.scan(List.of(resources)).profiles();

        assertTrue(profiles.contains("dev"));
        assertTrue(profiles.contains("prod"));
        assertTrue(profiles.contains("local"));
    }

    @Test
    void treatsKebabAndCamelKeysAsTheSame(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("application.properties"),
                "spring.datasource.hikari.maximum-pool-size=10\n");

        SpringConfigIndex index = SpringConfigIndex.scan(List.of(resources));

        assertTrue(index.knows("spring.datasource.hikari.maximumPoolSize"));
    }

    @Test
    void ignoresFilesThatAreNotSpringConfiguration(@TempDir Path resources) throws Exception {
        Files.writeString(resources.resolve("logback.xml"), "<configuration/>");
        Files.writeString(resources.resolve("messages.properties"), "hello=oi\n");

        assertTrue(SpringConfigIndex.scan(List.of(resources)).isEmpty());
    }

    @Test
    void readsTheProfileFromTheFileName() {
        assertEquals("dev", SpringConfigIndex.profileOf("application-dev.properties"));
        assertEquals("prod", SpringConfigIndex.profileOf("application-prod.yml"));
        assertEquals("", SpringConfigIndex.profileOf("application.properties"));
    }
}
