package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PomStructureCompletion {

    private static final String ROOT = "";
    private static final String ELEMENT_DETAIL = "elemento do POM";
    private static final String CLOSE_DETAIL = "fechar elemento";
    private static final String VALUE_DETAIL = "valor";

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile(
            "<(/?)([A-Za-z_][\\w.\\-:]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>");
    private static final Pattern OPENING_AT_CARET = Pattern.compile("<([A-Za-z_][\\w.\\-]*)?$");
    private static final Pattern CLOSING_AT_CARET = Pattern.compile("</([\\w.\\-:]*)$");
    private static final Pattern VALUE_AT_CARET = Pattern.compile("<([\\w.\\-]+)\\s*>\\s*([\\w.\\-:]*)$");

    private static final Map<String, List<String>> CHILDREN = new LinkedHashMap<>();
    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("project", """
                    project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 \
                    http://maven.apache.org/xsd/maven-4.0.0.xsd">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                        <version>${3:1.0.0-SNAPSHOT}</version>
                        $0
                    </project>"""),
            Map.entry("dependency", """
                    dependency>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                        <version>$3</version>$0
                    </dependency>"""),
            Map.entry("parent", """
                    parent>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                        <version>$3</version>$0
                    </parent>"""),
            Map.entry("plugin", """
                    plugin>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                        <version>$3</version>$0
                    </plugin>"""),
            Map.entry("path", """
                    path>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                        <version>$3</version>$0
                    </path>"""),
            Map.entry("exclusion", """
                    exclusion>
                        <groupId>$1</groupId>
                        <artifactId>$2</artifactId>
                    </exclusion>$0"""),
            Map.entry("execution", """
                    execution>
                        <id>$1</id>
                        <phase>$2</phase>
                        <goals>
                            <goal>$3</goal>
                        </goals>$0
                    </execution>"""),
            Map.entry("repository", """
                    repository>
                        <id>$1</id>
                        <url>$2</url>$0
                    </repository>"""),
            Map.entry("pluginRepository", """
                    pluginRepository>
                        <id>$1</id>
                        <url>$2</url>$0
                    </pluginRepository>"""),
            Map.entry("profile", """
                    profile>
                        <id>$1</id>
                        $0
                    </profile>"""));

    private static final Set<String> REPEATABLE = Set.of("dependency", "plugin", "module", "exclusion",
            "execution", "goal", "repository", "pluginRepository", "profile", "resource", "testResource",
            "include", "exclude", "license", "developer", "contributor", "role", "path", "arg", "extension",
            "filter", "mailingList", "otherArchive");

    private static final Set<String> FREE_FORM = Set.of("properties", "configuration");

    static {
        children(ROOT, "project");
        children("project", "modelVersion", "parent", "groupId", "artifactId", "version", "packaging",
                "name", "description", "url", "inceptionYear", "organization", "licenses", "developers",
                "contributors", "modules", "properties", "dependencyManagement", "dependencies",
                "repositories", "pluginRepositories", "build", "reporting", "profiles", "scm",
                "issueManagement", "ciManagement", "distributionManagement", "prerequisites", "mailingLists");
        children("parent", "groupId", "artifactId", "version", "relativePath");
        children("dependencyManagement", "dependencies");
        children("dependencies", "dependency");
        children("dependency", "groupId", "artifactId", "version", "type", "classifier", "scope",
                "systemPath", "optional", "exclusions");
        children("exclusions", "exclusion");
        children("exclusion", "groupId", "artifactId");
        children("modules", "module");
        children("properties", "maven.compiler.release", "maven.compiler.source", "maven.compiler.target",
                "project.build.sourceEncoding", "project.reporting.outputEncoding", "java.version");
        children("build", "defaultGoal", "directory", "finalName", "sourceDirectory", "testSourceDirectory",
                "outputDirectory", "testOutputDirectory", "resources", "testResources", "filters",
                "pluginManagement", "plugins", "extensions");
        children("pluginManagement", "plugins");
        children("plugins", "plugin");
        children("plugin", "groupId", "artifactId", "version", "extensions", "inherited", "configuration",
                "dependencies", "executions");
        children("executions", "execution");
        children("execution", "id", "phase", "goals", "inherited", "configuration");
        children("goals", "goal");
        children("configuration", "release", "source", "target", "encoding", "parameters", "compilerArgs",
                "annotationProcessorPaths", "mainClass", "skip", "skipTests", "includes", "excludes",
                "archive", "finalName");
        children("compilerArgs", "arg");
        children("annotationProcessorPaths", "path");
        children("path", "groupId", "artifactId", "version");
        children("archive", "manifest");
        children("manifest", "mainClass", "addClasspath", "classpathPrefix");
        children("extensions", "extension");
        children("extension", "groupId", "artifactId", "version");
        children("filters", "filter");
        children("resources", "resource");
        children("testResources", "testResource");
        children("resource", "directory", "targetPath", "filtering", "includes", "excludes");
        children("testResource", "directory", "targetPath", "filtering", "includes", "excludes");
        children("includes", "include");
        children("excludes", "exclude");
        children("repositories", "repository");
        children("pluginRepositories", "pluginRepository");
        children("repository", "id", "name", "url", "layout", "releases", "snapshots");
        children("pluginRepository", "id", "name", "url", "layout", "releases", "snapshots");
        children("releases", "enabled", "updatePolicy", "checksumPolicy");
        children("snapshots", "enabled", "updatePolicy", "checksumPolicy");
        children("profiles", "profile");
        children("profile", "id", "activation", "properties", "modules", "dependencyManagement",
                "dependencies", "repositories", "pluginRepositories", "build", "reporting",
                "distributionManagement");
        children("activation", "activeByDefault", "jdk", "os", "property", "file");
        children("os", "name", "family", "arch", "version");
        children("property", "name", "value");
        children("file", "exists", "missing");
        children("licenses", "license");
        children("license", "name", "url", "distribution", "comments");
        children("developers", "developer");
        children("contributors", "contributor");
        children("developer", "id", "name", "email", "url", "organization", "organizationUrl", "roles",
                "timezone");
        children("contributor", "name", "email", "url", "organization", "organizationUrl", "roles",
                "timezone");
        children("roles", "role");
        children("organization", "name", "url");
        children("scm", "connection", "developerConnection", "tag", "url");
        children("issueManagement", "system", "url");
        children("ciManagement", "system", "url");
        children("distributionManagement", "repository", "snapshotRepository", "site", "downloadUrl");
        children("snapshotRepository", "id", "name", "url", "layout", "uniqueVersion");
        children("site", "id", "name", "url");
        children("reporting", "excludeDefaults", "outputDirectory", "plugins");
        children("prerequisites", "maven");
        children("mailingLists", "mailingList");
        children("mailingList", "name", "subscribe", "unsubscribe", "post", "archive", "otherArchives");
    }

    private PomStructureCompletion() {
    }

    private static void children(String parent, String... names) {
        CHILDREN.put(parent, List.of(names));
    }

    static List<AutoCompleteItem> suggestions(BuildFileCompletionProvider.Insertion insertion) {
        String head = insertion.head();
        Deque<Frame> stack = openElements(head);
        if (stack == null) {
            return List.of();
        }
        Matcher closing = CLOSING_AT_CARET.matcher(head);
        if (closing.find()) {
            return closingTag(stack, closing.group(1), insertion);
        }
        Matcher opening = OPENING_AT_CARET.matcher(head);
        if (opening.find()) {
            String typed = opening.group(1) == null ? "" : opening.group(1);
            return elements(stack, typed, insertion);
        }
        Matcher value = VALUE_AT_CARET.matcher(head);
        if (value.find() && stack.peek().name().equals(value.group(1))) {
            String parent = List.copyOf(stack).get(1).name();
            return values(value.group(1), parent, value.group(2), insertion);
        }
        return List.of();
    }

    static Deque<Frame> openElements(String head) {
        int comment = head.lastIndexOf("<!--");
        if (comment >= 0 && head.indexOf("-->", comment) < 0) {
            return null;
        }
        String text = COMMENT.matcher(head).replaceAll(" ");
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(ROOT, new HashSet<>()));
        Matcher tag = TAG.matcher(text);
        while (tag.find()) {
            String name = tag.group(2);
            if (!tag.group(1).isEmpty()) {
                closeUpTo(stack, name);
                continue;
            }
            stack.peek().children().add(name);
            if (tag.group(4).isEmpty()) {
                stack.push(new Frame(name, new HashSet<>()));
            }
        }
        return stack;
    }

    private static void closeUpTo(Deque<Frame> stack, String name) {
        boolean open = stack.stream().anyMatch(frame -> frame.name().equals(name));
        while (open && stack.size() > 1) {
            if (stack.pop().name().equals(name)) {
                return;
            }
        }
    }

    private static List<AutoCompleteItem> closingTag(Deque<Frame> stack, String typed,
                                                     BuildFileCompletionProvider.Insertion insertion) {
        if (stack.peek().name().equals(ROOT)) {
            return List.of();
        }
        String name = stack.peek().name();
        if (!name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return List.of(insertion.item(name, typed, ">", CLOSE_DETAIL, null, AutoCompleteItem.Kind.KEYWORD));
    }

    private static List<AutoCompleteItem> elements(Deque<Frame> stack, String typed,
                                                   BuildFileCompletionProvider.Insertion insertion) {
        Frame parent = stack.peek();
        List<String> candidates = CHILDREN.getOrDefault(parent.name(), List.of());
        String needle = typed.toLowerCase(Locale.ROOT);
        boolean free = FREE_FORM.contains(parent.name());
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String name : candidates) {
            if (!name.toLowerCase(Locale.ROOT).startsWith(needle)
                    || (!free && !REPEATABLE.contains(name) && parent.children().contains(name))) {
                continue;
            }
            items.add(element(name, typed, insertion));
        }
        return List.copyOf(items);
    }

    private static AutoCompleteItem element(String name, String typed,
                                            BuildFileCompletionProvider.Insertion insertion) {
        String template = TEMPLATES.get(name);
        String suffix;
        if (template != null) {
            suffix = template.substring(name.length());
        } else if (CHILDREN.containsKey(name) && !FREE_FORM.contains(name)) {
            suffix = ">\n    $0\n</" + name + ">";
        } else {
            suffix = ">$0</" + name + ">";
        }
        String head = insertion.head();
        String line = head.substring(head.lastIndexOf('\n') + 1);
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        String unit = indentationUnit(head);
        String[] lines = suffix.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            int spaces = 0;
            while (spaces < lines[i].length() && lines[i].charAt(spaces) == ' ') spaces++;
            lines[i] = indent + unit.repeat(spaces / 4) + " ".repeat(spaces % 4) + lines[i].substring(spaces);
        }
        suffix = String.join("\n", lines);
        return insertion.item(name, typed, suffix, ELEMENT_DETAIL, "<" + name + ">",
                AutoCompleteItem.Kind.SNIPPET);
    }

    static String indentationUnit(String text) {
        int width = 0;
        for (String line : text.split("\n")) {
            int count = line.length() - line.stripLeading().length();
            if (count == 0 || line.isBlank()) continue;
            if (line.substring(0, count).contains("\t")) return "\t";
            if (width == 0) width = count;
            else {
                int next = count;
                while (next != 0) { int remainder = width % next; width = next; next = remainder; }
            }
        }
        return " ".repeat(width > 0 && width <= 8 ? width : 4);
    }

    private static List<AutoCompleteItem> values(String element, String parent, String typed,
                                                 BuildFileCompletionProvider.Insertion insertion) {
        List<String> values = valuesOf(element, parent);
        String needle = typed.toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(needle)) {
                items.add(insertion.item(value, typed, "", VALUE_DETAIL + " de <" + element + ">", null,
                        AutoCompleteItem.Kind.VALUE));
            }
        }
        return List.copyOf(items);
    }

    static List<String> valuesOf(String element, String parent) {
        return switch (element) {
            case "scope" -> List.of("compile", "provided", "runtime", "test", "system", "import");
            case "packaging" -> List.of("jar", "pom", "war", "ear", "maven-plugin");
            case "type" -> List.of("jar", "pom", "test-jar", "war", "ear");
            case "modelVersion" -> List.of("4.0.0");
            case "phase" -> List.of("validate", "initialize", "generate-sources", "process-sources",
                    "generate-resources", "process-resources", "compile", "process-classes",
                    "generate-test-sources", "process-test-sources", "generate-test-resources",
                    "process-test-resources", "test-compile", "process-test-classes", "test",
                    "prepare-package", "package", "pre-integration-test", "integration-test",
                    "post-integration-test", "verify", "install", "deploy", "none");
            case "updatePolicy" -> List.of("always", "daily", "never", "interval:");
            case "checksumPolicy" -> List.of("fail", "warn", "ignore");
            case "layout" -> List.of("default", "legacy");
            case "optional", "enabled", "filtering", "activeByDefault", "inherited", "skip", "skipTests",
                 "addClasspath", "parameters", "uniqueVersion", "excludeDefaults" -> List.of("true", "false");
            case "extensions" -> "plugin".equals(parent) ? List.of("true", "false") : List.of();
            case "project.build.sourceEncoding", "project.reporting.outputEncoding", "encoding" ->
                    List.of("UTF-8", "ISO-8859-1");
            default -> List.of();
        };
    }

    record Frame(String name, Set<String> children) {
    }
}
