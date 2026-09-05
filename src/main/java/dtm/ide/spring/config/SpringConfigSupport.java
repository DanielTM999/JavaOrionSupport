package dtm.ide.spring.config;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class SpringConfigSupport {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(SpringConfigSupport.class, key, fallback);
    }

    private static final String SOURCE = "spring-config";

    private static final int MAX_SUGGESTIONS = 60;

    private SpringConfigSupport() {
    }

    public static boolean isConfigFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean rightExtension = name.endsWith(".properties") || name.endsWith(".yml")
                || name.endsWith(".yaml");
        if (!rightExtension) {
            return false;
        }
        return name.startsWith("application") || name.startsWith("bootstrap");
    }

    public static List<AutoCompleteItem> complete(SpringConfigMetadata metadata, Path file,
                                                  String content, int line, int column) {
        if (metadata == null || !isConfigFile(file)) {
            return List.of();
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(file.getFileName().toString());
        Optional<String> keyPrefix =
                SpringConfigDocument.keyPrefixAt(content, line, column, format);
        if (keyPrefix.isEmpty()) {
            return List.of();
        }
        String prefix = keyPrefix.get();
        if (prefix.isBlank() && format == SpringConfigDocument.Format.YAML) {
            return List.of();
        }
        String parentPath = parentOf(prefix, format);

        List<AutoCompleteItem> items = new ArrayList<>();
        for (SpringConfigProperty property : metadata.startingWith(prefix)) {
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
            items.add(new AutoCompleteItem(
                    insertTextFor(property, parentPath, format),
                    property.name(),
                    property.simpleType(),
                    property.documentation(),
                    null,
                    property.deprecated()
                            ? AutoCompleteItem.Kind.EVENT
                            : AutoCompleteItem.Kind.PROPERTY,
                    List.of()));
        }
        return items;
    }

    private static String insertTextFor(SpringConfigProperty property, String parentPath,
                                        SpringConfigDocument.Format format) {
        if (format == SpringConfigDocument.Format.PROPERTIES) {
            return property.name() + "=";
        }
        String remainder = property.name();
        if (!parentPath.isBlank() && remainder.startsWith(parentPath + ".")) {
            remainder = remainder.substring(parentPath.length() + 1);
        }
        return remainder + ": ";
    }

    private static String parentOf(String prefix, SpringConfigDocument.Format format) {
        if (format == SpringConfigDocument.Format.PROPERTIES) {
            return "";
        }
        int lastDot = prefix.lastIndexOf('.');
        return lastDot < 0 ? "" : prefix.substring(0, lastDot);
    }

    public static HoverInfo hover(SpringConfigMetadata metadata, Path file, String content, int line) {
        if (metadata == null || !isConfigFile(file)) {
            return null;
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(file.getFileName().toString());
        String key = SpringConfigDocument.keyAt(content, line, format);
        if (key.isBlank()) {
            return null;
        }
        return metadata.find(key)
                .map(property -> HoverInfo.markdown(property.documentation()))
                .orElse(null);
    }

    public static List<Diagnostic> validate(SpringConfigMetadata metadata, Path file, String content) {
        if (metadata == null || !isConfigFile(file)) {
            return List.of();
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(file.getFileName().toString());
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (SpringConfigDocument.ConfigKey key : SpringConfigDocument.keys(content, format)) {
            if (format == SpringConfigDocument.Format.YAML && !key.hasValue()) {
                continue;
            }
            Optional<SpringConfigProperty> property = metadata.find(key.key());
            if (property.isPresent() && property.get().deprecated()) {
                SpringConfigProperty deprecated = property.get();
                String message = text("diagnostic.deprecated", "Propriedade descontinuada")
                        + (deprecated.replacement().isBlank() ? "."
                        : ": " + text("diagnostic.useInstead", "use")
                          + " " + deprecated.replacement());
                diagnostics.add(diagnostic(key, DiagnosticSeverity.WARNING, message));
                continue;
            }
            if (metadata.fromClasspath() && !metadata.isKnown(key.key())) {
                diagnostics.add(diagnostic(key, DiagnosticSeverity.WARNING,
                        text("diagnostic.unknown", "Propriedade desconhecida no classpath:")
                                + " " + key.key()));
            }
        }
        return diagnostics;
    }

    private static Diagnostic diagnostic(SpringConfigDocument.ConfigKey key,
                                         DiagnosticSeverity severity, String message) {
        return new Diagnostic(key.line(), key.keyStart(), key.line(), key.keyEnd(),
                severity, message, SOURCE, null);
    }
}
