package dtm.ide.spring.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringConfigDocumentTest {

    @Test
    void detectsFormatFromTheFileName() {
        assertEquals(SpringConfigDocument.Format.YAML,
                SpringConfigDocument.Format.of("application.yml"));
        assertEquals(SpringConfigDocument.Format.YAML,
                SpringConfigDocument.Format.of("application-dev.yaml"));
        assertEquals(SpringConfigDocument.Format.PROPERTIES,
                SpringConfigDocument.Format.of("application.properties"));
        assertEquals(SpringConfigDocument.Format.PROPERTIES,
                SpringConfigDocument.Format.of(null));
    }

    @Test
    void readsPropertiesKeys() {
        List<SpringConfigDocument.ConfigKey> keys = properties("""
                server.port=8080
                spring.datasource.url=jdbc:postgresql://localhost/app
                """);

        assertEquals(2, keys.size());
        assertEquals("server.port", keys.getFirst().key());
        assertEquals("8080", keys.getFirst().value());
        assertEquals(0, keys.getFirst().line());
        assertEquals("spring.datasource.url", keys.get(1).key());
        assertTrue(keys.get(1).value().startsWith("jdbc:"));
    }

    @Test
    void skipsCommentsAndBlankLines() {
        List<SpringConfigDocument.ConfigKey> keys = properties("""
                # comentario
                ! outro comentario

                server.port=8080
                """);

        assertEquals(1, keys.size());
        assertEquals(3, keys.getFirst().line());
    }

    @Test
    void acceptsColonAsSeparatorInProperties() {
        assertEquals("server.port", properties("server.port: 8080").getFirst().key());
    }

    @Test
    void recordsTheKeyColumns() {
        SpringConfigDocument.ConfigKey key = properties("server.port=8080").getFirst();

        assertEquals(0, key.keyStart());
        assertEquals("server.port".length(), key.keyEnd());
    }

    @Test
    void flattensYamlIntoDottedKeys() {
        List<SpringConfigDocument.ConfigKey> keys = yaml("""
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/app
                    username: app
                server:
                  port: 8080
                """);

        List<String> names = keys.stream().map(SpringConfigDocument.ConfigKey::key).toList();
        assertTrue(names.contains("spring.datasource.url"));
        assertTrue(names.contains("spring.datasource.username"));
        assertTrue(names.contains("server.port"));
    }

    @Test
    void yamlValuesKeepColonsIntact() {
        SpringConfigDocument.ConfigKey url = yaml("""
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/app
                """).stream()
                .filter(key -> key.key().equals("spring.datasource.url"))
                .findFirst()
                .orElseThrow();

        assertEquals("jdbc:postgresql://localhost/app", url.value());
    }

    @Test
    void parentKeysHaveNoValue() {
        SpringConfigDocument.ConfigKey parent = yaml("""
                spring:
                  application:
                    name: demo
                """).getFirst();

        assertEquals("spring", parent.key());
        assertFalse(parent.hasValue());
    }

    @Test
    void dedentingClosesTheDeeperLevels() {
        List<String> names = yaml("""
                spring:
                  jpa:
                    show-sql: true
                logging:
                  level:
                    root: INFO
                """).stream().map(SpringConfigDocument.ConfigKey::key).toList();

        assertTrue(names.contains("spring.jpa.show-sql"));
        assertTrue(names.contains("logging.level.root"));
        assertFalse(names.contains("spring.jpa.logging"));
    }

    @Test
    void documentSeparatorResetsThePath() {
        List<String> names = yaml("""
                spring:
                  application:
                    name: demo
                ---
                server:
                  port: 9090
                """).stream().map(SpringConfigDocument.ConfigKey::key).toList();

        assertTrue(names.contains("server.port"));
        assertFalse(names.contains("spring.server.port"));
    }

    @Test
    void listItemsAreSkipped() {
        List<String> names = yaml("""
                spring:
                  profiles:
                    include:
                      - dev
                      - local
                """).stream().map(SpringConfigDocument.ConfigKey::key).toList();

        assertTrue(names.contains("spring.profiles.include"));
        assertEquals(3, names.size(), "os itens da lista nao sao chaves");
    }

    @Test
    void yamlCommentsAreIgnored() {
        assertTrue(yaml("# so um comentario\n").isEmpty());
    }

    @Test
    void findsTheKeyOnALine() {
        String text = "server.port=8080\nspring.jpa.show-sql=true";

        assertEquals("spring.jpa.show-sql",
                SpringConfigDocument.keyAt(text, 1, SpringConfigDocument.Format.PROPERTIES));
        assertEquals("",
                SpringConfigDocument.keyAt(text, 5, SpringConfigDocument.Format.PROPERTIES));
    }

    @Test
    void propertiesPrefixIsWhatWasTyped() {
        assertEquals("server.po", SpringConfigDocument.keyPrefixAt(
                "server.po", 0, 9, SpringConfigDocument.Format.PROPERTIES).orElseThrow());
    }

    @Test
    void yamlPrefixIncludesTheAncestors() {
        String text = """
                spring:
                  datasource:
                    ur
                """;

        assertEquals("spring.datasource.ur",
                SpringConfigDocument.keyPrefixAt(text, 2, 6,
                        SpringConfigDocument.Format.YAML).orElseThrow());
    }

    @Test
    void yamlPrefixAtTheRootHasNoAncestors() {
        assertEquals("serv", SpringConfigDocument.keyPrefixAt(
                "serv", 0, 4, SpringConfigDocument.Format.YAML).orElseThrow());
    }

    @Test
    void thereIsNoKeyPrefixWhenTheCaretIsInTheValue() {
        assertTrue(SpringConfigDocument.keyPrefixAt(
                "server.port=80", 0, 14, SpringConfigDocument.Format.PROPERTIES).isEmpty());
        assertTrue(SpringConfigDocument.keyPrefixAt(
                "  port: 80", 0, 10, SpringConfigDocument.Format.YAML).isEmpty());
    }

    @Test
    void anEmptyPrefixMeansTheKeyHasNotStartedYet() {
        assertEquals("", SpringConfigDocument.keyPrefixAt(
                "", 0, 0, SpringConfigDocument.Format.PROPERTIES).orElseThrow());
    }

    @Test
    void handlesEmptyInput() {
        assertTrue(properties("").isEmpty());
        assertTrue(yaml("").isEmpty());
        assertEquals("", SpringConfigDocument.keyPrefixAt(
                "", 0, 0, SpringConfigDocument.Format.YAML).orElseThrow());
    }

    private static List<SpringConfigDocument.ConfigKey> properties(String text) {
        return SpringConfigDocument.keys(text, SpringConfigDocument.Format.PROPERTIES);
    }

    private static List<SpringConfigDocument.ConfigKey> yaml(String text) {
        return SpringConfigDocument.keys(text, SpringConfigDocument.Format.YAML);
    }
}
