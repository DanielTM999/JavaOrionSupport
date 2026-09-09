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

    private static final java.util.Set<String> PROFILE_KEYS = java.util.Set.of(
            "spring.profiles.active", "spring.profiles.include", "spring.profiles.default");

    public static List<AutoCompleteItem> completeValues(SpringConfigIndex index, Path file,
                                                        String content, int line, int column) {
        if (index == null || !isConfigFile(file)) {
            return List.of();
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(file.getFileName().toString());
        String key = SpringConfigDocument.keyAt(content, line, format);
        if (key == null || !PROFILE_KEYS.contains(SpringConfigMetadata.canonical(key))) {
            return List.of();
        }
        if (!afterSeparator(content, line, column, format)) {
            return List.of();
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String profile : index.profiles()) {
            items.add(new AutoCompleteItem(profile, profile, "profile", "", null,
                    AutoCompleteItem.Kind.PROPERTY, List.of()));
        }
        return items;
    }

    private static boolean afterSeparator(String content, int line, int column,
                                          SpringConfigDocument.Format format) {
        String[] lines = content == null ? new String[0] : content.split("\\n", -1);
        if (line < 0 || line >= lines.length) {
            return false;
        }
        String text = lines[line];
        int separator = format == SpringConfigDocument.Format.PROPERTIES
                ? text.indexOf('=') : text.indexOf(':');
        return separator >= 0 && column > separator;
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
                diagnostics.add(diagnostic(key, DiagnosticSeverity.WARNING, message,
                        dtm.ide.inspection.JavaInspection.CONFIG_DEPRECATED_KEY.id()));
                continue;
            }
            if (metadata.fromClasspath() && !metadata.isKnown(key.key())) {
                diagnostics.add(diagnostic(key, DiagnosticSeverity.WARNING,
                        text("diagnostic.unknown", "Propriedade desconhecida no classpath:")
                                + " " + key.key(),
                        dtm.ide.inspection.JavaInspection.CONFIG_UNKNOWN_KEY.id()));
            }
        }
        return diagnostics;
    }

    private static Diagnostic diagnostic(SpringConfigDocument.ConfigKey key,
                                         DiagnosticSeverity severity, String message) {
        return diagnostic(key, severity, message, SOURCE);
    }

    private static Diagnostic diagnostic(SpringConfigDocument.ConfigKey key,
                                         DiagnosticSeverity severity, String message,
                                         String source) {
        return new Diagnostic(key.line(), key.keyStart(), key.line(), key.keyEnd(),
                severity, message, source, null);
    }
}
