package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTokenizerProviderTest {

    private final JavaTokenizerProvider tokenizer = new JavaTokenizerProvider();

    @Test
    void tokensCoverTheWholeSourceWithoutGapsOrOverlaps() {
        String source = """
                package com.example;

                /** Doc. */
                @Service
                public class Demo {
                    private static final int LIMIT = 1_000;

                    public String greet(String name) {
                        return "Ola, " + name; // saudacao
                    }
                }
                """;

        List<Token> tokens = List.copyOf(tokenizer.tokenize(source, null));

        int cursor = 0;
        for (Token token : tokens) {
            assertEquals(cursor, token.getStartOffset(), "token comeca onde o anterior terminou");
            assertTrue(token.getEndOffset() > token.getStartOffset(), "token vazio: " + token);
            cursor = token.getEndOffset();
        }
        assertEquals(source.length(), cursor, "os tokens devem cobrir o arquivo inteiro");
    }

    @Test
    void emptySourceYieldsNoTokens() {
        assertTrue(tokenizer.tokenize("", null).isEmpty());
        assertTrue(tokenizer.tokenize((String) null, null).isEmpty());
    }

    @Test
    void separatesJavadocFromOrdinaryComments() {
        List<Token> tokens = tokens("/** doc */ /* bloco */ // linha");

        assertEquals(JavaTokenizerProvider.TOKEN_JAVADOC, typeOfText(tokens, "/** doc */"));
        assertEquals(TokenType.COMMENT, typeOfText(tokens, "/* bloco */"));
        assertEquals(TokenType.COMMENT, typeOfText(tokens, "// linha"));
    }

    @Test
    void emptyBlockCommentIsNotJavadoc() {
        assertEquals(TokenType.COMMENT, typeOfText(tokens("/**/"), "/**/"));
    }

    @Test
    void unterminatedBlockCommentStopsAtEndOfFile() {
        List<Token> tokens = tokens("/* aberto");

        assertEquals(1, tokens.size());
        assertEquals(TokenType.COMMENT, tokens.getFirst().getType());
    }

    @Test
    void recognizesTextBlocks() {
        String source = "String sql = \"\"\"\n    select 1\n    \"\"\";";

        assertTrue(hasType(tokens(source), JavaTokenizerProvider.TOKEN_TEXT_BLOCK));
    }

    @Test
    void handlesEscapedQuotesInsideStrings() {
        List<Token> tokens = tokens("String s = \"a\\\"b\";");

        assertEquals("\"a\\\"b\"", textOfType(tokens, TokenType.STRING));
    }

    @Test
    void unterminatedStringStopsAtLineBreak() {
        List<Token> tokens = tokens("String s = \"aberta\nint x = 1;");

        assertEquals("\"aberta", textOfType(tokens, TokenType.STRING),
                "uma aspa solta nao pode pintar o resto do arquivo");
    }

    @Test
    void charLiteralsAreStrings() {
        assertEquals("'a'", textOfType(tokens("char c = 'a';"), TokenType.STRING));
    }

    @Test
    void recognizesNumberLiteralForms() {
        assertEquals("1_000_000L", firstNumber("long v = 1_000_000L;"));
        assertEquals("0xFF", firstNumber("int v = 0xFF;"));
        assertEquals("0b1010", firstNumber("int v = 0b1010;"));
        assertEquals("3.14f", firstNumber("float v = 3.14f;"));
        assertEquals("1e-9", firstNumber("double v = 1e-9;"));
        assertEquals("42", firstNumber("int v = 42;"));
    }

    @Test
    void aNumberSuffixDoesNotSwallowTheNextToken() {
        List<Token> tokens = tokens("long v = 10L + x;");

        assertEquals("10L", firstOfType(tokens, TokenType.NUMBER).getText());
        assertTrue(hasText(tokens, "x"));
    }

    @Test
    void annotationsAreASingleToken() {
        assertEquals("@RestController",
                textOfType(tokens("@RestController\nclass A {}"),
                        JavaTokenizerProvider.TOKEN_ANNOTATION));
    }

    @Test
    void qualifiedAnnotationsStayTogether() {
        assertEquals("@jakarta.inject.Inject",
                textOfType(tokens("@jakarta.inject.Inject private A a;"),
                        JavaTokenizerProvider.TOKEN_ANNOTATION));
    }

    @Test
    void annotationDoesNotSwallowATrailingDot() {
        assertEquals("@Value", textOfType(tokens("@Value. "),
                JavaTokenizerProvider.TOKEN_ANNOTATION));
    }

    @Test
    void loneAtSignIsJustASymbol() {
        assertFalse(hasType(tokens("int a = b @ c;"), JavaTokenizerProvider.TOKEN_ANNOTATION));
    }

    @Test
    void keywordsAndLiteralsAreKeywords() {
        List<Token> tokens = tokens("public static final boolean OK = true;");

        assertEquals(TokenType.KEYWORD, typeOfText(tokens, "public"));
        assertEquals(TokenType.KEYWORD, typeOfText(tokens, "boolean"));
        assertEquals(TokenType.KEYWORD, typeOfText(tokens, "true"));
    }

    @Test
    void modernContextualKeywordsAreHighlighted() {
        List<Token> tokens = tokens("sealed interface Shape permits Circle {}");

        assertEquals(TokenType.KEYWORD, typeOfText(tokens, "sealed"));
        assertEquals(TokenType.KEYWORD, typeOfText(tokens, "permits"));
    }

    @Test
    void callSitesAreMethods() {
        List<Token> tokens = tokens("value.trim();");

        assertEquals(JavaTokenizerProvider.TOKEN_METHOD, typeOfText(tokens, "trim"));
        assertEquals(TokenType.IDENTIFIER, typeOfText(tokens, "value"));
    }

    @Test
    void capitalizedNamesAreTypes() {
        List<Token> tokens = tokens("UserService service;");

        assertEquals(JavaTokenizerProvider.TOKEN_TYPE, typeOfText(tokens, "UserService"));
        assertEquals(TokenType.IDENTIFIER, typeOfText(tokens, "service"));
    }

    @Test
    void screamingCaseIsAConstantNotAType() {
        assertEquals(TokenType.IDENTIFIER, typeOfText(tokens("int MAX = 1;"), "MAX"));
    }

    @Test
    void declaredTypeNamesAreTypesEvenInScreamingCase() {
        assertEquals(JavaTokenizerProvider.TOKEN_TYPE, typeOfText(tokens("class HTTP {}"), "HTTP"));
    }

    @Test
    void javaLangTypesAreRecognizedWithoutImport() {
        assertEquals(JavaTokenizerProvider.TOKEN_TYPE, typeOfText(tokens("String s;"), "String"));
    }

    private List<Token> tokens(String source) {
        return List.copyOf(tokenizer.tokenize(source, null));
    }

    private String firstNumber(String source) {
        return firstOfType(tokens(source), TokenType.NUMBER).getText();
    }

    private static Token firstOfType(List<Token> tokens, String type) {
        return tokens.stream()
                .filter(token -> token.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum token do tipo " + type));
    }

    private static String textOfType(List<Token> tokens, String type) {
        return firstOfType(tokens, type).getText();
    }

    private static String typeOfText(List<Token> tokens, String text) {
        return tokens.stream()
                .filter(token -> token.getText().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum token com o texto " + text))
                .getType();
    }

    private static boolean hasType(List<Token> tokens, String type) {
        return tokens.stream().anyMatch(token -> token.getType().equals(type));
    }

    private static boolean hasText(List<Token> tokens, String text) {
        return tokens.stream().anyMatch(token -> token.getText().equals(text));
    }
}
