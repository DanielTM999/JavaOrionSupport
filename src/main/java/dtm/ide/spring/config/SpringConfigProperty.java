package dtm.ide.spring.config;

import java.util.List;

public record SpringConfigProperty(
        String name,
        String type,
        String description,
        String defaultValue,
        String sourceType,
        boolean deprecated,
        String replacement,
        List<String> values
) {

    public SpringConfigProperty {
        name = name == null ? "" : name.trim();
        type = type == null ? "" : type.trim();
        description = description == null ? "" : description.trim();
        defaultValue = defaultValue == null ? "" : defaultValue.trim();
        sourceType = sourceType == null ? "" : sourceType.trim();
        replacement = replacement == null ? "" : replacement.trim();
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static SpringConfigProperty of(String name, String type, String description) {
        return new SpringConfigProperty(name, type, description, "", "", false, "", List.of());
    }

    public boolean hasDefault() {
        return !defaultValue.isBlank();
    }

    public String simpleType() {
        if (type.isBlank()) {
            return "";
        }
        String base = type;
        int generics = base.indexOf('<');
        String suffix = "";
        if (generics > 0) {
            suffix = "<...>";
            base = base.substring(0, generics);
        }
        int lastDot = base.lastIndexOf('.');
        return (lastDot >= 0 ? base.substring(lastDot + 1) : base) + suffix;
    }

    public String documentation() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("**").append(name).append("**");
        if (!type.isBlank()) {
            markdown.append("  \n`").append(type).append('`');
        }
        if (deprecated) {
            markdown.append("  \n\n_Descontinuada");
            if (!replacement.isBlank()) {
                markdown.append("; use `").append(replacement).append('`');
            }
            markdown.append("._");
        }
        if (!description.isBlank()) {
            markdown.append("\n\n").append(description);
        }
        if (hasDefault()) {
            markdown.append("\n\nPadrao: `").append(defaultValue).append('`');
        }
        return markdown.toString();
    }
}
