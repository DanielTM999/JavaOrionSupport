package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTokenizerProviderTest {

    private final ConfigTokenizerProvider properties =
            new ConfigTokenizerProvider(ConfigTokenizerProvider.Mode.PROPERTIES);
    private final ConfigTokenizerProvider yaml =
            new ConfigTokenizerProvider(ConfigTokenizerProvider.Mode.YAML);

    @Test
    void tokensCoverTheWholeSource() {
        String source = """
                # datasource
                spring.datasource.url=jdbc:postgresql://localhost/app
                spring.datasource.password=${DB_PASSWORD}
                server.port=8080
                """;

        int cursor = 0;
        for (Token token : properties.tokenize(source, null)) {
            assertEquals(cursor, token.getStartOffset());
            cursor = token.getEndOffset();
        }
        assertEquals(source.length(), cursor);
    }

    @Test
    void yamlTokensCoverTheWholeSource() {
        String source = """
                spring:
                  application:
                    name: demo
                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                """;

        int cursor = 0;
        for (Token token : yaml.tokenize(source, null)) {
            assertEquals(cursor, token.getStartOffset());
            cursor = token.getEndOffset();
        }
        assertEquals(source.length(), cursor);
    }

    @Test
    void splitsKeyFromValue() {
        List<Token> tokens = tokens(properties, "server.port=8080");

        assertEquals("server.port", textOfType(tokens, ConfigTokenizerProvider.TOKEN_KEY));
        assertEquals("8080", textOfType(tokens, TokenType.NUMBER));
    }

    @Test
    void treatsHashAndBangAsComments() {
        assertTrue(hasType(tokens(properties, "# comentario"), TokenType.COMMENT));
        assertTrue(hasType(tokens(properties, "! comentario legado"), TokenType.COMMENT));
    }

    @Test
    void keepsJdbcColonsInsideTheValue() {
        List<Token> tokens = tokens(properties, "spring.datasource.url=jdbc:postgresql://localhost/app");

        assertEquals("spring.datasource.url", textOfType(tokens, ConfigTokenizerProvider.TOKEN_KEY));
        assertTrue(textOfType(tokens, TokenType.STRING).startsWith("jdbc:postgresql"));
    }

    @Test
    void highlightsPlaceholders() {
        List<Token> tokens = tokens(properties, "spring.datasource.password=${DB_PASSWORD}");

        assertEquals("${DB_PASSWORD}", textOfType(tokens, ConfigTokenizerProvider.TOKEN_PLACEHOLDER));
    }

    @Test
    void highlightsPlaceholderSurroundedByText() {
        List<Token> tokens = tokens(properties, "app.url=http://${HOST}:8080/api");

        assertEquals("${HOST}", textOfType(tokens, ConfigTokenizerProvider.TOKEN_PLACEHOLDER));
        assertTrue(hasText(tokens, "http://"));
        assertTrue(hasText(tokens, ":8080/api"));
    }

    @Test
    void handlesUnterminatedPlaceholder() {
        List<Token> tokens = tokens(properties, "app.url=${HOST");

        assertEquals("${HOST", textOfType(tokens, ConfigTokenizerProvider.TOKEN_PLACEHOLDER));
    }

    @Test
    void booleanValuesReadAsLiterals() {
        assertEquals("true",
                textOfType(tokens(properties, "spring.jpa.show-sql=true"), TokenType.KEYWORD));
    }

    @Test
    void keyWithoutSeparatorIsStillTokenized() {
        List<Token> tokens = tokens(properties, "chaveSolta");

        assertFalse(tokens.isEmpty());
        assertFalse(hasType(tokens, ConfigTokenizerProvider.TOKEN_KEY));
    }

    @Test
    void yamlKeysAreDetectedWithIndentation() {
        List<Token> tokens = tokens(yaml, "  name: demo");

        assertEquals("name", textOfType(tokens, ConfigTokenizerProvider.TOKEN_KEY));
        assertEquals("demo", textOfType(tokens, TokenType.STRING));
    }

    @Test
    void yamlColonInsideValueDoesNotSplitTheKey() {
        List<Token> tokens = tokens(yaml, "  url: jdbc:postgresql://localhost/app");

        assertEquals("url", textOfType(tokens, ConfigTokenizerProvider.TOKEN_KEY));
    }

    @Test
    void yamlDocumentMarkerSeparatesProfiles() {
        assertTrue(hasType(tokens(yaml, "---"), ConfigTokenizerProvider.TOKEN_DOCUMENT_MARKER));
    }

    @Test
    void yamlListItemsKeepTheirDash() {
        List<Token> tokens = tokens(yaml, "  - primeiro");

        assertTrue(hasText(tokens, "-"));
        assertTrue(hasText(tokens, "primeiro"));
    }

    @Test
    void yamlKeyWithoutValueIsStillAKey() {
        assertEquals("spring", textOfType(tokens(yaml, "spring:"),
                ConfigTokenizerProvider.TOKEN_KEY));
    }

    private static List<Token> tokens(ConfigTokenizerProvider tokenizer, String source) {
        return List.copyOf(tokenizer.tokenize(source, null));
    }

    private static String textOfType(List<Token> tokens, String type) {
        return tokens.stream()
                .filter(token -> token.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum token do tipo " + type))
                .getText();
    }

    private static boolean hasType(List<Token> tokens, String type) {
        return tokens.stream().anyMatch(token -> token.getType().equals(type));
    }

    private static boolean hasText(List<Token> tokens, String text) {
        return tokens.stream().anyMatch(token -> token.getText().equals(text));
    }
}
