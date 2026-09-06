package dtm.ide.project;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JavaProjectConventions {

    public static final String PROJECT_TYPE = "Java";

    public static final String POM_FILE = "pom.xml";
    public static final String GRADLE_BUILD_GROOVY = "build.gradle";
    public static final String GRADLE_BUILD_KOTLIN = "build.gradle.kts";
    public static final String GRADLE_SETTINGS_GROOVY = "settings.gradle";
    public static final String GRADLE_SETTINGS_KOTLIN = "settings.gradle.kts";

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");
    private static final Set<String> HANDLED_EXTENSIONS = Set.of(
            "java", "properties", "yml", "yaml", "gradle", "kts", "jsp", "jar", "xml"
    );
    private static final Set<String> BUILD_FILES = Set.of(
            POM_FILE, GRADLE_BUILD_GROOVY, GRADLE_BUILD_KOTLIN,
            GRADLE_SETTINGS_GROOVY, GRADLE_SETTINGS_KOTLIN,
            "gradle.properties", "maven-wrapper.properties", "gradle-wrapper.properties"
    );
    private static final Set<String> IGNORED_FOLDERS = Set.of(
            "target", "build", "out", "bin", ".gradle", ".git", ".idea", ".orion",
            ".settings", "node_modules", ".mvn"
    );

    private static final List<String> SOURCE_ROOT_CANDIDATES =
            List.of("src/main/java", "src", "source");

    private static final int SOURCE_SCAN_DEPTH = 12;

    private static final int SHALLOW_SCAN_DEPTH = 2;

    /** Teto de arquivos por varredura, para nao pagar um projeto inteiro em nenhum caminho. */
    public static final int MAX_SCAN_FILES = 20_000;

    /** Teto da varredura textual de anotacoes, que le o conteudo de cada arquivo. */
    public static final int MAX_ANNOTATION_SCAN_FILES = 2_000;

    private JavaProjectConventions() {
    }

    public static boolean supports(Path path) {
        Path root = projectRoot(path);
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        return hasBuildFile(root) || hasNestedBuildFile(root) || hasJavaNearRoot(root);
    }

    public static boolean handlesPath(Path path) {
        if (path == null) {
            return true;
        }
        String extension = extensionOf(path);
        return extension.isEmpty()
                || HANDLED_EXTENSIONS.contains(extension)
                || BUILD_FILES.contains(fileName(path));
    }

    public static Path projectRoot(Path input) {
        if (input == null) {
            return null;
        }
        Path normalized = normalize(input);
        return Files.isDirectory(normalized) ? normalized : normalized.getParent();
    }

    public static boolean isJava(Path path) {
        return JAVA_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isBuildFile(Path path) {
        return BUILD_FILES.contains(fileName(path));
    }

    public static boolean isMavenPom(Path path) {
        return POM_FILE.equals(fileName(path));
    }

    public static boolean isGradleBuildFile(Path path) {
        String name = fileName(path);
        return GRADLE_BUILD_GROOVY.equals(name) || GRADLE_BUILD_KOTLIN.equals(name)
                || GRADLE_SETTINGS_GROOVY.equals(name) || GRADLE_SETTINGS_KOTLIN.equals(name);
    }

    public static boolean isIgnoredFolder(Path path) {
        return path != null && path.getFileName() != null
                && IGNORED_FOLDERS.contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    public static JavaProjectDescriptor describe(Path input) {
        Path root = projectRoot(input);
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }

        List<JavaModule> modules;
        JavaProjectKind kind;

        if (Files.isRegularFile(root.resolve(POM_FILE))) {
            modules = discoverMavenModules(root);
            kind = modules.size() > 1 ? JavaProjectKind.MAVEN_MULTIMODULE : JavaProjectKind.MAVEN;
        } else if (hasGradleBuild(root)) {
            modules = discoverGradleModules(root);
            kind = modules.size() > 1 ? JavaProjectKind.GRADLE_MULTIPROJECT : JavaProjectKind.GRADLE;
        } else {
            List<Path> nested = discoverNestedProjectRoots(root);
            if (nested.size() > 1) {
                modules = new ArrayList<>();
                for (Path child : nested) {
                    JavaProjectDescriptor childDescriptor = describe(child);
                    if (childDescriptor != null) {
                        modules.addAll(childDescriptor.modules());
                    }
                }
                kind = JavaProjectKind.JAVA_WORKSPACE;
            } else if (nested.size() == 1) {
                return describe(nested.getFirst());
            } else if (hasJavaNearRoot(root)) {
                modules = List.of(plainJavaModule(root));
                kind = JavaProjectKind.PLAIN_JAVA;
            } else {
                return null;
            }
        }

        if (modules.isEmpty()) {
            return null;
        }

        modules = applyLayoutOverrides(root, modules);
        boolean springBoot = detectSpringBoot(root, modules);
        boolean spring = springBoot || detectSpring(root, modules);
        return new JavaProjectDescriptor(root, kind, modules, springBoot, spring,
                detectJdkVersion(root, kind), findWrapper(root, kind));
    }

    private static List<JavaModule> applyLayoutOverrides(Path root, List<JavaModule> modules) {
        ProjectLayout layout = ProjectLayout.of(root);
        if (layout.isEmpty()) {
            return modules;
        }
        List<JavaModule> adjusted = new ArrayList<>(modules.size());
        for (JavaModule module : modules) {
            List<Path> sources = new ArrayList<>(
                    layout.merge(module.sourceRoots(), ProjectLayout.Role.SOURCE));
            sources.addAll(layout.foldersWith(ProjectLayout.Role.RESOURCE));
            List<Path> tests = new ArrayList<>(
                    layout.merge(module.testRoots(), ProjectLayout.Role.TEST));
            tests.addAll(layout.foldersWith(ProjectLayout.Role.TEST_RESOURCE));

            adjusted.add(new JavaModule(module.root(), module.name(), module.groupId(),
                    module.artifactId(), module.packaging(),
                    within(module.root(), sources), within(module.root(), tests),
                    module.outputDir()));
        }
        return List.copyOf(adjusted);
    }

    private static List<Path> within(Path moduleRoot, List<Path> folders) {
        Path normalizedRoot = normalize(moduleRoot);
        return folders.stream()
                .map(JavaProjectConventions::normalize)
                .filter(folder -> folder.startsWith(normalizedRoot))
                .distinct()
                .toList();
    }

    public static List<JavaModule> discoverMavenModules(Path root) {
        List<JavaModule> modules = new ArrayList<>();
        collectMavenModules(normalize(root), modules, new LinkedHashSet<>());
        return modules;
    }

    private static void collectMavenModules(Path moduleRoot, List<JavaModule> collected, Set<Path> visited) {
        if (moduleRoot == null || !visited.add(moduleRoot)) {
            return;
        }
        Path pom = moduleRoot.resolve(POM_FILE);
        if (!Files.isRegularFile(pom)) {
            return;
        }
        MavenPom parsed = MavenPom.parse(pom);
        collected.add(mavenModule(moduleRoot, parsed));

        for (String child : parsed.values("modules", "module")) {
            Path childRoot = normalize(moduleRoot.resolve(child.trim()));
            if (childRoot.startsWith(moduleRoot)) {
                collectMavenModules(childRoot, collected, visited);
            }
        }
    }

    private static JavaModule mavenModule(Path moduleRoot, MavenPom pom) {
        String artifactId = pom.value("artifactId");
        String groupId = pom.value("groupId");
        if (groupId.isBlank()) {
            groupId = pom.value("parent", "groupId");
        }
        String packaging = pom.value("packaging");
        return new JavaModule(
                moduleRoot,
                artifactId.isBlank() ? moduleRoot.getFileName().toString() : artifactId,
                groupId,
                artifactId,
                packaging,
                List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/resources")),
                List.of(moduleRoot.resolve("src/test/java"), moduleRoot.resolve("src/test/resources")),
                moduleRoot.resolve("target/classes"));
    }

    private static final Pattern GRADLE_INCLUDE = Pattern.compile(
            "include\\s*\\(?\\s*((?:[\"'][^\"']+[\"']\\s*,?\\s*)+)\\)?");
    private static final Pattern GRADLE_QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    public static List<JavaModule> discoverGradleModules(Path root) {
        Path normalized = normalize(root);
        List<JavaModule> modules = new ArrayList<>();
        modules.add(gradleModule(normalized, normalized.getFileName().toString()));

        String settings = readOrEmpty(gradleSettingsFile(normalized));
        if (settings.isBlank()) {
            return modules;
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher includes = GRADLE_INCLUDE.matcher(stripComments(settings));
        while (includes.find()) {
            Matcher quoted = GRADLE_QUOTED.matcher(includes.group(1));
            while (quoted.find()) {
                paths.add(quoted.group(1));
            }
        }
        for (String projectPath : paths) {
            String relative = projectPath.startsWith(":") ? projectPath.substring(1) : projectPath;
            Path moduleRoot = normalize(normalized.resolve(relative.replace(':', '/')));
            if (moduleRoot.startsWith(normalized) && !moduleRoot.equals(normalized)
                    && Files.isDirectory(moduleRoot)) {
                modules.add(gradleModule(moduleRoot, relative.replace('/', ':')));
            }
        }
        return modules;
    }

    private static JavaModule gradleModule(Path moduleRoot, String name) {
        return new JavaModule(
                moduleRoot,
                name,
                "",
                moduleRoot.getFileName().toString(),
                Files.isDirectory(moduleRoot.resolve("src/main/webapp")) ? "war" : "jar",
                List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/resources")),
                List.of(moduleRoot.resolve("src/test/java"), moduleRoot.resolve("src/test/resources")),
                moduleRoot.resolve("build/classes/java/main"));
    }

    private static Path gradleSettingsFile(Path root) {
        Path kotlin = root.resolve(GRADLE_SETTINGS_KOTLIN);
        return Files.isRegularFile(kotlin) ? kotlin : root.resolve(GRADLE_SETTINGS_GROOVY);
    }

    public static Path gradleBuildFile(Path moduleRoot) {
        Path kotlin = moduleRoot.resolve(GRADLE_BUILD_KOTLIN);
        return Files.isRegularFile(kotlin) ? kotlin : moduleRoot.resolve(GRADLE_BUILD_GROOVY);
    }

    private static JavaModule plainJavaModule(Path root) {
        List<Path> sources = new ArrayList<>();
        for (String candidate : List.of("src/main/java", "src", "source")) {
            Path path = root.resolve(candidate);
            if (Files.isDirectory(path)) {
                sources.add(path);
                break;
            }
        }
        if (sources.isEmpty()) {
            sources.add(root);
        }
        List<Path> tests = new ArrayList<>();
        for (String candidate : List.of("src/test/java", "test", "tests")) {
            Path path = root.resolve(candidate);
            if (Files.isDirectory(path)) {
                tests.add(path);
                break;
            }
        }
        return new JavaModule(root, root.getFileName().toString(), "",
                root.getFileName().toString(), "jar", sources, tests,
                root.resolve(".orion/out"));
    }

    private static List<Path> discoverNestedProjectRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(child -> !isIgnoredFolder(child))
                    .filter(JavaProjectConventions::hasBuildFile)
                    .map(JavaProjectConventions::normalize)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(roots::add);
        } catch (IOException ignored) {
        }
        return roots;
    }

    private static boolean hasBuildFile(Path root) {
        return Files.isRegularFile(root.resolve(POM_FILE)) || hasGradleBuild(root);
    }

    private static boolean hasGradleBuild(Path root) {
        return Files.isRegularFile(root.resolve(GRADLE_BUILD_GROOVY))
                || Files.isRegularFile(root.resolve(GRADLE_BUILD_KOTLIN))
                || Files.isRegularFile(root.resolve(GRADLE_SETTINGS_GROOVY))
                || Files.isRegularFile(root.resolve(GRADLE_SETTINGS_KOTLIN));
    }

    private static boolean hasNestedBuildFile(Path root) {
        return !discoverNestedProjectRoots(root).isEmpty();
    }

    private static boolean hasJavaNearRoot(Path root) {
        for (String candidate : SOURCE_ROOT_CANDIDATES) {
            Path sourceRoot = root.resolve(candidate);
            if (Files.isDirectory(sourceRoot) && containsJava(sourceRoot, SOURCE_SCAN_DEPTH)) {
                return true;
            }
        }
        return containsJava(root, SHALLOW_SCAN_DEPTH);
    }

    private static boolean containsJava(Path root, int depth) {
        return !javaSources(root, depth, 1).isEmpty();
    }

    /**
     * Coleta arquivos {@code .java} sob {@code root} podando as pastas ignoradas na descida.
     * Diferente de {@link Files#walk}, nao entra em {@code target/}, {@code build/},
     * {@code node_modules/} nem {@code .git/} - o custo dessas subarvores nem chega a ser pago.
     *
     * @param maxDepth profundidade maxima relativa a {@code root}; {@code <= 0} significa sem limite
     * @param maxFiles teto de arquivos coletados; {@code <= 0} usa {@link #MAX_SCAN_FILES}
     */
    public static List<Path> javaSources(Path root, int maxDepth, int maxFiles) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        int depth = maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth;
        int limit = maxFiles <= 0 ? MAX_SCAN_FILES : maxFiles;
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.<FileVisitOption>of(), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return isIgnoredFolder(dir) && !dir.equals(root)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || !isJava(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    found.add(file);
                    return found.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return found;
    }

    private static final Pattern SPRING_BOOT_APPLICATION =
            Pattern.compile("@SpringBootApplication\\b");

    public static boolean detectSpringBoot(Path root, List<JavaModule> modules) {
        boolean declaresBuild = false;
        for (JavaModule module : modules) {
            String build = readBuildScript(module.root());
            if (build.contains("spring-boot-starter-parent")
                    || build.contains("spring-boot-maven-plugin")
                    || build.contains("spring-boot-gradle-plugin")
                    || build.contains("org.springframework.boot")
                    || build.contains("spring-boot-starter")) {
                return true;
            }
            declaresBuild |= hasBuildFile(module.root());
        }
        // Projeto Boot com Maven/Gradle sempre declara o starter ou o plugin. Se ha build script
        // e ele nao menciona Boot, nao vale ler o codigo-fonte inteiro para confirmar.
        if (declaresBuild) {
            return false;
        }
        for (JavaModule module : modules) {
            for (Path sourceRoot : module.existingSourceRoots()) {
                if (findAnnotatedSource(sourceRoot, SPRING_BOOT_APPLICATION) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean detectSpring(Path root, List<JavaModule> modules) {
        for (JavaModule module : modules) {
            if (readBuildScript(module.root()).contains("org.springframework")) {
                return true;
            }
        }
        return Files.isRegularFile(root.resolve("src/main/resources/applicationContext.xml"));
    }

    public static Path findAnnotatedSource(Path root, Pattern pattern) {
        return findAnnotatedSource(root, pattern, SOURCE_SCAN_DEPTH, MAX_ANNOTATION_SCAN_FILES);
    }

    public static Path findAnnotatedSource(Path root, Pattern pattern, int maxDepth, int maxFiles) {
        for (Path file : javaSources(root, maxDepth, maxFiles)) {
            if (pattern.matcher(readOrEmpty(file)).find()) {
                return file;
            }
        }
        return null;
    }

    public static boolean isUnderIgnoredFolder(Path root, Path path) {
        Path relative = normalize(root).relativize(normalize(path));
        for (Path segment : relative) {
            if (IGNORED_FOLDERS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String readBuildScript(Path moduleRoot) {
        Path pom = moduleRoot.resolve(POM_FILE);
        if (Files.isRegularFile(pom)) {
            return readOrEmpty(pom);
        }
        return readOrEmpty(gradleBuildFile(moduleRoot)) + "\n" + readOrEmpty(gradleSettingsFile(moduleRoot));
    }

    public static final String ORION_SETTINGS_DIR = ".orion";
    public static final String ORION_JAVA_PROPERTIES = "java.properties";

    private static final Pattern GRADLE_JAVA_VERSION = Pattern.compile(
            "(?:sourceCompatibility|targetCompatibility|JavaLanguageVersion\\.of|JavaVersion\\.VERSION_)"
                    + "\\s*(?:=|\\(|)\\s*[\"']?(?:1[._])?(\\d{1,2})");
    private static final Pattern SDKMANRC_JAVA = Pattern.compile("java\\s*=\\s*(\\d{1,2})");

    private static final List<String> MAVEN_JDK_PROPERTIES = List.of(
            "maven.compiler.release", "maven.compiler.source", "maven.compiler.target",
            "java.version");

    private static final Pattern POM_PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static final int MAX_PROPERTY_DEPTH = 5;

    private static String resolvePomProperty(MavenPom pom, String value) {
        String current = value == null ? "" : value.trim();
        for (int depth = 0; depth < MAX_PROPERTY_DEPTH; depth++) {
            Matcher reference = POM_PROPERTY_REFERENCE.matcher(current);
            if (!reference.matches()) {
                return current;
            }
            String resolved = pom.property(reference.group(1).trim());
            if (resolved == null || resolved.isBlank()) {
                return "";
            }
            current = resolved.trim();
        }
        return current;
    }

    public static Integer detectJdkVersion(Path root, JavaProjectKind kind) {
        if (kind.isMaven()) {
            MavenPom pom = MavenPom.parse(root.resolve(POM_FILE));
            for (String key : MAVEN_JDK_PROPERTIES) {
                Integer major = parseMajor(resolvePomProperty(pom, pom.property(key)));
                if (major != null) {
                    return major;
                }
            }
        }
        if (kind.isGradle()) {
            Matcher matcher = GRADLE_JAVA_VERSION.matcher(stripComments(readOrEmpty(gradleBuildFile(root))));
            if (matcher.find()) {
                return parseMajor(matcher.group(1));
            }
        }
        Matcher sdkman = SDKMANRC_JAVA.matcher(readOrEmpty(root.resolve(".sdkmanrc")));
        return sdkman.find() ? parseMajor(sdkman.group(1)) : null;
    }

    public static Path findWrapper(Path root, JavaProjectKind kind) {
        List<String> names = kind.isGradle()
                ? List.of(isWindows() ? "gradlew.bat" : "gradlew", "gradlew")
                : List.of(isWindows() ? "mvnw.cmd" : "mvnw", "mvnw");
        for (String name : names) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return normalize(candidate);
            }
        }
        return null;
    }

    private static Integer parseMajor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int cut = 0;
        while (cut < value.length() && Character.isDigit(value.charAt(cut))) {
            cut++;
        }
        if (cut == 0) {
            return null;
        }
        try {
            int major = Integer.parseInt(value.substring(0, cut));
            return major > 0 && major < 100 ? major : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String stripComments(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    public static String readOrEmpty(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    public static String extensionOf(Path path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    public static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
