package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizeChange;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class GradleTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_DSL_BLOCK = "GRADLE_DSL_BLOCK";
    public static final String TOKEN_CONFIGURATION = "GRADLE_CONFIGURATION";
    public static final String TOKEN_INTERPOLATION = "GRADLE_INTERPOLATION";

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "return", "try", "catch", "finally", "throw",
            "new", "true", "false", "null", "this", "super", "in", "is", "as",
            "def", "it", "assert", "class", "extends", "implements", "import", "package",
            "val", "var", "fun", "object", "when", "by", "typealias", "internal", "private",
            "public", "protected", "operator", "inline", "lateinit", "companion"
    );

    private static final Set<String> DSL_BLOCKS = Set.of(
            "plugins", "dependencies", "repositories", "subprojects", "allprojects",
            "buildscript", "java", "kotlin", "application", "tasks", "sourceSets",
            "publishing", "test", "configurations", "dependencyManagement", "springBoot",
            "toolchain", "jar", "bootJar", "ext", "extra", "settings", "pluginManagement"
    );

    private static final Set<String> CONFIGURATIONS = Set.of(
            "implementation", "api", "compileOnly", "runtimeOnly", "testImplementation",
            "testCompileOnly", "testRuntimeOnly", "annotationProcessor",
            "testAnnotationProcessor", "developmentOnly", "compileClasspath",
            "runtimeClasspath", "classpath", "platform", "enforcedPlatform"
    );

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public Collection<Token> tokenize(TokenizeChange change,
                                      TokenClassifierCodeEditorProvider classifier) {
        return IncrementalTokenization.retokenizeFromSafeLine(change, classifier, this::tokenize);
    }

    @Override
    public Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String source = text == null ? "" : text;
        List<Token> tokens = new ArrayList<>(Math.max(16, source.length() / 4));

        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);

            if (c == '\r' || c == '\n') {
                int start = i++;
                if (c == '\r' && i < source.length() && source.charAt(i) == '\n') {
                    i++;
                }
                tokens.add(token(source, start, i, TokenType.NEWLINE));
            } else if (Character.isWhitespace(c)) {
                int start = i++;
                while (i < source.length() && isInlineWhitespace(source.charAt(i))) {
                    i++;
                }
                tokens.add(token(source, start, i, TokenType.WHITESPACE));
            } else if (source.startsWith("//", i)) {
                int start = i;
                i = lineEnd(source, i);
                tokens.add(token(source, start, i, TokenType.COMMENT));
            } else if (source.startsWith("/*", i)) {
                int start = i;
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                tokens.add(token(source, start, i, TokenType.COMMENT));
            } else if (source.startsWith("\"\"\"", i) || source.startsWith("'''", i)) {
                int start = i;
                String fence = source.substring(i, i + 3);
                int close = source.indexOf(fence, i + 3);
                i = close < 0 ? source.length() : close + 3;
                emitStringWithInterpolation(source, start, i, tokens);
            } else if (c == '"' || c == '\'') {
                int start = i;
                i = stringEnd(source, i, c);
                emitStringWithInterpolation(source, start, i, tokens);
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < source.length()
                        && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.'
                            || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(token(source, start, i, TokenType.NUMBER));
            } else if (isIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && isIdentifierPart(source.charAt(i))) {
                    i++;
                }
                String word = source.substring(start, i);
                tokens.add(token(source, start, i, classifyWord(word)));
            } else {
                tokens.add(token(source, i, i + 1, TokenType.SYMBOL));
                i++;
            }
        }
        return tokens;
    }

    private static String classifyWord(String word) {
        if (KEYWORDS.contains(word)) {
            return TokenType.KEYWORD;
        }
        if (DSL_BLOCKS.contains(word)) {
            return TOKEN_DSL_BLOCK;
        }
        if (CONFIGURATIONS.contains(word)) {
            return TOKEN_CONFIGURATION;
        }
        return TokenType.IDENTIFIER;
    }

    private static void emitStringWithInterpolation(String source, int from, int to, List<Token> tokens) {
        int i = from;
        int plainStart = from;
        while (i < to) {
            if (!source.startsWith("${", i)) {
                i++;
                continue;
            }
            if (plainStart < i) {
                tokens.add(token(source, plainStart, i, TokenType.STRING));
            }
            int close = source.indexOf('}', i + 2);
            int end = close < 0 || close >= to ? to : close + 1;
            tokens.add(token(source, i, end, TOKEN_INTERPOLATION));
            i = end;
            plainStart = end;
        }
        if (plainStart < to) {
            tokens.add(token(source, plainStart, to, TokenType.STRING));
        }
    }

    private static int stringEnd(String source, int at, char quote) {
        int i = at + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int lineEnd(String value, int at) {
        int end = value.indexOf('\n', at);
        return end < 0 ? value.length() : end;
    }

    private static boolean isInlineWhitespace(char c) {
        return Character.isWhitespace(c) && c != '\r' && c != '\n';
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isJavaIdentifierPart(c);
    }

    private static Token token(String source, int start, int end, String type) {
        return new Token(start, end, type, source.substring(start, end));
    }
}
