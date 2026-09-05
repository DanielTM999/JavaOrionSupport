package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.project.editor.SemanticToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTokenDecoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TYPES = List.of("class", "method", "variable", "annotation");
    private static final List<String> MODIFIERS = List.of("static", "final", "public", "declaration");

    @Test
    void decodesASingleToken() {
        List<SemanticToken> tokens = decode(2, 4, 6, 0, 0);

        assertEquals(1, tokens.size());
        SemanticToken token = tokens.getFirst();
        assertEquals("class", token.type());
        assertEquals(2, token.range().start().line());
        assertEquals(4, token.range().start().col());
        assertEquals(10, token.range().end().col());
        assertTrue(token.modifiers().isEmpty());
    }

    @Test
    void columnDeltasAccumulateWithinTheSameLine() {
        List<SemanticToken> tokens = decode(
                0, 5, 3, 2, 0,
                0, 4, 2, 2, 0);

        assertEquals(0, tokens.get(1).range().start().line());
        assertEquals(9, tokens.get(1).range().start().col(),
                "na mesma linha o delta soma a coluna anterior");
    }

    @Test
    void columnResetsOnANewLine() {
        List<SemanticToken> tokens = decode(
                0, 20, 3, 2, 0,
                1, 4, 2, 2, 0);

        assertEquals(1, tokens.get(1).range().start().line());
        assertEquals(4, tokens.get(1).range().start().col(),
                "ao mudar de linha o delta e absoluto");
    }

    @Test
    void lineDeltasAccumulate() {
        List<SemanticToken> tokens = decode(
                3, 0, 2, 0, 0,
                2, 0, 2, 0, 0,
                5, 0, 2, 0, 0);

        assertEquals(3, tokens.get(0).range().start().line());
        assertEquals(5, tokens.get(1).range().start().line());
        assertEquals(10, tokens.get(2).range().start().line());
    }

    @Test
    void decodesModifierBitmask() {
        List<SemanticToken> tokens = decode(0, 0, 4, 1, 0b1001);

        SemanticToken token = tokens.getFirst();
        assertEquals(2, token.modifiers().size());
        assertTrue(token.hasModifier("static"));
        assertTrue(token.hasModifier("declaration"));
    }

    @Test
    void skipsTokensWithAnUnknownType() {
        List<SemanticToken> tokens = decode(
                0, 0, 3, 99, 0,
                0, 5, 3, 1, 0);

        assertEquals(1, tokens.size());
        assertEquals("method", tokens.getFirst().type());
    }

    @Test
    void skipsZeroLengthTokens() {
        assertTrue(decode(0, 0, 0, 0, 0).isEmpty());
    }

    @Test
    void toleratesATruncatedPayload() {
        assertEquals(1, decode(0, 0, 3, 0, 0, 0, 4).size());
    }

    @Test
    void emptyOrMissingPayloadYieldsNoTokens() {
        assertTrue(SemanticTokenDecoder.decode(null, TYPES, MODIFIERS).isEmpty());
        assertTrue(decode().isEmpty());
    }

    private static List<SemanticToken> decode(int... data) {
        var array = MAPPER.createArrayNode();
        for (int value : data) {
            array.add(value);
        }
        JsonNode node = array;
        return SemanticTokenDecoder.decode(node, TYPES, MODIFIERS);
    }
}
