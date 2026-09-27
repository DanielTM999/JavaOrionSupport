package dtm.ide.lsp;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.index.JavaLexicalSource;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class LombokAccessorRename {

    public interface Definitions {
        List<List<Location>> at(Path file, String text, List<Position> positions);
    }

    private record Candidate(Position position, Range range, String newName) {
    }

    private LombokAccessorRename() {
    }

    public static Map<Path, List<TextEdit>> edits(List<LombokAccessors.Accessor> accessors, Collection<Path> files,
                                                  Function<Path, String> contentOf, Path declaringFile,
                                                  Definitions definitions) {
        Map<Path, List<TextEdit>> result = new LinkedHashMap<>();
        if (accessors == null || accessors.isEmpty() || files == null || declaringFile == null) {
            return result;
        }
        Path declaring = normalize(declaringFile);
        Set<Path> visited = new LinkedHashSet<>();
        for (Path file : files) {
            Path normalized = normalize(file);
            if (!visited.add(normalized)) {
                continue;
            }
            String content = contentOf.apply(normalized);
            if (content == null || content.isEmpty()) {
                continue;
            }
            List<Candidate> candidates = candidates(content, accessors);
            if (candidates.isEmpty()) {
                continue;
            }
            List<List<Location>> resolved = definitions.at(normalized,
                    content, candidates.stream().map(Candidate::position).toList());
            List<TextEdit> edits = new ArrayList<>();
            for (int i = 0; i < candidates.size() && resolved != null && i < resolved.size(); i++) {
                if (pointsTo(resolved.get(i), declaring)) {
                    edits.add(new TextEdit(candidates.get(i).range(), candidates.get(i).newName()));
                }
            }
            if (!edits.isEmpty()) {
                result.put(normalized, List.copyOf(edits));
            }
        }
        return result;
    }

    public record Result(IdeWorkspaceEdit edit, List<LombokAccessors.Accessor> accessors, int calls) {
    }

    public static Result apply(JdtLsService lsp, Path current, String text, int line, int col, String newName,
                               IdeWorkspaceEdit edit, Function<String, Collection<Path>> filesFor,
                               Function<Path, String> contentOf) {
        Result unchanged = new Result(edit, List.of(), 0);
        List<Location> definitions = lsp.definitions(current, text, line, col);
        if (definitions == null || definitions.size() != 1 || definitions.getFirst().range() == null) {
            return unchanged;
        }
        Path declaring = LspConversions.toPath(definitions.getFirst().uri());
        if (declaring == null || !declaring.getFileName().toString().endsWith(".java")) {
            return unchanged;
        }
        String declaringText = contentOf.apply(declaring);
        List<dtm.stools.component.panels.editor.code.api.DocumentSymbol> symbols = lsp.withDocument(declaring,
                declaringText, open -> lsp.documentSymbols(declaring, open));
        List<LombokAccessors.Accessor> accessors = LombokAccessors.of(declaringText, symbols,
                definitions.getFirst().range().start(), newName == null ? null : newName.trim());
        if (accessors.isEmpty()) {
            return unchanged;
        }
        Set<Path> files = new LinkedHashSet<>();
        files.add(current);
        files.add(declaring);
        accessors.forEach(accessor -> files.addAll(filesFor.apply(accessor.oldName())));
        Map<Path, List<TextEdit>> extra = edits(accessors, files, contentOf, declaring,
                (file, content, positions) -> lsp.withDocument(file, content, open -> positions.stream()
                        .map(position -> lsp.definitions(file, open, position.line(), position.col()))
                        .toList()));
        int calls = extra.values().stream().mapToInt(List::size).sum();
        return new Result(merge(edit, extra), accessors, calls);
    }

    public static IdeWorkspaceEdit merge(IdeWorkspaceEdit base, Map<Path, List<TextEdit>> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        Map<Path, List<TextEdit>> pending = new LinkedHashMap<>(extra);
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        for (IdeWorkspaceEdit.Operation operation : base == null ? List.<IdeWorkspaceEdit.Operation>of() : base.operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits && textEdits.file() != null) {
                List<TextEdit> added = pending.remove(normalize(textEdits.file()));
                operations.add(added == null ? textEdits
                        : new IdeWorkspaceEdit.TextEdits(textEdits.file(), combine(textEdits.edits(), added)));
            } else {
                operations.add(operation);
            }
        }
        pending.forEach((file, edits) -> operations.add(new IdeWorkspaceEdit.TextEdits(file, edits)));
        return new IdeWorkspaceEdit(operations);
    }

    static List<TextEdit> combine(List<TextEdit> base, List<TextEdit> added) {
        List<TextEdit> combined = new ArrayList<>(base);
        for (TextEdit edit : added) {
            boolean clashes = false;
            for (TextEdit existing : base) {
                if (overlaps(existing.range(), edit.range())) {
                    clashes = true;
                    break;
                }
            }
            if (!clashes) {
                combined.add(edit);
            }
        }
        return combined;
    }

    private static List<Candidate> candidates(String content, List<LombokAccessors.Accessor> accessors) {
        String masked = JavaLexicalSource.mask(content);
        int[] lineStarts = JavaLexicalSource.lineStarts(content);
        Map<String, String> renames = new LinkedHashMap<>();
        accessors.forEach(accessor -> renames.putIfAbsent(accessor.oldName(), accessor.newName()));
        List<Candidate> candidates = new ArrayList<>();
        for (int[] span : JavaLexicalSource.occurrences(masked, renames.keySet())) {
            String name = content.substring(span[0], span[1]);
            String newName = renames.get(name);
            if (newName == null || !callsAt(masked, span[1])) {
                continue;
            }
            Range range = JavaLexicalSource.rangeOf(lineStarts, span[0], span[1]);
            candidates.add(new Candidate(range.start(), range, newName));
        }
        return candidates;
    }

    private static boolean callsAt(String masked, int end) {
        int i = end;
        while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) {
            i++;
        }
        return i < masked.length() && masked.charAt(i) == '(';
    }

    private static boolean pointsTo(List<Location> locations, Path declaring) {
        if (locations == null || locations.isEmpty()) {
            return false;
        }
        for (Location location : locations) {
            Path target = location == null || location.uri() == null ? null : LspConversions.toPath(location.uri());
            if (target == null || !normalize(target).equals(declaring)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(Range a, Range b) {
        return compare(a.start(), b.end()) < 0 && compare(b.start(), a.end()) < 0
                || a.start().equals(b.start());
    }

    private static int compare(Position a, Position b) {
        return a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
