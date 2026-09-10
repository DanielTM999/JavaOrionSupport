package dtm.ide.deps;

import dtm.ide.project.MavenPom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PomEditor {

    private static final Pattern DEPENDENCIES_BLOCK = Pattern.compile(
            "(?s)<dependencies\\s*>(.*?)</dependencies\\s*>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile(
            "(?s)<dependency\\s*>(.*?)</dependency\\s*>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("</project\\s*>\\s*$");
    private static final Pattern PROPERTIES_BLOCK = Pattern.compile(
            "(?s)<properties\\s*>(.*?)</properties\\s*>");
    private static final Pattern DEPENDENCY_MANAGEMENT_BLOCK = Pattern.compile(
            "(?s)<dependencyManagement\\s*>(.*?)</dependencyManagement\\s*>");

    private static final List<String> FOREIGN_SECTIONS =
            List.of("dependencyManagement", "build", "reporting", "profiles");

    private PomEditor() {
    }

    public static List<DependencyCoordinate> readDependencies(String pomXml) {
        MavenPom pom = MavenPom.parseContent(pomXml);
        if (!pom.isValid()) {
            return List.of();
        }
        List<DependencyCoordinate> dependencies = new ArrayList<>();
        for (String block : dependencyBlocks(pomXml)) {
            DependencyCoordinate coordinate = parseBlock(block);
            if (coordinate != null && coordinate.isValid()) {
                dependencies.add(coordinate);
            }
        }
        return dependencies;
    }

    public static boolean contains(String pomXml, DependencyCoordinate coordinate) {
        return coordinate != null && readDependencies(pomXml).stream()
                .anyMatch(existing -> existing.sameArtifact(coordinate));
    }

    public static boolean hasProperty(String pomXml, String name) {
        return locateProperty(pomXml, name).isPresent();
    }

    public static String setProperty(String pomXml, String name, String newValue) {
        if (pomXml == null || name == null || name.isBlank()
                || newValue == null || newValue.isBlank()) {
            return pomXml;
        }
        Optional<int[]> bounds = locateProperty(pomXml, name);
        if (bounds.isEmpty()) {
            return pomXml;
        }
        return pomXml.substring(0, bounds.get()[0]) + newValue
                + pomXml.substring(bounds.get()[1]);
    }

    public static String managedVersion(String pomXml, DependencyCoordinate coordinate) {
        return locateManagedDependency(pomXml, coordinate)
                .map(bounds -> firstGroup(pomXml.substring(bounds[0], bounds[1]), "version"))
                .orElse("");
    }

    public static String setManagedVersion(String pomXml, DependencyCoordinate coordinate,
                                           String newVersion) {
        if (pomXml == null || coordinate == null || newVersion == null || newVersion.isBlank()) {
            return pomXml;
        }
        Optional<int[]> bounds = locateManagedDependency(pomXml, coordinate);
        if (bounds.isEmpty()) {
            return pomXml;
        }
        int start = bounds.get()[0];
        int end = bounds.get()[1];
        String block = pomXml.substring(start, end);
        Matcher version = tagPattern("version").matcher(block);
        if (!version.find()) {
            return pomXml;
        }
        String updated = block.substring(0, version.start(1)) + newVersion
                + block.substring(version.end(1));
        return pomXml.substring(0, start) + updated + pomXml.substring(end);
    }

    public static String addDependency(String pomXml, DependencyCoordinate coordinate) {
        if (pomXml == null || coordinate == null || !coordinate.isValid()) {
            return pomXml;
        }
        if (contains(pomXml, coordinate)) {
            return coordinate.hasVersion()
                    ? setVersion(pomXml, coordinate, coordinate.version())
                    : pomXml;
        }

        Optional<int[]> bounds = locateProjectDependencies(pomXml);
        if (bounds.isEmpty()) {
            return appendDependenciesBlock(pomXml, coordinate);
        }
        int contentStart = bounds.get()[0];
        int contentEnd = bounds.get()[1];
        String body = pomXml.substring(contentStart, contentEnd);

        int insertAt = contentEnd;
        int lastLineBreak = body.lastIndexOf('\n');
        if (lastLineBreak >= 0 && body.substring(lastLineBreak + 1).isBlank()) {
            insertAt = contentStart + lastLineBreak + 1;
        }
        String entry = renderDependency(coordinate, detectDependencyIndent(body));
        return pomXml.substring(0, insertAt) + entry + pomXml.substring(insertAt);
    }

    public static String removeDependency(String pomXml, DependencyCoordinate coordinate) {
        if (pomXml == null || coordinate == null) {
            return pomXml;
        }
        Optional<int[]> bounds = locateDependency(pomXml, coordinate);
        if (bounds.isEmpty()) {
            return pomXml;
        }
        int start = bounds.get()[0];
        int end = bounds.get()[1];

        int lineStart = pomXml.lastIndexOf('\n', start);
        start = lineStart < 0 ? start : lineStart + 1;
        int lineEnd = pomXml.indexOf('\n', end);
        end = lineEnd < 0 ? end : lineEnd + 1;

        return pomXml.substring(0, start) + pomXml.substring(end);
    }

    public static String setVersion(String pomXml, DependencyCoordinate coordinate, String newVersion) {
        if (pomXml == null || coordinate == null || newVersion == null || newVersion.isBlank()) {
            return pomXml;
        }
        Optional<int[]> bounds = locateDependency(pomXml, coordinate);
        if (bounds.isEmpty()) {
            return pomXml;
        }
        int start = bounds.get()[0];
        int end = bounds.get()[1];
        String block = pomXml.substring(start, end);

        Matcher version = tagPattern("version").matcher(block);
        String updated;
        if (version.find()) {
            updated = block.substring(0, version.start(1)) + newVersion + block.substring(version.end(1));
        } else {
            Matcher artifact = tagPattern("artifactId").matcher(block);
            if (!artifact.find()) {
                return pomXml;
            }
            String indent = detectInnerIndent(block);
            int insertAt = block.indexOf('\n', artifact.end());
            if (insertAt < 0) {
                return pomXml;
            }
            updated = block.substring(0, insertAt + 1)
                    + indent + "<version>" + newVersion + "</version>\n"
                    + block.substring(insertAt + 1);
        }
        return pomXml.substring(0, start) + updated + pomXml.substring(end);
    }

    private static List<String> dependencyBlocks(String pomXml) {
        Optional<int[]> bounds = locateProjectDependencies(pomXml);
        if (bounds.isEmpty()) {
            return List.of();
        }
        String body = pomXml.substring(bounds.get()[0], bounds.get()[1]);
        List<String> blocks = new ArrayList<>();
        Matcher dependency = DEPENDENCY_BLOCK.matcher(body);
        while (dependency.find()) {
            blocks.add(dependency.group(1));
        }
        return blocks;
    }

    private static Optional<int[]> locateProjectDependencies(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) {
            return Optional.empty();
        }
        List<int[]> foreign = foreignSectionRanges(pomXml);
        Matcher dependencies = DEPENDENCIES_BLOCK.matcher(pomXml);
        while (dependencies.find()) {
            int start = dependencies.start();
            boolean nested = foreign.stream()
                    .anyMatch(range -> start > range[0] && start < range[1]);
            if (!nested) {
                return Optional.of(new int[]{dependencies.start(1), dependencies.end(1)});
            }
        }
        return Optional.empty();
    }

    private static List<int[]> foreignSectionRanges(String pomXml) {
        List<int[]> ranges = new ArrayList<>();
        for (String section : FOREIGN_SECTIONS) {
            Matcher matcher = Pattern.compile("(?s)<" + section + "\\s*>.*?</" + section + "\\s*>")
                    .matcher(pomXml);
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return ranges;
    }

    private static DependencyCoordinate parseBlock(String block) {
        String groupId = firstGroup(block, "groupId");
        String artifactId = firstGroup(block, "artifactId");
        if (groupId.isBlank() || artifactId.isBlank()) {
            return null;
        }
        return new DependencyCoordinate(groupId, artifactId,
                firstGroup(block, "version"), firstGroup(block, "scope"));
    }

    private static Optional<int[]> locateDependency(String pomXml, DependencyCoordinate coordinate) {
        Optional<int[]> bounds = locateProjectDependencies(pomXml);
        if (bounds.isEmpty()) {
            return Optional.empty();
        }
        int offset = bounds.get()[0];
        Matcher dependency = DEPENDENCY_BLOCK.matcher(pomXml.substring(offset, bounds.get()[1]));

        while (dependency.find()) {
            DependencyCoordinate parsed = parseBlock(dependency.group(1));
            if (parsed != null && parsed.sameArtifact(coordinate)) {
                return Optional.of(new int[]{offset + dependency.start(), offset + dependency.end()});
            }
        }
        return Optional.empty();
    }

    private static Optional<int[]> locateProperty(String pomXml, String name) {
        if (pomXml == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Matcher properties = PROPERTIES_BLOCK.matcher(pomXml);
        if (!properties.find()) {
            return Optional.empty();
        }
        Pattern property = Pattern.compile("(?s)<" + Pattern.quote(name)
                + "\\s*>(.*?)</" + Pattern.quote(name) + "\\s*>");
        Matcher value = property.matcher(properties.group(1));
        if (!value.find()) {
            return Optional.empty();
        }
        int offset = properties.start(1);
        return Optional.of(new int[]{offset + value.start(1), offset + value.end(1)});
    }

    private static Optional<int[]> locateManagedDependency(
            String pomXml, DependencyCoordinate coordinate) {
        if (pomXml == null || coordinate == null) {
            return Optional.empty();
        }
        Matcher management = DEPENDENCY_MANAGEMENT_BLOCK.matcher(pomXml);
        while (management.find()) {
            int managementOffset = management.start(1);
            Matcher dependencies = DEPENDENCIES_BLOCK.matcher(management.group(1));
            if (!dependencies.find()) {
                continue;
            }
            int dependenciesOffset = managementOffset + dependencies.start(1);
            Matcher dependency = DEPENDENCY_BLOCK.matcher(dependencies.group(1));
            while (dependency.find()) {
                DependencyCoordinate parsed = parseBlock(dependency.group(1));
                if (parsed != null && parsed.sameArtifact(coordinate)) {
                    return Optional.of(new int[]{dependenciesOffset + dependency.start(),
                            dependenciesOffset + dependency.end()});
                }
            }
        }
        return Optional.empty();
    }

    private static String appendDependenciesBlock(String pomXml, DependencyCoordinate coordinate) {
        Matcher close = PROJECT_CLOSE.matcher(pomXml);
        String indent = "    ";
        String block = "\n" + indent + "<dependencies>\n"
                + renderDependency(coordinate, indent + indent)
                + indent + "</dependencies>\n\n";

        if (close.find()) {
            return pomXml.substring(0, close.start()) + block + pomXml.substring(close.start());
        }
        return pomXml + block;
    }

    private static String renderDependency(DependencyCoordinate coordinate, String indent) {
        String inner = indent + "    ";
        StringBuilder entry = new StringBuilder();
        entry.append(indent).append("<dependency>\n");
        entry.append(inner).append("<groupId>").append(coordinate.groupId()).append("</groupId>\n");
        entry.append(inner).append("<artifactId>").append(coordinate.artifactId())
                .append("</artifactId>\n");
        if (coordinate.hasVersion()) {
            entry.append(inner).append("<version>").append(coordinate.version()).append("</version>\n");
        }
        if (!DependencyCoordinate.SCOPE_COMPILE.equals(coordinate.scope())) {
            entry.append(inner).append("<scope>").append(coordinate.scope()).append("</scope>\n");
        }
        entry.append(indent).append("</dependency>\n");
        return entry.toString();
    }

    private static String detectDependencyIndent(String dependenciesBody) {
        Matcher matcher = Pattern.compile("(?m)^([ \\t]+)<dependency\\s*>").matcher(dependenciesBody);
        return matcher.find() ? matcher.group(1) : "        ";
    }

    private static String detectInnerIndent(String dependencyBlock) {
        Matcher matcher = Pattern.compile("(?m)^([ \\t]+)<artifactId\\s*>").matcher(dependencyBlock);
        return matcher.find() ? matcher.group(1) : "            ";
    }

    private static String firstGroup(String xml, String tag) {
        Matcher matcher = tagPattern(tag).matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Pattern tagPattern(String tag) {
        return Pattern.compile("(?s)<" + tag + "\\s*>(.*?)</" + tag + "\\s*>");
    }
}
