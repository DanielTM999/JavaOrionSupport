package dtm.ide.build;

import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildDiagnosticParser {

    private static final Pattern MAVEN = Pattern.compile(
            "^\\[(ERROR|WARNING|INFO)\\]\\s+(.+?):\\[(\\d+),(\\d+)\\]\\s*(.*)$");

    private static final Pattern JAVAC = Pattern.compile(
            "^(.+?\\.java):(\\d+):\\s*(error|warning|note):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVAC_WITH_COLUMN = Pattern.compile(
            "^(.+?\\.java):(\\d+):(\\d+):\\s*(error|warning|note):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MAVEN_PLAIN = Pattern.compile("^\\[(ERROR|WARNING)\\]\\s+(.*)$");

    private static final Pattern DETAIL = Pattern.compile(
            "^(?:\\[(?:ERROR|WARNING)\\]\\s*)?\\s*"
                    + "(symbol|location|required|found|reason):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final Path projectRoot;
    private final Map<String, BuildDiagnostic> diagnostics = new LinkedHashMap<>();
    private String lastLocatedKey;

    public BuildDiagnosticParser(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public BuildDiagnostic accept(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String clean = stripAnsi(line).stripTrailing();

        BuildDiagnostic continued = appendDetail(clean);
        if (continued != null) {
            return continued;
        }

        BuildDiagnostic diagnostic = parseMaven(clean);
        if (diagnostic == null) {
            diagnostic = parseJavacWithColumn(clean);
        }
        if (diagnostic == null) {
            diagnostic = parseJavac(clean);
        }
        if (diagnostic == null) {
            diagnostic = parseMavenPlain(clean);
        }
        if (diagnostic == null) {
            return null;
        }
        String key = keyOf(diagnostic);
        BuildDiagnostic added = diagnostics.putIfAbsent(key, diagnostic) == null ? diagnostic : null;
        if (diagnostic.hasLocation()) {
            lastLocatedKey = key;
        }
        return added;
    }

    public List<BuildDiagnostic> diagnostics() {
        return List.copyOf(diagnostics.values());
    }

    public List<BuildDiagnostic> errors() {
        return diagnostics.values().stream().filter(BuildDiagnostic::isError).toList();
    }

    public boolean hasErrors() {
        return diagnostics.values().stream().anyMatch(BuildDiagnostic::isError);
    }

    public void reset() {
        diagnostics.clear();
        lastLocatedKey = null;
    }

    private BuildDiagnostic appendDetail(String line) {
        Matcher matcher = DETAIL.matcher(line);
        if (!matcher.matches() || lastLocatedKey == null) {
            return null;
        }
        BuildDiagnostic previous = diagnostics.get(lastLocatedKey);
        if (previous == null) {
            return null;
        }
        String detail = matcher.group(1).toLowerCase(Locale.ROOT) + ": " + matcher.group(2).trim();
        if (previous.message().contains(detail)) {
            return null;
        }
        BuildDiagnostic merged = new BuildDiagnostic(previous.file(), previous.line(),
                previous.column(), previous.severity(), previous.message() + "\n" + detail,
                previous.source());
        diagnostics.put(lastLocatedKey, merged);
        return merged;
    }

    private BuildDiagnostic parseMaven(String line) {
        Matcher matcher = MAVEN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        DiagnosticSeverity severity = severityOf(matcher.group(1));
        if (severity == null) {
            return null;
        }
        return new BuildDiagnostic(
                resolve(matcher.group(2)),
                parseInt(matcher.group(3)),
                parseInt(matcher.group(4)),
                severity,
                matcher.group(5),
                "maven");
    }

    private BuildDiagnostic parseJavacWithColumn(String line) {
        Matcher matcher = JAVAC_WITH_COLUMN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new BuildDiagnostic(
                resolve(matcher.group(1)),
                parseInt(matcher.group(2)),
                parseInt(matcher.group(3)),
                severityOf(matcher.group(4)),
                matcher.group(5),
                "javac");
    }

    private BuildDiagnostic parseJavac(String line) {
        Matcher matcher = JAVAC.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new BuildDiagnostic(
                resolve(matcher.group(1)),
                parseInt(matcher.group(2)),
                0,
                severityOf(matcher.group(3)),
                matcher.group(4),
                "javac");
    }

    private BuildDiagnostic parseMavenPlain(String line) {
        Matcher matcher = MAVEN_PLAIN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String message = matcher.group(2).trim();
        if (message.isBlank() || isNoise(message)) {
            return null;
        }
        return new BuildDiagnostic(null, 0, 0, severityOf(matcher.group(1)), message, "maven");
    }

    private static boolean isNoise(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("-> [help")
                || lower.startsWith("re-run maven")
                || lower.startsWith("to see the full stack trace")
                || lower.startsWith("for more information about the errors")
                || lower.startsWith("please refer to")
                || message.chars().allMatch(c -> c == '-' || c == '=' || c == ' ');
    }

    private Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String value = rawPath.trim();
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() && projectRoot != null) {
                path = projectRoot.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static DiagnosticSeverity severityOf(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "error" -> DiagnosticSeverity.ERROR;
            case "warning" -> DiagnosticSeverity.WARNING;
            case "note", "info" -> DiagnosticSeverity.INFO;
            default -> null;
        };
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String keyOf(BuildDiagnostic diagnostic) {
        return diagnostic.file() + "|" + diagnostic.line() + "|" + diagnostic.column()
                + "|" + diagnostic.message();
    }

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[a-zA-Z]");

    static String stripAnsi(String line) {
        return line.indexOf(0x1B) < 0 ? line : ANSI.matcher(line).replaceAll("");
    }

    public static Map<Path, List<BuildDiagnostic>> byFile(List<BuildDiagnostic> diagnostics) {
        Map<Path, List<BuildDiagnostic>> grouped = new LinkedHashMap<>();
        for (BuildDiagnostic diagnostic : diagnostics) {
            if (diagnostic.hasLocation()) {
                grouped.computeIfAbsent(diagnostic.file(), key -> new ArrayList<>()).add(diagnostic);
            }
        }
        return grouped;
    }
}
