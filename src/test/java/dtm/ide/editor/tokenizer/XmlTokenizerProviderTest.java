package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTokenizerProviderTest {

    private final XmlTokenizerProvider tokenizer = new XmlTokenizerProvider();

    @Test
    void marksTagNames() {
        List<Token> tokens = tokenize("<project><groupId>x</groupId></project>");

        assertEquals(List.of("project", "groupId", "groupId", "project"),
                textsOf(tokens, XmlTokenizerProvider.TOKEN_TAG));
    }

    @Test
    void marksAttributeNamesAndValues() {
        List<Token> tokens = tokenize("<project xmlns=\"http://maven.apache.org\">");

        assertEquals(List.of("xmlns"), textsOf(tokens, XmlTokenizerProvider.TOKEN_ATTRIBUTE));
        assertTrue(textsOf(tokens, TokenType.STRING).contains("\"http://maven.apache.org\""));
    }

    @Test
    void marksComments() {
        List<Token> tokens = tokenize("<a><!-- nao mexer --></a>");

        assertEquals(List.of("<!-- nao mexer -->"), textsOf(tokens, TokenType.COMMENT));
    }

    @Test
    void marksCdataAsASingleBlock() {
        List<Token> tokens = tokenize("<a><![CDATA[ <nao> e tag ]]></a>");

        assertTrue(textsOf(tokens, TokenType.STRING).contains("<![CDATA[ <nao> e tag ]]>"));
    }

    @Test
    void coversTheWholeInputWithoutGapsOrOverlaps() {
        String source = """
                <?xml version="1.0"?>
                <project>
                    <!-- comentario -->
                    <artifactId>demo</artifactId>
                </project>
                """;

        int cursor = 0;
        for (Token token : tokenize(source)) {
            assertEquals(cursor, token.getStartOffset());
            cursor = token.getEndOffset();
        }
        assertEquals(source.length(), cursor);
    }

    @Test
    void anUnterminatedTagDoesNotLoopForever() {
        List<Token> tokens = tokenize("<project");

        assertEquals(List.of("project"), textsOf(tokens, XmlTokenizerProvider.TOKEN_TAG));
    }

    private List<Token> tokenize(String source) {
        return List.copyOf(tokenizer.tokenize(source, null));
    }

    private static List<String> textsOf(List<Token> tokens, String type) {
        return tokens.stream()
                .filter(token -> type.equals(token.getType()))
                .map(Token::getText)
                .toList();
    }
}
