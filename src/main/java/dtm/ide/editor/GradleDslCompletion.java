package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GradleDslCompletion {

    private static final String TOP = "";

    private static final Pattern STATEMENT_AT_CARET = Pattern.compile("(?:^|\\n)[ \\t]*([A-Za-z_][\\w.\\-]*)\\z");
    private static final Pattern NAMED_TASK = Pattern.compile("named\\s*(?:<\\s*\\w+\\s*>)?\\s*\\(\\s*[\"'](\\w+)[\"']");
    private static final Pattern TYPED_TASK = Pattern.compile("withType\\s*[(<]\\s*(\\w+)");
    private static final Pattern LAST_NAME = Pattern.compile("(\\w+)\\s*(?:\\([^()]*\\))?\\s*$");

    private record Entry(String label, String groovy, String kotlin, String detail) {

        String insert(boolean kotlinDsl) {
            return kotlinDsl ? kotlin : groovy;
        }
    }

    private static final List<Entry> BUILD_TOP = List.of(
            block("plugins", "bloco de plugins"),
            block("repositories", "repositorios"),
            block("dependencies", "dependencias"),
            new Entry("java", "java {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of($0)\n    }\n}",
                    "java {\n    toolchain {\n        languageVersion.set(JavaLanguageVersion.of($0))\n    }\n}",
                    "toolchain Java"),
            new Entry("application", "application {\n    mainClass = '$0'\n}",
                    "application {\n    mainClass.set(\"$0\")\n}", "classe principal"),
            new Entry("test", "test {\n    useJUnitPlatform()$0\n}",
                    "tasks.test {\n    useJUnitPlatform()$0\n}", "configuracao de testes"),
            new Entry("tasks", "tasks.named('$1') {\n    $0\n}",
                    "tasks.named(\"$1\") {\n    $0\n}", "configurar tarefa"),
            assignment("group", "grupo do projeto"),
            assignment("version", "versao do projeto"),
            assignment("description", "descricao do projeto"),
            block("configurations", "configuracoes"),
            block("buildscript", "classpath do build"),
            block("allprojects", "todos os projetos"),
            block("subprojects", "subprojetos"),
            new Entry("jar", "jar {\n    manifest {\n        attributes 'Main-Class': '$0'\n    }\n}",
                    "tasks.jar {\n    manifest {\n        attributes[\"Main-Class\"] = \"$0\"\n    }\n}",
                    "tarefa jar"),
            new Entry("sourceCompatibility", "sourceCompatibility = JavaVersion.VERSION_$0",
                    "java.sourceCompatibility = JavaVersion.VERSION_$0", "nivel do codigo-fonte"),
            new Entry("targetCompatibility", "targetCompatibility = JavaVersion.VERSION_$0",
                    "java.targetCompatibility = JavaVersion.VERSION_$0", "nivel do bytecode"));

    private static final List<Entry> SETTINGS_TOP = List.of(
            new Entry("rootProject.name", "rootProject.name = '$0'", "rootProject.name = \"$0\"",
                    "nome do projeto"),
            new Entry("include", "include '$0'", "include(\"$0\")", "incluir modulo"),
            block("pluginManagement", "resolucao de plugins"),
            block("dependencyResolutionManagement", "resolucao de dependencias"),
            block("plugins", "plugins de settings"));

    private static final List<Entry> DEPENDENCIES = List.of(
            configuration("implementation"),
            configuration("api"),
            configuration("compileOnly"),
            configuration("runtimeOnly"),
            configuration("annotationProcessor"),
            configuration("testImplementation"),
            configuration("testCompileOnly"),
            configuration("testRuntimeOnly"),
            configuration("testAnnotationProcessor"),
            configuration("developmentOnly"),
            configuration("classpath"),
            new Entry("implementation platform", "implementation platform('$0')",
                    "implementation(platform(\"$0\"))", "BOM de dependencias"),
            new Entry("implementation project", "implementation project(':$0')",
                    "implementation(project(\":$0\"))", "modulo do projeto"),
            new Entry("testImplementation project", "testImplementation project(':$0')",
                    "testImplementation(project(\":$0\"))", "modulo do projeto"));

    private static final List<Entry> PLUGINS = List.of(
            new Entry("id", "id '$0'", "id(\"$0\")", "plugin por id"),
            plugin("java"),
            plugin("java-library"),
            plugin("application"),
            plugin("jacoco"),
            plugin("maven-publish"),
            plugin("checkstyle"),
            plugin("pmd"),
            plugin("war"),
            new Entry("org.springframework.boot", "id 'org.springframework.boot' version '$0'",
                    "id(\"org.springframework.boot\") version \"$0\"", "Spring Boot"),
            new Entry("io.spring.dependency-management", "id 'io.spring.dependency-management' version '$0'",
                    "id(\"io.spring.dependency-management\") version \"$0\"", "Spring dependency management"));

    private static final List<Entry> REPOSITORIES = List.of(
            call("mavenCentral", "Maven Central"),
            call("mavenLocal", "repositorio local ~/.m2"),
            call("google", "repositorio Google"),
            call("gradlePluginPortal", "Gradle Plugin Portal"),
            new Entry("maven", "maven { url '$0' }", "maven { url = uri(\"$0\") }", "repositorio Maven"));

    private static final List<Entry> JAVA = List.of(
            new Entry("toolchain", "toolchain {\n    languageVersion = JavaLanguageVersion.of($0)\n}",
                    "toolchain {\n    languageVersion.set(JavaLanguageVersion.of($0))\n}", "toolchain Java"),
            new Entry("sourceCompatibility", "sourceCompatibility = JavaVersion.VERSION_$0",
                    "sourceCompatibility = JavaVersion.VERSION_$0", "nivel do codigo-fonte"),
            new Entry("targetCompatibility", "targetCompatibility = JavaVersion.VERSION_$0",
                    "targetCompatibility = JavaVersion.VERSION_$0", "nivel do bytecode"),
            call("withSourcesJar", "publicar fontes"),
            call("withJavadocJar", "publicar javadoc"));

    private static final List<Entry> TOOLCHAIN = List.of(
            new Entry("languageVersion", "languageVersion = JavaLanguageVersion.of($0)",
                    "languageVersion.set(JavaLanguageVersion.of($0))", "versao da JDK"),
            new Entry("vendor", "vendor = JvmVendorSpec.$0", "vendor.set(JvmVendorSpec.$0)", "fornecedor da JDK"));

    private static final List<Entry> TEST = List.of(
            call("useJUnitPlatform", "JUnit 5/6"),
            call("useJUnit", "JUnit 4"),
            call("useTestNG", "TestNG"),
            new Entry("maxParallelForks", "maxParallelForks = $0", "maxParallelForks = $0", "forks paralelos"),
            new Entry("jvmArgs", "jvmArgs '$0'", "jvmArgs(\"$0\")", "argumentos da JVM"),
            new Entry("systemProperty", "systemProperty '$1', '$0'", "systemProperty(\"$1\", \"$0\")",
                    "propriedade de sistema"),
            new Entry("testLogging", "testLogging {\n    events 'passed', 'skipped', 'failed'$0\n}",
                    "testLogging {\n    events(\"passed\", \"skipped\", \"failed\")$0\n}", "log dos testes"),
            new Entry("finalizedBy", "finalizedBy $0", "finalizedBy($0)", "tarefa seguinte"));

    private static final List<Entry> APPLICATION = List.of(
            new Entry("mainClass", "mainClass = '$0'", "mainClass.set(\"$0\")", "classe principal"),
            new Entry("applicationDefaultJvmArgs", "applicationDefaultJvmArgs = ['$0']",
                    "applicationDefaultJvmArgs = listOf(\"$0\")", "argumentos da JVM"));

    private static final List<Entry> CONFIGURATIONS = List.of(
            new Entry("compileOnly", "compileOnly {\n    extendsFrom annotationProcessor\n}",
                    "compileOnly {\n    extendsFrom(configurations.annotationProcessor.get())\n}",
                    "Lombok/annotation processors"),
            new Entry("all", "all {\n    exclude group: '$1', module: '$0'\n}",
                    "all {\n    exclude(group = \"$1\", module = \"$0\")\n}", "excluir de todas"));

    private static final Map<String, List<Entry>> BLOCKS = Map.ofEntries(
            Map.entry("dependencies", DEPENDENCIES),
            Map.entry("plugins", PLUGINS),
            Map.entry("repositories", REPOSITORIES),
            Map.entry("java", JAVA),
            Map.entry("toolchain", TOOLCHAIN),
            Map.entry("test", TEST),
            Map.entry("application", APPLICATION),
            Map.entry("configurations", CONFIGURATIONS),
            Map.entry("buildscript", List.of(block("repositories", "repositorios"),
                    block("dependencies", "dependencias"))),
            Map.entry("pluginManagement", List.of(block("repositories", "repositorios"),
                    block("plugins", "versoes de plugins"))),
            Map.entry("dependencyResolutionManagement", List.of(block("repositories", "repositorios"))));

    private GradleDslCompletion() {
    }

    static List<AutoCompleteItem> suggestions(Path file, BuildFileCompletionProvider.Insertion insertion) {
        String head = insertion.head();
        Matcher statement = STATEMENT_AT_CARET.matcher(head);
        if (!statement.find()) {
            return List.of();
        }
        Deque<String> blocks = openBlocks(head);
        if (blocks == null) {
            return List.of();
        }
        String name = file == null || file.getFileName() == null ? ""
                : file.getFileName().toString();
        boolean kotlin = name.endsWith(".kts");
        boolean settings = name.startsWith("settings.");
        String block = blocks.isEmpty() ? TOP : blocks.peek();
        List<Entry> entries = switch (block) {
            case TOP, "allprojects", "subprojects" -> settings ? SETTINGS_TOP : BUILD_TOP;
            default -> BLOCKS.getOrDefault(block, List.of());
        };
        String typed = statement.group(1);
        String needle = typed.toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.label().toLowerCase(Locale.ROOT).startsWith(needle)) {
                items.add(insertion.replacing(entry.label(), typed, entry.insert(kotlin), entry.detail(),
                        null, AutoCompleteItem.Kind.SNIPPET));
            }
        }
        return List.copyOf(items);
    }

    static Deque<String> openBlocks(String head) {
        Deque<String> blocks = new ArrayDeque<>();
        int statementStart = 0;
        int length = head.length();
        int index = 0;
        while (index < length) {
            char current = head.charAt(index);
            char next = index + 1 < length ? head.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                int end = head.indexOf('\n', index);
                if (end < 0) {
                    return null;
                }
                index = end;
                continue;
            }
            if (current == '/' && next == '*') {
                int end = head.indexOf("*/", index + 2);
                if (end < 0) {
                    return null;
                }
                index = end + 2;
                continue;
            }
            if (current == '"' || current == '\'') {
                int end = stringEnd(head, index);
                if (end < 0) {
                    return null;
                }
                index = end;
                continue;
            }
            if (current == '{') {
                blocks.push(blockName(head.substring(statementStart, index)));
                statementStart = index + 1;
            } else if (current == '}') {
                if (!blocks.isEmpty()) {
                    blocks.pop();
                }
                statementStart = index + 1;
            } else if (current == '\n' || current == ';') {
                statementStart = index + 1;
            }
            index++;
        }
        return blocks;
    }

    private static int stringEnd(String text, int start) {
        char quote = text.charAt(start);
        String triple = String.valueOf(quote).repeat(3);
        if (text.startsWith(triple, start)) {
            int end = text.indexOf(triple, start + 3);
            return end < 0 ? -1 : end + 3;
        }
        for (int index = start + 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == quote) {
                return index + 1;
            } else if (current == '\n') {
                return index;
            }
        }
        return -1;
    }

    static String blockName(String header) {
        String trimmed = header.trim();
        Matcher named = NAMED_TASK.matcher(trimmed);
        if (named.find()) {
            return named.group(1);
        }
        Matcher typed = TYPED_TASK.matcher(trimmed);
        if (typed.find()) {
            String type = typed.group(1);
            return Character.toLowerCase(type.charAt(0)) + type.substring(1);
        }
        Matcher last = LAST_NAME.matcher(trimmed);
        return last.find() ? last.group(1) : trimmed;
    }

    private static Entry block(String name, String detail) {
        return new Entry(name, name + " {\n    $0\n}", name + " {\n    $0\n}", detail);
    }

    private static Entry assignment(String name, String detail) {
        return new Entry(name, name + " = '$0'", name + " = \"$0\"", detail);
    }

    private static Entry configuration(String name) {
        return new Entry(name, name + " '$0'", name + "(\"$0\")", "configuracao de dependencia");
    }

    private static Entry plugin(String id) {
        return new Entry(id, "id '" + id + "'", "id(\"" + id + "\")", "plugin principal do Gradle");
    }

    private static Entry call(String name, String detail) {
        return new Entry(name, name + "()$0", name + "()$0", detail);
    }
}
