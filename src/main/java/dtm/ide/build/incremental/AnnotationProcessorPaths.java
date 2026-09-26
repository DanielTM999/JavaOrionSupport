package dtm.ide.build.incremental;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AnnotationProcessorPaths {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    record Coordinate(String groupId, String artifactId, String version) {
    }

    private AnnotationProcessorPaths() {
    }

    static List<Path> resolve(List<Coordinate> coordinates, Path repository) {
        if (repository == null || coordinates == null) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            Path jar = repository.resolve(coordinate.groupId().replace('.', '/'))
                    .resolve(coordinate.artifactId())
                    .resolve(coordinate.version())
                    .resolve(coordinate.artifactId() + "-" + coordinate.version() + ".jar");
            if (Files.isRegularFile(jar)) {
                jars.add(jar);
            }
        }
        return jars;
    }

    static List<Coordinate> declared(Path modulePom, Path rootPom) {
        Element module = parse(modulePom);
        Element root = rootPom == null || rootPom.equals(modulePom) ? null : parse(rootPom);
        Map<String, String> properties = new LinkedHashMap<>();
        collectProperties(root, properties);
        collectProperties(module, properties);
        List<Coordinate> coordinates = new ArrayList<>();
        Element source = hasProcessorPaths(module) ? module : root;
        for (Element path : processorPaths(source)) {
            String groupId = expand(text(child(path, "groupId")), properties);
            String artifactId = expand(text(child(path, "artifactId")), properties);
            String version = expand(text(child(path, "version")), properties);
            if (!groupId.isBlank() && !artifactId.isBlank() && !version.isBlank()
                    && !version.contains("${")) {
                coordinates.add(new Coordinate(groupId, artifactId, version));
            }
        }
        return coordinates;
    }

    private static boolean hasProcessorPaths(Element project) {
        return !processorPaths(project).isEmpty();
    }

    private static List<Element> processorPaths(Element project) {
        List<Element> paths = new ArrayList<>();
        if (project == null) {
            return paths;
        }
        Element build = child(project, "build");
        List<Element> plugins = new ArrayList<>(children(child(build, "plugins"), "plugin"));
        plugins.addAll(children(child(child(build, "pluginManagement"), "plugins"), "plugin"));
        for (Element plugin : plugins) {
            if (!"maven-compiler-plugin".equals(text(child(plugin, "artifactId")))) {
                continue;
            }
            Element configured = child(child(plugin, "configuration"), "annotationProcessorPaths");
            paths.addAll(children(configured, "path"));
            paths.addAll(children(configured, "annotationProcessorPath"));
            if (!paths.isEmpty()) {
                return paths;
            }
        }
        return paths;
    }

    private static void collectProperties(Element project, Map<String, String> properties) {
        if (project == null) {
            return;
        }
        String version = text(child(project, "version"));
        if (version.isBlank()) {
            version = text(child(child(project, "parent"), "version"));
        }
        if (!version.isBlank()) {
            properties.put("project.version", version);
        }
        Element declared = child(project, "properties");
        if (declared == null) {
            return;
        }
        NodeList nodes = declared.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                properties.put(element.getTagName(), text(element));
            }
        }
    }

    private static String expand(String value, Map<String, String> properties) {
        String current = value;
        for (int depth = 0; depth < 5 && current.contains("${"); depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder expanded = new StringBuilder();
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(
                        replacement == null ? matcher.group() : replacement));
            }
            matcher.appendTail(expanded);
            if (expanded.toString().equals(current)) {
                break;
            }
            current = expanded.toString();
        }
        return current;
    }

    private static Element parse(Path pom) {
        if (pom == null || !Files.isRegularFile(pom)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            return document.getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }
}
