package dtm.ide.todo;

import dtm.ide.editor.JavaSourceText;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TodoScanner {

    public static final List<String> DEFAULT_MARKERS = List.of("TODO", "FIXME", "XXX", "HACK");

    private static final int MAX_FILES = 20_000;

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:@\\w+\\s+)*(?:public|protected|private|static|final|abstract|default|synchronized|native|sealed|non-sealed)"
                    + "[\\w\\s<>,\\[\\]$.?]*?\\b([A-Za-z_$][\\w$]*)\\s*(?:\\(|\\{|<)");

    private final Map<Path, List<TodoItem>> byFile = new ConcurrentHashMap<>();
    private volatile Pattern markerPattern = compile(DEFAULT_MARKERS);
    private volatile List<String> markers = DEFAULT_MARKERS;

    public void setMarkers(Collection<String> values) {
        List<String> effective = normalize(values);
        if (effective.equals(markers)) {
            return;
        }
        markers = effective;
        markerPattern = compile(effective);
        byFile.clear();
    }

    public List<String> markers() {
        return markers;
    }

    public List<TodoItem> scan(JavaProjectDescriptor descriptor) {
        byFile.clear();
        if (descriptor == null) {
            return List.of();
        }
        int visited = 0;
        for (JavaModule module : descriptor.modules()) {
            List<Path> roots = new ArrayList<>(module.existingSourceRoots());
            roots.addAll(module.existingTestRoots());
            for (Path root : roots) {
                visited = scanRoot(root, visited);
                if (visited > MAX_FILES) {
                    return items();
                }
            }
        }
        return items();
    }

    private int scanRoot(Path root, int visited) {
        int remaining = MAX_FILES - visited;
        if (remaining <= 0) {
            return MAX_FILES + 1;
        }
        for (Path file : JavaProjectConventions.javaSources(root, 0, remaining)) {
            visited++;
            index(file, JavaProjectConventions.readOrEmpty(file));
        }
        return visited;
    }

    public boolean refreshFile(Path file, String source) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return false;
        }
        List<TodoItem> previous = byFile.get(file);
        List<TodoItem> found = index(file, source);
        return !found.equals(previous == null ? List.of() : previous);
    }

    public boolean forget(Path file) {
        return file != null && byFile.remove(file) != null;
    }

    public void clear() {
        byFile.clear();
    }

    public List<TodoItem> items() {
        return byFile.values().stream().flatMap(List::stream).sorted().toList();
    }

    public List<TodoItem> itemsOf(Path file) {
        return byFile.getOrDefault(file, List.of());
    }

    private List<TodoItem> index(Path file, String source) {
        List<TodoItem> found = parse(file, source);
        if (found.isEmpty()) {
            byFile.remove(file);
        } else {
            byFile.put(file, found);
        }
        return found;
    }

    public List<TodoItem> parse(Path file, String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String withoutStrings = JavaSourceText.blankStringContents(source);
        String withoutComments = JavaSourceText.blankComments(withoutStrings);
        Matcher matcher = markerPattern.matcher(withoutStrings);

        List<TodoItem> found = new ArrayList<>();
        int[] lineStarts = lineStarts(source);
        while (matcher.find()) {
            int start = matcher.start();
            if (start < withoutComments.length()
                    && withoutComments.charAt(start) == withoutStrings.charAt(start)) {
                continue;
            }
            int line = lineOf(lineStarts, start);
            int column = start - lineStarts[line];
            String message = messageOf(source, matcher.end(), lineStarts, line);
            found.add(new TodoItem(matcher.group(1).toUpperCase(Locale.ROOT), message,
                    file, line, column, contextOf(source, lineStarts, line)));
        }
        return List.copyOf(found);
    }

    private static String messageOf(String source, int markerEnd, int[] lineStarts, int line) {
        int lineEnd = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : source.length();
        if (markerEnd >= lineEnd) {
            return "";
        }
        String tail = source.substring(markerEnd, lineEnd);
        return tail.replaceFirst("^[\\s:,\\-]+", "").replaceFirst("\\s*\\*/\\s*$", "").trim();
    }

    private static String contextOf(String source, int[] lineStarts, int line) {
        for (int candidate = line; candidate >= 0; candidate--) {
            int start = lineStarts[candidate];
            int end = candidate + 1 < lineStarts.length ? lineStarts[candidate + 1] - 1 : source.length();
            if (start >= end) {
                continue;
            }
            Matcher matcher = DECLARATION.matcher(source.substring(start, end));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = starts.get(index);
        }
        return result;
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (lineStarts[middle] <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private static List<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return DEFAULT_MARKERS;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalized.isEmpty() ? DEFAULT_MARKERS : List.copyOf(normalized);
    }

    private static Pattern compile(List<String> markers) {
        String alternation = String.join("|", markers.stream().map(Pattern::quote).toList());
        return Pattern.compile("\\b(" + alternation + ")\\b", Pattern.CASE_INSENSITIVE);
    }
}
