package dtm.ide.editor.theme;

import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.theme.EditorThemeConfig;
import dtm.ide.editor.tokenizer.ConfigTokenizerProvider;
import dtm.ide.editor.tokenizer.GradleTokenizerProvider;
import dtm.ide.editor.tokenizer.JavaTokenizerProvider;
import dtm.ide.editor.tokenizer.JpaQueryTokenizer;
import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class JavaEditorTheme implements EditorTheme {

    private static final Set<String> FILE_TYPES = Set.of(
            "java", "properties", "yml", "yaml", "gradle", "kts", "jsp");

    private static final Color KEYWORD = new Color(86, 156, 214);
    private static final Color STRING = new Color(206, 145, 120);
    private static final Color NUMBER = new Color(181, 206, 168);
    private static final Color COMMENT = new Color(106, 153, 85);
    private static final Color JAVADOC = new Color(96, 139, 138);
    private static final Color SYMBOL = new Color(212, 212, 212);
    private static final Color TYPE = new Color(78, 201, 176);
    private static final Color METHOD = new Color(220, 220, 170);
    private static final Color ANNOTATION = new Color(197, 134, 192);
    private static final Color CONFIG_KEY = new Color(156, 220, 254);
    private static final Color PLACEHOLDER = new Color(215, 186, 125);
    private static final Color MUTED = new Color(155, 155, 155);

    private final Supplier<EditorThemeConfig> fallback;

    public JavaEditorTheme() {
        this(() -> null);
    }

    public JavaEditorTheme(Supplier<EditorThemeConfig> fallback) {
        this.fallback = fallback == null ? () -> null : fallback;
    }

    @Override
    public EditorThemeConfig getConfigByFileType(String fileType) {
        if (fileType == null) {
            return this;
        }
        String normalized = fileType.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return FILE_TYPES.contains(normalized) ? this : null;
    }

    @Override
    public Color getColorByToken(Token token) {
        return token == null ? null : getColorByToken(token.getType());
    }

    @Override
    public Color getColorByToken(String tokenType) {
        if (tokenType == null) {
            return null;
        }
        Color specific = specificColor(tokenType);
        if (specific != null) {
            return specific;
        }
        Color themed = fromCurrentTheme(tokenType);
        return themed != null ? themed : genericColor(tokenType);
    }

    private static Color specificColor(String tokenType) {
        return switch (tokenType) {
            case JavaTokenizerProvider.TOKEN_ANNOTATION -> ANNOTATION;
            case JavaTokenizerProvider.TOKEN_TYPE -> TYPE;
            case JavaTokenizerProvider.TOKEN_METHOD -> METHOD;
            case JavaTokenizerProvider.TOKEN_JAVADOC -> JAVADOC;
            case JavaTokenizerProvider.TOKEN_TEXT_BLOCK -> STRING;
            case JpaQueryTokenizer.TOKEN_KEYWORD -> KEYWORD;
            case JpaQueryTokenizer.TOKEN_ENTITY -> TYPE;
            case JpaQueryTokenizer.TOKEN_ALIAS -> ANNOTATION;
            case JpaQueryTokenizer.TOKEN_PROPERTY -> CONFIG_KEY;
            case JpaQueryTokenizer.TOKEN_FUNCTION -> METHOD;
            case JpaQueryTokenizer.TOKEN_PARAMETER -> PLACEHOLDER;
            case ConfigTokenizerProvider.TOKEN_KEY -> CONFIG_KEY;
            case ConfigTokenizerProvider.TOKEN_PLACEHOLDER -> PLACEHOLDER;
            case ConfigTokenizerProvider.TOKEN_DOCUMENT_MARKER -> MUTED;
            case GradleTokenizerProvider.TOKEN_DSL_BLOCK -> METHOD;
            case GradleTokenizerProvider.TOKEN_CONFIGURATION -> ANNOTATION;
            case GradleTokenizerProvider.TOKEN_INTERPOLATION -> PLACEHOLDER;
            default -> null;
        };
    }

    private Color fromCurrentTheme(String tokenType) {
        try {
            EditorThemeConfig current = fallback.get();
            return current == null ? null : current.getColorByToken(tokenType);
        } catch (Exception e) {
            return null;
        }
    }

    private static Color genericColor(String tokenType) {
        return switch (tokenType) {
            case TokenType.KEYWORD -> KEYWORD;
            case TokenType.STRING -> STRING;
            case TokenType.NUMBER -> NUMBER;
            case TokenType.COMMENT -> COMMENT;
            case TokenType.SYMBOL -> SYMBOL;
            default -> null;
        };
    }
}
