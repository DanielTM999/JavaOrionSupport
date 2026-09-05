package dtm.ide;

import dtm.ide.editor.tokenizer.ConfigTokenizerProvider;
import dtm.ide.editor.tokenizer.GradleTokenizerProvider;
import dtm.ide.editor.tokenizer.JavaTokenizerProvider;
import dtm.ide.editor.tokenizer.XmlTokenizerProvider;
import dtm.ide.project.JavaProjectConventions;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class JavaEditorRegistry {

    private final JavaTokenizerProvider java = new JavaTokenizerProvider();
    private final ConfigTokenizerProvider properties =
            new ConfigTokenizerProvider(ConfigTokenizerProvider.Mode.PROPERTIES);
    private final ConfigTokenizerProvider yaml =
            new ConfigTokenizerProvider(ConfigTokenizerProvider.Mode.YAML);
    private final GradleTokenizerProvider gradle = new GradleTokenizerProvider();
    private final XmlTokenizerProvider xml = new XmlTokenizerProvider();

    public TokenizerCodeEditorProvider tokenizerFor(Path filePath) {
        return switch (extensionOf(filePath)) {
            case "java" -> java;
            case "properties" -> properties;
            case "yml", "yaml" -> yaml;
            case "gradle" -> gradle;
            case "kts" -> isGradleScript(filePath) ? gradle : null;
            case "xml" -> JavaProjectConventions.isMavenPom(filePath) ? xml : null;
            default -> null;
        };
    }

    public List<FoldRule> foldRulesFor(Path filePath) {
        return switch (extensionOf(filePath)) {
            case "java" -> List.of(
                    FoldRule.pair('{', '}'),
                    FoldRule.pair('[', ']'),
                    FoldRule.pair("/*", "*/"));
            case "gradle" -> List.of(FoldRule.pair('{', '}'), FoldRule.pair("/*", "*/"));
            case "xml" -> JavaProjectConventions.isMavenPom(filePath)
                    ? List.of(FoldRule.pair("<!--", "-->"))
                    : List.of();
            case "kts" -> isGradleScript(filePath)
                    ? List.of(FoldRule.pair('{', '}'), FoldRule.pair("/*", "*/"))
                    : List.of();
            default -> List.of();
        };
    }

    public boolean handles(Path filePath) {
        return tokenizerFor(filePath) != null;
    }

    private static boolean isGradleScript(Path filePath) {
        if (filePath == null || filePath.getFileName() == null) {
            return false;
        }
        String name = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(JavaProjectConventions.GRADLE_BUILD_KOTLIN)
                || name.equals(JavaProjectConventions.GRADLE_SETTINGS_KOTLIN);
    }

    private static String extensionOf(Path filePath) {
        return filePath == null ? "" : JavaProjectConventions.extensionOf(filePath);
    }
}
