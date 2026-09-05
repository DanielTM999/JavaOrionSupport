package dtm.ide.wizard;

import dtm.ide.project.JavaModule;

import java.nio.file.Path;
import java.util.ArrayList;
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
        String name = sanitizeTypeName(typeName);
        StringBuilder source = new StringBuilder();

        if (packageName != null && !packageName.isBlank()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append(importsFor(kind));
        source.append(bodyFor(kind, name));
        return source.toString();
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

    private static String bodyFor(Kind kind, String name) {
        return switch (kind) {
            case CLASS -> "public class " + name + " {\n}\n";
            case INTERFACE -> "public interface " + name + " {\n}\n";
            case ENUM -> "public enum " + name + " {\n}\n";
            case RECORD -> "public record " + name + "() {\n}\n";
            case ANNOTATION -> "public @interface " + name + " {\n}\n";
            case SERVICE -> "@Service\npublic class " + name + " {\n}\n";
            case REPOSITORY -> "@Repository\npublic interface " + name + " {\n}\n";
            case CONFIGURATION -> "@Configuration\npublic class " + name + " {\n}\n";
            case REST_CONTROLLER -> "@RestController\n@RequestMapping(\"/" + endpointPath(name) + "\")\n"
                    + "public class " + name + " {\n\n"
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
