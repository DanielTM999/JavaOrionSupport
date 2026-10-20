package dtm.ide.build.incremental;

import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

record MavenCompilerOptions(String release, String source, String target, String testRelease,
                            String testSource, String testTarget, String encoding, String proc,
                            List<String> processors, boolean parameters, boolean enablePreview,
                            List<String> compilerArgs, String unsupported) {

    static final String DEFAULT_ENCODING = "UTF-8";
    static final String DEFAULT_PROC = "full";

    private static final Set<String> CONFLICTING_ARGUMENTS = Set.of(
            "-d", "-s", "-cp", "-classpath", "--class-path", "-processorpath", "--processor-path",
            "--processor-module-path", "--release", "-source", "--source", "-target", "--target",
            "-encoding", "-sourcepath", "--source-path", "--module-path", "-p", "-implicit:class");

    static MavenCompilerOptions defaults() {
        return new MavenCompilerOptions("", "", "", "", "", "", DEFAULT_ENCODING, DEFAULT_PROC, List.of(),
                false, false, List.of(), "");
    }

    static MavenCompilerOptions of(Path modulePom, Path rootPom) {
        Element module = AnnotationProcessorPaths.parse(modulePom);
        Element root = rootPom == null || rootPom.equals(modulePom) ? null : AnnotationProcessorPaths.parse(rootPom);
        Map<String, String> properties = new LinkedHashMap<>();
        AnnotationProcessorPaths.collectProperties(root, properties);
        AnnotationProcessorPaths.collectProperties(module, properties);
        addProjectProperties(module, modulePom, properties);
        List<Element> configurations = new ArrayList<>();
        configurations.addAll(compilerConfigurations(module));
        configurations.addAll(compilerConfigurations(root));
        Lookup lookup = new Lookup(configurations, properties);

        String release = resolved(lookup.value("release", "maven.compiler.release"));
        String source = resolved(lookup.value("source", "maven.compiler.source"));
        String target = resolved(lookup.value("target", "maven.compiler.target"));
        String testRelease = resolved(lookup.value("testRelease", "maven.compiler.testRelease"));
        String testSource = resolved(lookup.value("testSource", "maven.compiler.testSource"));
        String testTarget = resolved(lookup.value("testTarget", "maven.compiler.testTarget"));
        String encoding = resolved(lookup.value("encoding", "maven.compiler.encoding"));
        if (encoding.isBlank()) {
            encoding = resolved(expanded(properties.get("project.build.sourceEncoding"), properties));
        }
        String proc = lookup.value("proc", "maven.compiler.proc");
        boolean parameters = "true".equalsIgnoreCase(lookup.value("parameters", "maven.compiler.parameters"))
                || inheritsSpringBootParent(module) || inheritsSpringBootParent(root);
        boolean enablePreview = "true".equalsIgnoreCase(
                lookup.value("enablePreview", "maven.compiler.enablePreview"));
        List<String> processors = new ArrayList<>(lookup.list("annotationProcessors", "annotationProcessor"));
        List<String> compilerArgs = new ArrayList<>(lookup.list("compilerArgs", "arg"));
        String single = lookup.value("compilerArgument", null);
        if (!single.isBlank()) {
            compilerArgs.addAll(List.of(single.trim().split("\\s+")));
        }

        String unsupported = "";
        String compilerId = lookup.value("compilerId", "maven.compiler.compilerId");
        if (!compilerId.isBlank() && !compilerId.equals("javac")) {
            unsupported = "compilador " + compilerId;
        } else if ("true".equalsIgnoreCase(lookup.value("fork", "maven.compiler.fork"))
                && !lookup.value("executable", "maven.compiler.executable").isBlank()) {
            unsupported = "javac externo no pom";
        } else if (!proc.isBlank() && !Set.of("none", "only", "full").contains(proc)) {
            unsupported = "proc " + proc;
        } else {
            for (String argument : compilerArgs) {
                if (CONFLICTING_ARGUMENTS.contains(argument) || argument.startsWith("-J")
                        || argument.startsWith("-proc:") || argument.contains("${")) {
                    unsupported = "argumento " + argument + " no compilerArgs";
                    break;
                }
            }
        }
        if (unsupported.isEmpty() && processors.stream().anyMatch(value -> value.contains("${"))) {
            unsupported = "annotationProcessors com propriedade nao resolvida";
        }
        return new MavenCompilerOptions(release, source, target, testRelease, testSource, testTarget,
                encoding.isBlank() ? DEFAULT_ENCODING : encoding, proc.isBlank() ? DEFAULT_PROC : proc,
                List.copyOf(processors), parameters, enablePreview, List.copyOf(compilerArgs), unsupported);
    }

    boolean isSupported() {
        return unsupported.isEmpty();
    }

    Optional<Integer> releaseMajor(boolean test) {
        String value = test && !testRelease.isBlank() ? testRelease : release;
        return majorOf(value);
    }

    Optional<String> sourceLevel(boolean test) {
        String value = test && !testSource.isBlank() ? testSource : source;
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    Optional<String> targetLevel(boolean test) {
        String value = test && !testTarget.isBlank() ? testTarget : target;
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    boolean declaresLanguageLevel(boolean test) {
        return releaseMajor(test).isPresent() || sourceLevel(test).isPresent() || targetLevel(test).isPresent();
    }

    static Optional<Integer> majorOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String resolved(String value) {
        return value == null || value.contains("${") ? "" : value;
    }

    private static void addProjectProperties(Element module, Path modulePom, Map<String, String> properties) {
        if (module == null) {
            return;
        }
        String groupId = AnnotationProcessorPaths.text(AnnotationProcessorPaths.child(module, "groupId"));
        if (groupId.isBlank()) {
            groupId = AnnotationProcessorPaths.text(AnnotationProcessorPaths.child(
                    AnnotationProcessorPaths.child(module, "parent"), "groupId"));
        }
        properties.putIfAbsent("project.groupId", groupId);
        properties.putIfAbsent("project.artifactId",
                AnnotationProcessorPaths.text(AnnotationProcessorPaths.child(module, "artifactId")));
        if (modulePom != null && modulePom.getParent() != null) {
            properties.putIfAbsent("project.basedir", modulePom.getParent().toAbsolutePath().toString());
            properties.putIfAbsent("basedir", modulePom.getParent().toAbsolutePath().toString());
        }
    }

    private static boolean inheritsSpringBootParent(Element project) {
        Element parent = AnnotationProcessorPaths.child(project, "parent");
        return "spring-boot-starter-parent".equals(
                AnnotationProcessorPaths.text(AnnotationProcessorPaths.child(parent, "artifactId")));
    }

    private static List<Element> compilerConfigurations(Element project) {
        List<Element> configurations = new ArrayList<>();
        if (project == null) {
            return configurations;
        }
        Element build = AnnotationProcessorPaths.child(project, "build");
        List<Element> plugins = new ArrayList<>(AnnotationProcessorPaths.children(
                AnnotationProcessorPaths.child(build, "plugins"), "plugin"));
        plugins.addAll(AnnotationProcessorPaths.children(AnnotationProcessorPaths.child(
                AnnotationProcessorPaths.child(build, "pluginManagement"), "plugins"), "plugin"));
        for (Element plugin : plugins) {
            if (!"maven-compiler-plugin".equals(AnnotationProcessorPaths.text(
                    AnnotationProcessorPaths.child(plugin, "artifactId")))) {
                continue;
            }
            Element configuration = AnnotationProcessorPaths.child(plugin, "configuration");
            if (configuration != null) {
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    private static String expanded(String value, Map<String, String> properties) {
        return value == null ? "" : AnnotationProcessorPaths.expand(value.trim(), properties).trim();
    }

    private record Lookup(List<Element> configurations, Map<String, String> properties) {

        String value(String element, String property) {
            for (Element configuration : configurations) {
                Element found = AnnotationProcessorPaths.child(configuration, element);
                if (found != null) {
                    return expanded(AnnotationProcessorPaths.text(found), properties);
                }
            }
            return property == null ? "" : expanded(properties.get(property), properties);
        }

        List<String> list(String element, String item) {
            for (Element configuration : configurations) {
                Element found = AnnotationProcessorPaths.child(configuration, element);
                if (found == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (Element entry : AnnotationProcessorPaths.children(found, item)) {
                    String value = expanded(AnnotationProcessorPaths.text(entry), properties);
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
                return values;
            }
            return List.of();
        }
    }
}
