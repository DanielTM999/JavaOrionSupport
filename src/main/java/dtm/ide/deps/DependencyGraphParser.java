package dtm.ide.deps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DependencyGraphParser {

    private static final Pattern MAVEN_LINE = Pattern.compile(
            "^(?:\\[[A-Z]+]\\s*)?((?:\\|  |   )*)(?:\\+-|\\\\-)\\s+(.+)$");
    private static final Pattern GRADLE_LINE = Pattern.compile(
            "^((?:\\|    |     )*)(?:\\+---|\\\\---)\\s+(.+)$");
    private static final Pattern OMITTED_CONFLICT = Pattern.compile(
            "omitted for conflict with ([^)\\s]+)", Pattern.CASE_INSENSITIVE);

    private DependencyGraphParser() {
    }

    public static List<ResolvedDependency> parseMaven(List<String> lines) {
        List<Candidate> candidates = new ArrayList<>();
        for (String raw : safe(lines)) {
            Matcher matcher = MAVEN_LINE.matcher(stripAnsi(raw));
            if (!matcher.matches()) {
                continue;
            }
            String notation = matcher.group(2).trim();
            boolean conflict = notation.toLowerCase(Locale.ROOT).contains("omitted for conflict");
            String clean = notation.replaceFirst("\\s+\\(.*$", "").trim();
            String[] parts = clean.split(":");
            if (parts.length < 4) {
                continue;
            }
            String requested = parts.length >= 6 ? parts[4] : parts[3];
            String effective = requested;
            Matcher omitted = OMITTED_CONFLICT.matcher(notation);
            if (omitted.find()) {
                effective = omitted.group(1);
            }
            candidates.add(new Candidate(parts[0], parts[1], effective, requested,
                    matcher.group(1).length() / 3, conflict));
        }
        return resolvePaths(candidates);
    }

    public static List<ResolvedDependency> parseGradle(List<String> lines) {
        List<Candidate> candidates = new ArrayList<>();
        for (String raw : safe(lines)) {
            Matcher matcher = GRADLE_LINE.matcher(stripAnsi(raw));
            if (!matcher.matches()) {
                continue;
            }
            String value = matcher.group(2).replaceAll("\\s+\\([^)]*\\)\\s*$", "").trim();
            int arrow = value.indexOf(" -> ");
            String requestedNotation = arrow < 0 ? value : value.substring(0, arrow).trim();
            String[] parts = requestedNotation.split(":");
            if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) {
                continue;
            }
            String requested = parts[2].trim();
            String effective = arrow < 0 ? requested
                    : value.substring(arrow + 4).split("\\s+", 2)[0].trim();
            candidates.add(new Candidate(parts[0], parts[1], effective, requested,
                    matcher.group(1).length() / 5, arrow >= 0));
        }
        return resolvePaths(candidates);
    }

    private static List<ResolvedDependency> resolvePaths(List<Candidate> candidates) {
        Map<Integer, String> parents = new LinkedHashMap<>();
        Map<String, Set<String>> requestedVersions = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            requestedVersions.computeIfAbsent(candidate.key(), ignored -> new LinkedHashSet<>())
                    .add(candidate.requestedVersion());
        }
        List<ResolvedDependency> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            parents.entrySet().removeIf(entry -> entry.getKey() >= candidate.depth());
            List<String> path = new ArrayList<>();
            for (int depth = 0; depth < candidate.depth(); depth++) {
                String parent = parents.get(depth);
                if (parent != null) {
                    path.add(parent);
                }
            }
            path.add(candidate.key());
            boolean conflict = candidate.conflict()
                    || requestedVersions.getOrDefault(candidate.key(), Set.of()).size() > 1;
            result.add(new ResolvedDependency(new DependencyCoordinate(candidate.groupId(),
                    candidate.artifactId(), candidate.version(), DependencyCoordinate.SCOPE_COMPILE),
                    candidate.depth(), path, conflict, candidate.requestedVersion()));
            parents.put(candidate.depth(), candidate.key());
        }
        return List.copyOf(result);
    }

    private static List<String> safe(List<String> lines) {
        return lines == null ? List.of() : lines;
    }

    private static String stripAnsi(String value) {
        return value == null ? "" : value.replaceAll("\\x1B\\[[;\\d]*[a-zA-Z]", "");
    }

    private record Candidate(String groupId, String artifactId, String version,
                             String requestedVersion, int depth, boolean conflict) {
        String key() {
            return groupId + ":" + artifactId;
        }
    }
}
