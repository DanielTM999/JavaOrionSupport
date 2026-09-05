package dtm.ide.run;

import dtm.ide.editor.JavaSourceText;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainClassScanner {

    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern MAIN_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:public\\s+static|static\\s+public)\\s+void\\s+main\\s*\\("
                    + "\\s*(?:final\\s+)?String\\s*(?:\\[\\s*\\]\\s*\\w+|\\w+\\s*\\[\\s*\\]|\\.\\.\\.\\s*\\w+)\\s*\\)");

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)"
                    + "\\s+)*(?:class|record|enum)\\s+[A-Za-z_$][\\w$]*\\b");

    private static final Pattern SPRING_BOOT_APPLICATION =
            Pattern.compile("(?m)^[ \\t]*@SpringBootApplication\\b");

    private static final int MAX_FILES = 20_000;

    public record MainClass(String qualifiedName, Path file, JavaModule module, boolean springBoot,
                            boolean test)
            implements Comparable<MainClass> {

        public String simpleName() {
            int lastDot = qualifiedName.lastIndexOf('.');
            return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
        }

        public String display() {
            if (springBoot) {
                return simpleName() + "  (Spring Boot)";
            }
            return test ? simpleName() + "  (test)" : simpleName();
        }

        @Override
        public int compareTo(MainClass other) {
            if (springBoot != other.springBoot) {
                return springBoot ? -1 : 1;
            }
            if (test != other.test) {
                return test ? 1 : -1;
            }
            return qualifiedName.compareTo(other.qualifiedName);
        }
    }

    private MainClassScanner() {
    }

    public static List<MainClass> scan(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        List<MainClass> found = new ArrayList<>();
        int visited = 0;

        for (JavaModule module : descriptor.modules()) {
            visited = scanRoots(module, module.existingSourceRoots(), false, visited, found);
            if (visited > MAX_FILES) {
                return sorted(found);
            }
            visited = scanRoots(module, module.existingTestRoots(), true, visited, found);
            if (visited > MAX_FILES) {
                return sorted(found);
            }
        }
        return sorted(found);
    }

    private static int scanRoots(JavaModule module, List<Path> roots, boolean test,
                                 int visited, List<MainClass> found) {
        for (Path root : roots) {
            int remaining = MAX_FILES - visited;
            if (remaining <= 0) {
                return MAX_FILES + 1;
            }
            for (Path file : JavaProjectConventions.javaSources(root, 0, remaining)) {
                visited++;
                inspect(file, JavaProjectConventions.readOrEmpty(file), module, test)
                        .ifPresent(found::add);
            }
        }
        return visited;
    }

    public static java.util.Optional<MainClass> inspect(Path file, JavaModule module) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return java.util.Optional.empty();
        }
        return inspect(file, JavaProjectConventions.readOrEmpty(file), module);
    }

    public static java.util.Optional<MainClass> inspect(Path file, String source, JavaModule module) {
        return inspect(file, source, module, isUnderTestRoot(file, module));
    }

    public static boolean isUnderTestRoot(Path file, JavaModule module) {
        if (file == null || module == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        return module.existingTestRoots().stream()
                .map(root -> root.toAbsolutePath().normalize())
                .anyMatch(normalized::startsWith);
    }

    public static java.util.Optional<MainClass> inspect(Path file, String source, JavaModule module,
                                                        boolean test) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return java.util.Optional.empty();
        }
        if (source == null || source.isBlank()) {
            return java.util.Optional.empty();
        }
        String code = JavaSourceText.blankComments(source);
        boolean springBoot = SPRING_BOOT_APPLICATION.matcher(code).find();
        boolean hasMain = MAIN_METHOD.matcher(code).find();

        if (!springBoot && !hasMain) {
            return java.util.Optional.empty();
        }
        String typeName = typeNameOf(file);
        String packageName = packageOf(code);
        String qualifiedName = packageName.isBlank() ? typeName : packageName + "." + typeName;

        return java.util.Optional.of(new MainClass(qualifiedName, file, module, springBoot, test));
    }

    public static int mainMethodLine(String source) {
        if (source == null || source.isBlank()) {
            return -1;
        }
        String code = JavaSourceText.blankComments(source);
        Matcher matcher = MAIN_METHOD.matcher(code);
        if (!matcher.find()) {
            return -1;
        }
        int line = 0;
        for (int index = 0; index < matcher.start(); index++) {
            if (code.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    public record MainLensAnchor(int line, int col, boolean inline) {
    }

    public static MainLensAnchor mainLensAnchor(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String code = JavaSourceText.blankComments(source);
        Matcher main = MAIN_METHOD.matcher(code);
        if (!main.find()) {
            return null;
        }
        Matcher type = TYPE_DECLARATION.matcher(code);
        int declarationStart = -1;
        while (type.find() && type.start() < main.start()) {
            declarationStart = type.start();
        }
        if (declarationStart < 0) {
            return null;
        }
        int line = lineOf(code, declarationStart);
        String[] lines = code.split("\n", -1);
        return new MainLensAnchor(line, indentOf(lines[line]), true);
    }

    private static int lineOf(String source, int offset) {
        int line = 0;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int col = 0;
        while (col < line.length() && Character.isWhitespace(line.charAt(col))) {
            col++;
        }
        return col;
    }

    public static boolean hasValidMain(String source) {
        return source != null && !source.isBlank()
                && MAIN_METHOD.matcher(JavaSourceText.blankComments(source)).find();
    }

    private static List<MainClass> sorted(List<MainClass> found) {
        return found.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String typeNameOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static String packageOf(String code) {
        Matcher matcher = PACKAGE.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }
}
