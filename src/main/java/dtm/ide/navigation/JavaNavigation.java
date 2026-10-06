package dtm.ide.navigation;

import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JavaNavigation {
    private JavaNavigation() { }

    public enum Kind {
        DEFINITION("textDocument/definition", "definition"),
        IMPLEMENTATION("textDocument/implementation", "implementation"),
        REFERENCES("textDocument/references", "usages");
        private final String method, action;
        Kind(String method, String action) { this.method = method; this.action = action; }
        public String method() { return method; }
        public String action() { return action; }
        public static Kind forAction(String action) {
            for (Kind kind : values()) if (kind.action.equals(action)) return kind;
            return DEFINITION;
        }
        public static Kind forLens(String command) {
            return switch (command) {
                case "java.show.references" -> REFERENCES;
                case "java.show.implementations" -> IMPLEMENTATION;
                default -> null;
            };
        }
    }

    public enum Extent { DOCUMENT, PROJECT }
    public enum Status { COMPLETE, LOCAL, INDEXING, UNAVAILABLE, FAILED, STALE }
    public record Result(Status status, List<Location> locations) {
        public Result { locations = unique(locations); }
        public boolean resolved() { return status == Status.COMPLETE || status == Status.LOCAL; }
        public static Result of(Status status) { return new Result(status, List.of()); }
    }

    public static Path path(Location location) {
        if (location == null || location.uri() == null) return null;
        try {
            URI uri = URI.create(location.uri());
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri).toAbsolutePath().normalize() : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    public static String key(Location location) {
        if (location == null || location.range() == null) return "";
        Path path = path(location);
        Range range = location.range();
        String resource = path == null ? location.uri() : path.toUri().toString();
        if (path != null && java.io.File.separatorChar == '\\') resource = resource.toLowerCase(java.util.Locale.ROOT);
        return resource + "|" + range.start().line() + ":" + range.start().col()
                + "-" + range.end().line() + ":" + range.end().col();
    }

    public static List<Location> unique(List<Location> locations) {
        Map<String, Location> result = new LinkedHashMap<>();
        if (locations != null) for (Location location : locations) {
            if (location != null && location.uri() != null && location.range() != null
                    && location.range().start() != null && location.range().end() != null) {
                result.putIfAbsent(key(location), location);
            }
        }
        return List.copyOf(result.values());
    }

    public static boolean contains(Location location, Path file, int line, int col) {
        if (file == null || !file.toAbsolutePath().normalize().equals(path(location))) return false;
        Range r = location.range();
        return r != null && (line > r.start().line() || line == r.start().line() && col >= r.start().col())
                && (line < r.end().line() || line == r.end().line() && col < r.end().col());
    }

    public static Result restrict(Result result, Extent extent, Path file) {
        if (extent == Extent.PROJECT || file == null) return result;
        Path normalized = file.toAbsolutePath().normalize();
        return new Result(result.status(), result.locations().stream()
                .filter(location -> normalized.equals(path(location))).toList());
    }
}
