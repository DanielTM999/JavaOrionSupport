package dtm.ide.deps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PomProperties {

    public record Placeholder(String name, int start, int end) {
    }

    public record Declaration(String name, String value, Path file, int line, int col) {

        public boolean navigable() {
            return file != null && line >= 0;
        }
    }

    public record ParentReference(String groupId, String artifactId, String version,
                                  Path file, int line, int col) {
    }

    private static final int MAX_CHAIN = 12;
    private static final int MAX_EXPANSION_DEPTH = 8;
    private static final Pattern TAG = Pattern.compile(
            "<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<!.*?>|<(/?)([A-Za-z_][\\w.\\-:]*)[^>]*?(/?)>",
            Pattern.DOTALL);
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final List<String> BUILT_INS = List.of(
            "project.basedir", "basedir", "project.baseUri", "project.build.directory",
            "project.build.outputDirectory", "project.build.testOutputDirectory",
            "project.build.sourceDirectory", "project.build.finalName", "project.name",
            "project.packaging", "maven.build.timestamp", "java.version", "java.home",
            "user.home", "user.dir", "os.name");

    private final Supplier<Path> localRepository;
    private final Map<Path, CachedScan> cache = new ConcurrentHashMap<>();

    public PomProperties(Supplier<Path> localRepository) {
        this.localRepository = localRepository == null ? () -> null : localRepository;
    }

    public static Optional<Placeholder> placeholderAt(String text, int offset) {
        if (text == null || offset < 0 || offset > text.length()) {
            return Optional.empty();
        }
        int open = -1;
        if (offset + 1 < text.length() && text.charAt(offset) == '$' && text.charAt(offset + 1) == '{') {
            open = offset;
        }
        for (int index = Math.min(offset, text.length() - 1); open < 0 && index >= 0; index--) {
            char current = text.charAt(index);
            if (current == '{' && index > 0 && text.charAt(index - 1) == '$') {
                open = index - 1;
                break;
            }
            if ((current == '}' && index < offset) || current == '<' || current == '>'
                    || current == '\n' || current == '\r') {
                return Optional.empty();
            }
        }
        if (open < 0) {
            return Optional.empty();
        }
        int close = text.indexOf('}', open + 2);
        int lineEnd = lineEnd(text, open);
        if (close < 0 || close > lineEnd || offset > close) {
            return Optional.empty();
        }
        String name = text.substring(open + 2, close).trim();
        if (name.isEmpty() || name.indexOf('<') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new Placeholder(name, open, close + 1));
    }

    public List<Declaration> declarations(Path pom, String text) {
        Map<String, Declaration> merged = new LinkedHashMap<>();
        List<Scan> chain = chain(pom, text);
        for (Scan scan : chain) {
            scan.properties().forEach(declaration -> merged.putIfAbsent(declaration.name(), declaration));
        }
        if (!chain.isEmpty()) {
            Scan own = chain.getFirst();
            putProjectField(merged, own, "groupId");
            putProjectField(merged, own, "artifactId");
            putProjectField(merged, own, "version");
            putProjectField(merged, own, "name");
            putProjectField(merged, own, "packaging");
            putParentField(merged, own, "groupId");
            putParentField(merged, own, "artifactId");
            putParentField(merged, own, "version");
        }
        for (String builtIn : BUILT_INS) {
            merged.putIfAbsent(builtIn, new Declaration(builtIn, "", null, -1, -1));
        }
        return List.copyOf(merged.values());
    }

    public Optional<Declaration> find(Path pom, String text, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = normalizeAlias(name.trim());
        return declarations(pom, text).stream().filter(declaration -> declaration.name().equals(key))
                .findFirst();
    }

    public String resolve(String value, List<Declaration> declarations) {
        if (value == null || value.indexOf("${") < 0) {
            return value == null ? "" : value;
        }
        Map<String, String> values = new LinkedHashMap<>();
        declarations.forEach(declaration -> values.putIfAbsent(declaration.name(), declaration.value()));
        String current = value;
        for (int depth = 0; depth < MAX_EXPANSION_DEPTH && current.indexOf("${") >= 0; depth++) {
            Matcher matcher = REFERENCE.matcher(current);
            StringBuilder expanded = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = values.get(normalizeAlias(matcher.group(1).trim()));
                if (replacement == null || replacement.isEmpty()) {
                    matcher.appendReplacement(expanded, Matcher.quoteReplacement(matcher.group()));
                } else {
                    matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
                    changed = true;
                }
            }
            matcher.appendTail(expanded);
            current = expanded.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    public Optional<ParentReference> parentAt(Path pom, String text, int offset) {
        Scan scan = scan(pom, text);
        ParentReference parent = scan.parent();
        if (parent == null || offset < scan.parentStart() || offset > scan.parentEnd()) {
            return Optional.empty();
        }
        Path file = parentPom(pom, scan);
        if (file == null) {
            return Optional.empty();
        }
        Scan parentScan = cachedScan(file);
        int[] position = parentScan == null ? new int[]{0, 0} : parentScan.projectPosition();
        return Optional.of(new ParentReference(parent.groupId(), parent.artifactId(), parent.version(),
                file, position[0], position[1]));
    }

    private static void putProjectField(Map<String, Declaration> merged, Scan own, String field) {
        String key = "project." + field;
        Declaration declaration = own.projectFields().get(field);
        if (declaration == null && (field.equals("groupId") || field.equals("version"))) {
            declaration = own.parentFields().get(field);
        }
        if (declaration != null) {
            merged.putIfAbsent(key, new Declaration(key, declaration.value(), declaration.file(),
                    declaration.line(), declaration.col()));
        }
    }

    private static void putParentField(Map<String, Declaration> merged, Scan own, String field) {
        String key = "project.parent." + field;
        Declaration declaration = own.parentFields().get(field);
        if (declaration != null) {
            merged.putIfAbsent(key, new Declaration(key, declaration.value(), declaration.file(),
                    declaration.line(), declaration.col()));
        }
    }

    private static String normalizeAlias(String name) {
        if (name.startsWith("pom.")) {
            return "project." + name.substring(4);
        }
        return switch (name) {
            case "version", "groupId", "artifactId" -> "project." + name;
            default -> name;
        };
    }

    private List<Scan> chain(Path pom, String text) {
        List<Scan> result = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Scan current = scan(pom, text);
        Path currentPath = normalize(pom);
        while (current != null && result.size() < MAX_CHAIN) {
            result.add(current);
            if (currentPath != null && !visited.add(currentPath)) {
                break;
            }
            Path parent = parentPom(currentPath, current);
            if (parent == null || visited.contains(parent)) {
                break;
            }
            current = cachedScan(parent);
            currentPath = parent;
        }
        return result;
    }

    private Path parentPom(Path pom, Scan scan) {
        ParentReference parent = scan.parent();
        if (parent == null || parent.artifactId().isBlank()) {
            return null;
        }
        if (pom != null && scan.relativePath() != null && !scan.relativePath().isBlank()) {
            Path directory = pom.toAbsolutePath().normalize().getParent();
            if (directory != null) {
                Path candidate = directory.resolve(scan.relativePath().trim()).normalize();
                if (Files.isDirectory(candidate)) {
                    candidate = candidate.resolve("pom.xml");
                }
                if (Files.isRegularFile(candidate) && matches(cachedScan(candidate), parent)) {
                    return candidate;
                }
            }
        }
        return repositoryPom(parent);
    }

    private static boolean matches(Scan candidate, ParentReference parent) {
        if (candidate == null) {
            return false;
        }
        Declaration artifact = candidate.projectFields().get("artifactId");
        return artifact != null && artifact.value().equals(parent.artifactId());
    }

    private Path repositoryPom(ParentReference parent) {
        Path repository = localRepository.get();
        if (repository == null || parent.groupId().isBlank() || parent.version().isBlank()
                || parent.version().contains("${")) {
            return null;
        }
        Path directory = repository;
        for (String segment : parent.groupId().split("\\.")) {
            directory = directory.resolve(segment);
        }
        Path file = directory.resolve(parent.artifactId()).resolve(parent.version())
                .resolve(parent.artifactId() + "-" + parent.version() + ".pom");
        return Files.isRegularFile(file) ? file.toAbsolutePath().normalize() : null;
    }

    private Scan cachedScan(Path file) {
        Path normalized = normalize(file);
        if (normalized == null) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(normalized).toMillis();
            long size = Files.size(normalized);
            CachedScan cached = cache.get(normalized);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.scan();
            }
            Scan scan = scan(normalized, Files.readString(normalized));
            cache.put(normalized, new CachedScan(modified, size, scan));
            return scan;
        } catch (Exception e) {
            return null;
        }
    }

    static Scan scan(Path file, String text) {
        String content = text == null ? "" : text;
        Path normalized = normalize(file);
        LineIndex lines = new LineIndex(content);
        List<Declaration> properties = new ArrayList<>();
        Map<String, Declaration> projectFields = new LinkedHashMap<>();
        Map<String, Declaration> parentFields = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Deque<int[]> openings = new ArrayDeque<>();
        String relativePath = "../pom.xml";
        int parentStart = -1;
        int parentEnd = -1;
        int projectOffset = 0;
        Matcher matcher = TAG.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (name == null) {
                continue;
            }
            boolean closing = !matcher.group(1).isEmpty();
            boolean selfClosing = !matcher.group(3).isEmpty();
            if (!closing) {
                if (stack.isEmpty() && name.equals("project")) {
                    projectOffset = matcher.start() + 1;
                }
                if (stack.size() == 1 && name.equals("parent")) {
                    parentStart = matcher.start();
                }
                if (selfClosing) {
                    if (stack.size() == 2 && isParent(stack) && name.equals("relativePath")) {
                        relativePath = "";
                    }
                    continue;
                }
                stack.push(name);
                openings.push(new int[]{matcher.start() + 1, matcher.end()});
                continue;
            }
            if (stack.isEmpty() || !stack.peek().equals(name)) {
                continue;
            }
            int[] opening = openings.pop();
            stack.pop();
            String value = content.substring(opening[1], matcher.start()).trim();
            int[] position = lines.position(opening[0]);
            Declaration declaration = new Declaration(name, value, normalized, position[0], position[1]);
            int depth = stack.size();
            if (depth == 2 && isChildOf(stack, "properties")) {
                properties.add(declaration);
            } else if (depth == 1 && stack.peek().equals("project")) {
                projectFields.putIfAbsent(name, declaration);
                if (name.equals("parent")) {
                    parentEnd = matcher.end();
                }
            } else if (depth == 2 && isParent(stack)) {
                if (name.equals("relativePath")) {
                    relativePath = value;
                } else {
                    parentFields.putIfAbsent(name, declaration);
                }
            }
        }
        ParentReference parent = parentFields.containsKey("artifactId")
                ? new ParentReference(valueOf(parentFields, "groupId"), valueOf(parentFields, "artifactId"),
                valueOf(parentFields, "version"), null, -1, -1)
                : null;
        return new Scan(List.copyOf(properties), Map.copyOf(projectFields), Map.copyOf(parentFields),
                parent, relativePath, parentStart, parentEnd, lines.position(projectOffset));
    }

    private static boolean isParent(Deque<String> stack) {
        return isChildOf(stack, "parent");
    }

    private static boolean isChildOf(Deque<String> stack, String section) {
        if (stack.size() != 2) {
            return false;
        }
        var iterator = stack.iterator();
        String inner = iterator.next();
        String outer = iterator.next();
        return inner.equals(section) && outer.equals("project");
    }

    private static String valueOf(Map<String, Declaration> fields, String name) {
        Declaration declaration = fields.get(name);
        return declaration == null ? "" : declaration.value();
    }

    private static int lineEnd(String text, int from) {
        for (int index = from; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
        }
        return text.length();
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    record Scan(List<Declaration> properties, Map<String, Declaration> projectFields,
                Map<String, Declaration> parentFields, ParentReference parent, String relativePath,
                int parentStart, int parentEnd, int[] projectPosition) {
    }

    private record CachedScan(long modified, long size, Scan scan) {
    }

    private static final class LineIndex {
        private final int[] starts;

        private LineIndex(String text) {
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (current == '\n') {
                    offsets.add(index + 1);
                } else if (current == '\r' && (index + 1 >= text.length() || text.charAt(index + 1) != '\n')) {
                    offsets.add(index + 1);
                }
            }
            starts = offsets.stream().mapToInt(Integer::intValue).toArray();
        }

        private int[] position(int offset) {
            int low = 0;
            int high = starts.length - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (starts[middle] <= offset) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return new int[]{low, offset - starts[low]};
        }
    }
}
