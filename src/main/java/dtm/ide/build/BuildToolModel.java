package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record BuildToolModel(String tool, List<Node> projects, List<Node> profiles) {

    public BuildToolModel {
        projects = projects == null ? List.of() : List.copyOf(projects);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    public BuildToolModel(String tool, List<Node> projects) {
        this(tool, projects, List.of());
    }

    public enum Kind {
        PROJECT, GROUP, COMMAND, DEPENDENCY, PROFILE, PLUGIN, PLUGIN_GOAL, REPOSITORY, RUN_CONFIG
    }

    public record Node(Kind kind, String name, String detail, Coordinate coordinate,
                       JavaModule module, List<String> command, List<Node> children) {

        public Node {
            detail = detail == null ? "" : detail.trim();
            command = command == null ? List.of() : List.copyOf(command);
            children = children == null ? List.of() : List.copyOf(children);
        }

        public Node(Kind kind, String name, String detail, JavaModule module,
                    List<String> command, List<Node> children) {
            this(kind, name, detail, null, module, command, children);
        }

        public Node(Kind kind, String name, JavaModule module, List<String> command,
                    List<Node> children) {
            this(kind, name, "", null, module, command, children);
        }

        public boolean executable() {
            return !command.isEmpty();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public record Coordinate(String groupId, String artifactId, String version) {

        public Coordinate {
            groupId = groupId == null ? "" : groupId.trim();
            artifactId = artifactId == null ? "" : artifactId.trim();
            version = version == null ? "" : version.trim();
        }
    }

    private static final Pattern XML_DEPENDENCY = Pattern.compile(
            "(?s)<dependency\\b[^>]*>(.*?)</dependency>");
    private static final Pattern XML_PLUGIN = Pattern.compile(
            "(?s)<plugin\\b[^>]*>(.*?)</plugin>");
    private static final Pattern XML_PROFILE = Pattern.compile(
            "(?s)<profile\\b[^>]*>.*?<id>\\s*([^<]+).*?</profile>");
    private static final Pattern XML_REPOSITORY = Pattern.compile(
            "(?s)<repository\\b[^>]*>(.*?)</repository>");
    private static final Pattern GRADLE_TASK = Pattern.compile(
            "(?m)(?:tasks\\.(?:register|create)\\s*\\(\\s*[\"']([^\"']+)|^\\s*task\\s+([A-Za-z_$][\\w$]*))");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "(?m)\\b([A-Za-z_$][\\w$]*)\\s*(?:\\(|\\s)\\s*[\"']([^\"']+:[^\"']+)[\"']");

    private static final List<String> MAVEN_LIFECYCLE = List.of(
            "clean", "validate", "compile", "test", "package", "verify", "install", "site", "deploy");

    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    public static BuildToolModel load(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return new BuildToolModel("Build Tools", List.of(), List.of());
        }
        boolean maven = descriptor.isMaven();
        List<Node> projects = descriptor.modules().stream()
                .map(module -> maven ? maven(module) : gradle(module))
                .toList();
        return new BuildToolModel(maven ? "Maven" : "Gradle", projects,
                maven ? profilesOf(descriptor) : List.of());
    }

    private static List<Node> profilesOf(JavaProjectDescriptor descriptor) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (JavaModule module : descriptor.modules()) {
            String xml = JavaProjectConventions.readOrEmpty(
                    module.root().resolve(JavaProjectConventions.POM_FILE));
            for (String id : matches(XML_PROFILE, xml)) {
                byId.putIfAbsent(id.trim(),
                        new Node(Kind.PROFILE, id.trim(), "", module, List.of(), List.of()));
            }
        }
        return List.copyOf(byId.values());
    }

    private static Node maven(JavaModule module) {
        String xml = JavaProjectConventions.readOrEmpty(
                module.root().resolve(JavaProjectConventions.POM_FILE));
        List<Node> groups = new ArrayList<>();
        groups.add(group("Lifecycle", module, MAVEN_LIFECYCLE));
        groups.add(new Node(Kind.GROUP, "Plugins", "", module, List.of(), plugins(xml, module)));
        groups.add(new Node(Kind.GROUP, "Dependencies", "", module, List.of(),
                dependencies(xml, module)));
        groups.add(new Node(Kind.GROUP, "Repositories", "", module, List.of(),
                repositories(xml, module)));
        return new Node(Kind.PROJECT, module.name(), module.coordinates(), module,
                List.of(), groups);
    }

    private static List<Node> plugins(String xml, JavaModule module) {
        List<Node> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = XML_PLUGIN.matcher(xml == null ? "" : xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            String artifactId = tag(block, "artifactId");
            if (artifactId.isBlank() || !seen.add(artifactId)) {
                continue;
            }
            String groupId = orDefault(tag(block, "groupId"), DEFAULT_PLUGIN_GROUP);
            String version = tag(block, "version");
            String coordinates = groupId + ":" + artifactId + (version.isBlank() ? "" : ":" + version);
            found.add(new Node(Kind.PLUGIN, shortPluginName(artifactId), coordinates,
                    new Coordinate(groupId, artifactId, version), module, List.of(), List.of()));
        }
        return List.copyOf(found);
    }

    private static String shortPluginName(String artifactId) {
        String name = artifactId;
        if (name.startsWith("maven-")) {
            name = name.substring("maven-".length());
        }
        if (name.endsWith("-plugin")) {
            name = name.substring(0, name.length() - "-plugin".length());
        }
        return name.isBlank() ? artifactId : name;
    }

    private static List<Node> dependencies(String xml, JavaModule module) {
        List<Node> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = XML_DEPENDENCY.matcher(xml == null ? "" : xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            String groupId = tag(block, "groupId");
            String artifactId = tag(block, "artifactId");
            if (groupId.isBlank() || artifactId.isBlank()) {
                continue;
            }
            String version = tag(block, "version");
            String name = groupId + ":" + artifactId + (version.isBlank() ? "" : ":" + version);
            if (!seen.add(name)) {
                continue;
            }
            String scope = tag(block, "scope");
            found.add(new Node(Kind.DEPENDENCY, name, scope.isBlank() ? "" : "(" + scope + ")",
                    module, List.of(), List.of()));
        }
        return List.copyOf(found);
    }

    private static List<Node> repositories(String xml, JavaModule module) {
        List<Node> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = XML_REPOSITORY.matcher(xml == null ? "" : xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            String id = tag(block, "id");
            String url = tag(block, "url");
            if (id.isBlank() && url.isBlank()) {
                continue;
            }
            String name = id.isBlank() ? url : id;
            if (seen.add(name)) {
                found.add(new Node(Kind.REPOSITORY, name, url, module, List.of(), List.of()));
            }
        }
        return List.copyOf(found);
    }

    private static Node gradle(JavaModule module) {
        Path file = JavaProjectConventions.gradleBuildFile(module.root());
        String script = JavaProjectConventions.readOrEmpty(file);
        Set<String> tasks = new LinkedHashSet<>(List.of("build", "classes", "test", "check",
                "assemble", "clean", "run", "tasks", "dependencies"));
        Matcher taskMatcher = GRADLE_TASK.matcher(script);
        while (taskMatcher.find()) {
            String value = taskMatcher.group(1) == null ? taskMatcher.group(2) : taskMatcher.group(1);
            if (value != null && !value.isBlank()) {
                tasks.add(value.trim());
            }
        }
        List<Node> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher dependencyMatcher = GRADLE_DEPENDENCY.matcher(script);
        while (dependencyMatcher.find()) {
            String notation = dependencyMatcher.group(2);
            if (seen.add(notation)) {
                dependencies.add(new Node(Kind.DEPENDENCY, notation,
                        "(" + dependencyMatcher.group(1) + ")", module, List.of(), List.of()));
            }
        }
        return new Node(Kind.PROJECT, module.name(), module.coordinates(), module, List.of(),
                List.of(group("Tasks", module, List.copyOf(tasks)),
                        new Node(Kind.GROUP, "Dependencies", "", module, List.of(), dependencies)));
    }

    private static Node group(String name, JavaModule module, List<String> commands) {
        return new Node(Kind.GROUP, name, "", module, List.of(), commands.stream()
                .map(command -> new Node(Kind.COMMAND, command, "", module,
                        List.of(command), List.of()))
                .toList());
    }

    private static String tag(String block, String name) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(name) + "\\s*>([^<]*)</"
                + Pattern.quote(name) + ">").matcher(block == null ? "" : block);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> matches(Pattern pattern, String value) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return List.copyOf(new LinkedHashSet<>(found));
    }
}
