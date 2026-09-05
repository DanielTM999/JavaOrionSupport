package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SemanticTokenDecoder {

    private static final int FIELDS_PER_TOKEN = 5;

    private SemanticTokenDecoder() {
    }

    static List<SemanticToken> decode(JsonNode data, List<String> tokenTypes,
                                      List<String> tokenModifiers) {
        if (data == null || !data.isArray() || data.size() < FIELDS_PER_TOKEN) {
            return List.of();
        }
        List<SemanticToken> tokens = new ArrayList<>(data.size() / FIELDS_PER_TOKEN);

        int line = 0;
        int startChar = 0;
        for (int i = 0; i + FIELDS_PER_TOKEN - 1 < data.size(); i += FIELDS_PER_TOKEN) {
            int deltaLine = data.get(i).asInt(0);
            int deltaStart = data.get(i + 1).asInt(0);
            int length = data.get(i + 2).asInt(0);
            int typeIndex = data.get(i + 3).asInt(-1);
            int modifierBits = data.get(i + 4).asInt(0);

            line += deltaLine;
            startChar = deltaLine == 0 ? startChar + deltaStart : deltaStart;

            if (length <= 0 || typeIndex < 0 || typeIndex >= tokenTypes.size()) {
                continue;
            }
            Range range = new Range(
                    new Position(line, startChar),
                    new Position(line, startChar + length));
            tokens.add(new SemanticToken(range, tokenTypes.get(typeIndex),
                    decodeModifiers(modifierBits, tokenModifiers)));
        }
        return tokens;
    }

    private static Set<String> decodeModifiers(int bits, List<String> tokenModifiers) {
        if (bits == 0) {
            return Set.of();
        }
        Set<String> modifiers = new LinkedHashSet<>();
        for (int index = 0; index < tokenModifiers.size() && index < 32; index++) {
            if ((bits & (1 << index)) != 0) {
                modifiers.add(tokenModifiers.get(index));
            }
        }
        return Set.copyOf(modifiers);
    }
}
