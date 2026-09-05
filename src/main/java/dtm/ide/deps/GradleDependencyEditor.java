package dtm.ide.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleDependencyEditor {

    private static final String DEFAULT_CONFIGURATION = "implementation";

    private static final Map<String, String> SCOPE_TO_CONFIGURATION = Map.of(
            "compile", "implementation",
            "provided", "compileOnly",
            "runtime", "runtimeOnly",
            "test", "testImplementation");

    private static final Pattern DEPENDENCIES_BLOCK = Pattern.compile(
            "(?m)^([ \\t]*)dependencies\\s*\\{");

    private static final Pattern DEPENDENCY_LINE = Pattern.compile(
            "(?m)^[ \\t]*([A-Za-z][A-Za-z0-9_]*)[ \\t]*\\(?[ \\t]*"
                    + "[\"']([^\"':]+):([^\"':]+)(?::([^\"']+))?[\"'][ \\t]*\\)?");

    private GradleDependencyEditor() {
    }

    public static List<DependencyCoordinate> readDependencies(String script) {
        Optional<int[]> block = locateDependenciesBlock(script);
        if (block.isEmpty()) {
            return List.of();
        }
        String body = script.substring(block.get()[0], block.get()[1]);
        List<DependencyCoordinate> dependencies = new ArrayList<>();

        Matcher matcher = DEPENDENCY_LINE.matcher(stripComments(body));
        while (matcher.find()) {
            String configuration = matcher.group(1);
            DependencyCoordinate coordinate = new DependencyCoordinate(
                    matcher.group(2), matcher.group(3),
                    matcher.group(4) == null ? "" : matcher.group(4),
                    configuration);
            if (coordinate.isValid()) {
                dependencies.add(coordinate);
            }
        }
        return dependencies;
    }

    public static boolean contains(String script, DependencyCoordinate coordinate) {
        return coordinate != null && readDependencies(script).stream()
                .anyMatch(existing -> existing.sameArtifact(coordinate));
    }

    public static String addDependency(String script, DependencyCoordinate coordinate) {
        if (script == null || coordinate == null || !coordinate.isValid()) {
            return script;
        }
        if (contains(script, coordinate)) {
            return replaceDependency(script, coordinate);
        }
        boolean kotlin = isKotlinDsl(script);
        Optional<int[]> block = locateDependenciesBlock(script);

        if (block.isEmpty()) {
            String entry = renderEntry(coordinate, "    ", kotlin);
            String separator = script.endsWith("\n") ? "" : "\n";
            return script + separator + "\ndependencies {\n" + entry + "}\n";
        }

        int contentEnd = block.get()[1];
        String indent = detectEntryIndent(script.substring(block.get()[0], contentEnd));
        int insertAt = contentEnd;
        String body = script.substring(block.get()[0], contentEnd);
        int lastLineBreak = body.lastIndexOf('\n');
        if (lastLineBreak >= 0 && body.substring(lastLineBreak + 1).isBlank()) {
            insertAt = block.get()[0] + lastLineBreak + 1;
        }
        return script.substring(0, insertAt)
                + renderEntry(coordinate, indent, kotlin)
                + script.substring(insertAt);
    }

    public static String removeDependency(String script, DependencyCoordinate coordinate) {
        return locateDependencyLine(script, coordinate)
                .map(bounds -> script.substring(0, bounds[0]) + script.substring(bounds[1]))
                .orElse(script);
    }

    public static String setVersion(String script, DependencyCoordinate coordinate, String newVersion) {
        if (newVersion == null || newVersion.isBlank()) {
            return script;
        }
        return readDependencies(script).stream()
                .filter(existing -> existing.sameArtifact(coordinate))
                .findFirst()
                .map(existing -> replaceDependency(script, existing.withVersion(newVersion)))
                .orElse(script);
    }

    private static String replaceDependency(String script, DependencyCoordinate coordinate) {
        Optional<int[]> bounds = locateDependencyLine(script, coordinate);
        if (bounds.isEmpty()) {
            return script;
        }
        String line = script.substring(bounds.get()[0], bounds.get()[1]);
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        DependencyCoordinate merged = coordinate.scope().equals(DependencyCoordinate.SCOPE_COMPILE)
                ? coordinate.withScope(configurationOf(line))
                : coordinate;
        return script.substring(0, bounds.get()[0])
                + renderEntry(merged, indent.replace("\n", ""), isKotlinDsl(script))
                + script.substring(bounds.get()[1]);
    }

    private static Optional<int[]> locateDependenciesBlock(String script) {
        if (script == null || script.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = DEPENDENCIES_BLOCK.matcher(script);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int contentStart = matcher.end();
        int depth = 1;
        for (int i = contentStart; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(new int[]{contentStart, i});
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<int[]> locateDependencyLine(String script, DependencyCoordinate coordinate) {
        Optional<int[]> block = locateDependenciesBlock(script);
        if (block.isEmpty() || coordinate == null) {
            return Optional.empty();
        }
        int offset = block.get()[0];
        Matcher matcher = DEPENDENCY_LINE.matcher(script.substring(offset, block.get()[1]));

        while (matcher.find()) {
            if (matcher.group(2).equals(coordinate.groupId())
                    && matcher.group(3).equals(coordinate.artifactId())) {
                int start = offset + matcher.start();
                int lineStart = script.lastIndexOf('\n', start);
                start = lineStart < 0 ? start : lineStart + 1;
                int end = script.indexOf('\n', offset + matcher.end());
                end = end < 0 ? offset + matcher.end() : end + 1;
                return Optional.of(new int[]{start, end});
            }
        }
        return Optional.empty();
    }

    private static String renderEntry(DependencyCoordinate coordinate, String indent, boolean kotlin) {
        String configuration = configurationFor(coordinate.scope());
        String notation = coordinate.notation();
        return kotlin
                ? indent + configuration + "(\"" + notation + "\")\n"
                : indent + configuration + " '" + notation + "'\n";
    }

    static boolean isKotlinDsl(String script) {
        if (script == null) {
            return false;
        }
        return Pattern.compile("(?m)^[ \\t]*[A-Za-z][A-Za-z0-9_]*\\s*\\(\\s*[\"']")
                .matcher(script).find();
    }

    private static String configurationFor(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT_CONFIGURATION;
        }
        String normalized = scope.toLowerCase(Locale.ROOT);
        String mapped = SCOPE_TO_CONFIGURATION.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        return scope;
    }

    private static String configurationOf(String line) {
        Matcher matcher = DEPENDENCY_LINE.matcher(line);
        return matcher.find() ? matcher.group(1) : DEFAULT_CONFIGURATION;
    }

    private static String detectEntryIndent(String body) {
        Matcher matcher = Pattern.compile("(?m)^([ \\t]+)[A-Za-z]").matcher(body);
        return matcher.find() ? matcher.group(1) : "    ";
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }
}
