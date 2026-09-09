package dtm.ide.spring;

import java.nio.file.Path;
import java.util.List;

public record JavaType(
        String qualifiedName,
        String simpleName,
        String packageName,
        List<String> supertypes,
        List<String> imports,
        Kind kind,
        List<String> annotations,
        Path file,
        int line
) {

    public enum Kind {
        CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        ANNOTATION;

        public static Kind of(String keyword) {
            if (keyword == null) {
                return CLASS;
            }
            return switch (keyword.trim()) {
                case "interface" -> INTERFACE;
                case "enum" -> ENUM;
                case "record" -> RECORD;
                default -> CLASS;
            };
        }
    }

    public JavaType {
        qualifiedName = qualifiedName == null ? "" : qualifiedName.trim();
        simpleName = simpleName == null ? "" : simpleName.trim();
        packageName = packageName == null ? "" : packageName.trim();
        supertypes = supertypes == null ? List.of() : List.copyOf(supertypes);
        imports = imports == null ? List.of() : List.copyOf(imports);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        kind = kind == null ? Kind.CLASS : kind;
        line = Math.max(1, line);
    }

    public boolean isAnnotation() {
        return kind == Kind.ANNOTATION;
    }

    public boolean annotatedWith(String simpleName) {
        return annotations.contains(simpleName);
    }
}
