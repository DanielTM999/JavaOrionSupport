package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalTokenizerProviderTest {

    @Test
    void javaEditMatchesAFullTokenizationAndReusesTheUntouchedPrefix() {
        JavaTokenizerProvider tokenizer = new JavaTokenizerProvider();
        String oldText = "package demo;\n\nclass Demo {\n    int value = 1;\n}\n";
        int offset = oldText.indexOf("1;");

        assertIncrementalEdit(tokenizer, oldText, offset, 1, "42", true);
    }

    @Test
    void javaEditInAMultilineTokenRestartsAtThatTokensOpeningDelimiter() {
        JavaTokenizerProvider tokenizer = new JavaTokenizerProvider();
        String oldText = "package demo;\n/* comment\ncontinued */\nint value = 1;\n";
        int offset = oldText.indexOf("*/") + 1;

        assertIncrementalEdit(tokenizer, oldText, offset, 1, "", true);
    }

    @Test
    void gradleEditMatchesAFullTokenization() {
        GradleTokenizerProvider tokenizer = new GradleTokenizerProvider();
        String oldText = "plugins {\n    id(\"java\")\n}\n\ndependencies {\n"
                + "    implementation(\"org.example:demo:1.0\")\n}\n";
        int offset = oldText.indexOf("1.0");

        assertIncrementalEdit(tokenizer, oldText, offset, 3, "2.0", true);
    }

    @Test
    void configEditMatchesAFullTokenization() {
        ConfigTokenizerProvider tokenizer =
                new ConfigTokenizerProvider(ConfigTokenizerProvider.Mode.YAML);
        String oldText = "spring:\n  application:\n    name: demo\n  profiles:\n    active: dev\n";
        int offset = oldText.indexOf("dev");

        assertIncrementalEdit(tokenizer, oldText, offset, 3, "prod", true);
    }

    @Test
    void inconsistentChangeFallsBackToAFullTokenization() {
        JavaTokenizerProvider tokenizer = new JavaTokenizerProvider();
        String oldText = "class Old {}";
        String newText = "class New {}";
        List<Token> previous = List.copyOf(tokenizer.tokenize(oldText, null));
        TokenizeChange inconsistent = new TokenizeChange(
                oldText, newText, 0, 0, "wrong", previous);

        List<Token> actual = List.copyOf(tokenizer.tokenize(inconsistent, null));

        assertTokensEqual(tokenizer.tokenize(newText, null), actual);
    }

    private static void assertIncrementalEdit(
            dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider tokenizer,
            String oldText,
            int offset,
            int removedLength,
            String insertedText,
            boolean expectReusedPrefix) {
        List<Token> previous = List.copyOf(tokenizer.tokenize(oldText, null));
        String newText = oldText.substring(0, offset) + insertedText
                + oldText.substring(offset + removedLength);
        TokenizeChange change = new TokenizeChange(
                oldText, newText, offset, removedLength, insertedText, previous);

        List<Token> incremental = List.copyOf(tokenizer.tokenize(change, null));
        List<Token> full = List.copyOf(tokenizer.tokenize(newText, null));

        assertTrue(tokenizer.supportsIncremental());
        assertTokensEqual(full, incremental);
        if (expectReusedPrefix) {
            assertSame(previous.getFirst(), incremental.getFirst(),
                    "tokens anteriores a janela editada devem ser reaproveitados");
        }
    }

    private static void assertTokensEqual(Collection<Token> expected, Collection<Token> actual) {
        List<Token> expectedList = List.copyOf(expected);
        List<Token> actualList = List.copyOf(actual);
        assertEquals(expectedList.size(), actualList.size(), "quantidade de tokens");
        for (int i = 0; i < expectedList.size(); i++) {
            Token expectedToken = expectedList.get(i);
            Token actualToken = actualList.get(i);
            assertEquals(expectedToken.getStartOffset(), actualToken.getStartOffset(), "inicio " + i);
            assertEquals(expectedToken.getEndOffset(), actualToken.getEndOffset(), "fim " + i);
            assertEquals(expectedToken.getType(), actualToken.getType(), "tipo " + i);
            assertEquals(expectedToken.getText(), actualToken.getText(), "texto " + i);
        }
    }
}
