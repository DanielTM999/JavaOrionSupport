package dtm.ide.wizard;

import dtm.ide.project.JavaModule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class JavaFileTemplates {

    public enum Kind {
        CLASS("Classe"),
        INTERFACE("Interface"),
        ENUM("Enum"),
        RECORD("Record"),
        ANNOTATION("Anotacao"),
        SERVICE("@Service"),
        REST_CONTROLLER("@RestController"),
        REPOSITORY("@Repository"),
        CONFIGURATION("@Configuration"),
        TEST("Classe de teste");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public boolean isSpring() {
            return this == SERVICE || this == REST_CONTROLLER
                    || this == REPOSITORY || this == CONFIGURATION;
        }
    }

    private JavaFileTemplates() {
    }

    public static String render(Kind kind, String packageName, String typeName) {
        return render(kind, packageName, typeName, null, List.of());
    }

    public static String render(Kind kind, String packageName, String typeName,
                                String superclass, List<String> interfaces) {
        String name = sanitizeTypeName(typeName);
        String sourcePackage = packageName == null ? "" : packageName.trim();
        StringBuilder source = new StringBuilder();

        if (!sourcePackage.isEmpty()) {
            source.append("package ").append(sourcePackage).append(";\n\n");
        }
        StringBuilder typeImports = new StringBuilder();
        String superName = acceptsSuperclass(kind) && superclass != null && !superclass.isBlank()
                ? importType(typeImports, sourcePackage, superclass.trim()) : "";
        List<String> interfaceNames = new ArrayList<>();
        if (acceptsInterfaces(kind) && interfaces != null) {
            for (String type : new LinkedHashSet<>(interfaces)) {
                if (type != null && !type.isBlank()) {
                    interfaceNames.add(importType(typeImports, sourcePackage, type.trim()));
                }
            }
        }
        String kindImports = importsFor(kind);
        if (typeImports.isEmpty()) {
            source.append(kindImports);
        } else {
            if (!kindImports.isEmpty()) {
                source.append(kindImports, 0, kindImports.length() - 1);
            }
            source.append(typeImports).append('\n');
        }
        source.append(bodyFor(kind, name, clauseFor(kind, superName, interfaceNames)));
        return source.toString();
    }

    public static boolean acceptsSuperclass(Kind kind) {
        return kind == Kind.CLASS || kind == Kind.SERVICE
                || kind == Kind.REST_CONTROLLER || kind == Kind.CONFIGURATION;
    }

    public static boolean acceptsInterfaces(Kind kind) {
        return acceptsSuperclass(kind) || kind == Kind.INTERFACE
                || kind == Kind.ENUM || kind == Kind.RECORD;
    }

    public static int closingBraceLine(String source) {
        String[] lines = source.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith("}")) {
                return i;
            }
        }
        return Math.max(0, lines.length - 1);
    }

    private static String clauseFor(Kind kind, String superName, List<String> interfaceNames) {
        String interfaces = String.join(", ", interfaceNames);
        if (kind == Kind.INTERFACE) {
            return interfaces.isEmpty() ? "" : " extends " + interfaces;
        }
        StringBuilder clause = new StringBuilder();
        if (!superName.isEmpty()) {
            clause.append(" extends ").append(superName);
        }
        if (!interfaces.isEmpty()) {
            clause.append(" implements ").append(interfaces);
        }
        return clause.toString();
    }

    public static String renderRepository(String packageName, String typeName,
                                          String entityType, String idType) {
        String name = sanitizeTypeName(typeName);
        String entity = entityType == null ? "" : entityType.trim();
        String id = idType == null ? "" : idType.trim();
        String sourcePackage = packageName == null ? "" : packageName.trim();
        StringBuilder source = new StringBuilder();
        if (!sourcePackage.isEmpty()) {
            source.append("package ").append(sourcePackage).append(";\n\n");
        }
        source.append("import org.springframework.data.jpa.repository.JpaRepository;\n");
        String entityName = importType(source, sourcePackage, entity);
        String idName = importType(source, sourcePackage, boxedIdType(id));
        source.append("\npublic interface ").append(name)
                .append(" extends JpaRepository<").append(entityName)
                .append(", ").append(idName).append("> {\n}\n");
        return source.toString();
    }

    private static String importType(StringBuilder source, String sourcePackage, String type) {
        int dot = type.lastIndexOf('.');
        if (dot < 0) {
            return type;
        }
        String typePackage = type.substring(0, dot);
        if (!typePackage.equals(sourcePackage) && !typePackage.equals("java.lang")) {
            source.append("import ").append(type).append(";\n");
        }
        return type.substring(dot + 1);
    }

    private static String boxedIdType(String type) {
        return switch (type) {
            case "long" -> "Long";
            case "int" -> "Integer";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "boolean" -> "Boolean";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type;
        };
    }

    private static String importsFor(Kind kind) {
        return switch (kind) {
            case SERVICE -> "import org.springframework.stereotype.Service;\n\n";
            case REPOSITORY -> "import org.springframework.stereotype.Repository;\n\n";
            case CONFIGURATION -> "import org.springframework.context.annotation.Configuration;\n\n";
            case REST_CONTROLLER -> """
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    """;
            case TEST -> """
                    import org.junit.jupiter.api.Test;

                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    """;
            default -> "";
        };
    }

    private static String bodyFor(Kind kind, String name, String clause) {
        return switch (kind) {
            case CLASS -> "public class " + name + clause + " {\n}\n";
            case INTERFACE -> "public interface " + name + clause + " {\n}\n";
            case ENUM -> "public enum " + name + clause + " {\n}\n";
            case RECORD -> "public record " + name + "()" + clause + " {\n}\n";
            case ANNOTATION -> "public @interface " + name + " {\n}\n";
            case SERVICE -> "@Service\npublic class " + name + clause + " {\n}\n";
            case REPOSITORY -> "@Repository\npublic interface " + name + " {\n}\n";
            case CONFIGURATION -> "@Configuration\npublic class " + name + clause + " {\n}\n";
            case REST_CONTROLLER -> "@RestController\n@RequestMapping(\"/" + endpointPath(name) + "\")\n"
                    + "public class " + name + clause + " {\n\n"
                    + "    @GetMapping\n"
                    + "    public String listar() {\n"
                    + "        return \"\";\n"
                    + "    }\n}\n";
            case TEST -> "class " + name + " {\n\n"
                    + "    @Test\n"
                    + "    void deveFazerAlgo() {\n"
                    + "        assertEquals(1, 1);\n"
                    + "    }\n}\n";
        };
    }

    public static String packageOf(Path directory, JavaModule module) {
        if (directory == null || module == null) {
            return "";
        }
        Path normalized = directory.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>(module.sourceRoots());
        roots.addAll(module.testRoots());

        for (Path sourceRoot : roots) {
            Path root = sourceRoot.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) {
                continue;
            }
            String relative = root.relativize(normalized).toString();
            if (relative.isBlank()) {
                return "";
            }
            return relative.replace('\\', '.').replace('/', '.');
        }
        return "";
    }

    public static String fileNameOf(String typeName) {
        return sanitizeTypeName(typeName) + ".java";
    }

    static String sanitizeTypeName(String typeName) {
        String name = typeName == null ? "" : typeName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            name = name.substring(lastDot + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9_$]", "");
        if (name.isBlank()) {
            return "SemNome";
        }
        if (Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String endpointPath(String typeName) {
        String base = typeName;
        for (String suffix : new String[]{"RestController", "Controller"}) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        String lower = base.toLowerCase(Locale.ROOT);
        return lower.endsWith("s") ? lower : lower + "s";
    }
}
