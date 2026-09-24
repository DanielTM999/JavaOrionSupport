package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.stools.component.panels.editor.code.api.Range;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImportCandidates {

    public record Lookup(boolean diagnosed, Map<String, List<String>> candidates) {
        public static final Lookup PENDING = new Lookup(false, Map.of());

        public Lookup {
            candidates = candidates == null ? Map.of() : Map.copyOf(candidates);
        }
    }

    private static final Set<String> UNRESOLVED_CODES = Set.of("16777218", "570425394");
    private static final Pattern IMPORT_TITLE = Pattern.compile(
            "^Import '([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)' \\(([\\w.$]+)\\)$");

    private ImportCandidates() {
    }

    static List<JsonNode> unresolvedIn(List<JsonNode> diagnostics, String text, Range pasted) {
        if (diagnostics == null || diagnostics.isEmpty() || text == null || pasted == null) {
            return List.of();
        }
        int[] lineOffsets = lineOffsets(text);
        int from = offsetOf(pasted.start().line(), pasted.start().col(), lineOffsets, text.length());
        int to = offsetOf(pasted.end().line(), pasted.end().col(), lineOffsets, text.length());
        if (from < 0 || to < from) {
            return List.of();
        }
        List<JsonNode> unresolved = new ArrayList<>();
        for (JsonNode diagnostic : diagnostics) {
            if (!UNRESOLVED_CODES.contains(diagnostic.path("code").asText(""))) {
                continue;
            }
            JsonNode range = diagnostic.path("range");
            int start = offsetOf(range.path("start").path("line").asInt(-1),
                    range.path("start").path("character").asInt(-1), lineOffsets, text.length());
            int end = offsetOf(range.path("end").path("line").asInt(-1),
                    range.path("end").path("character").asInt(-1), lineOffsets, text.length());
            if (start < from || end > to || start >= end) {
                continue;
            }
            String name = text.substring(start, end);
            if (name.startsWith("@")) {
                name = name.substring(1).strip();
            }
            if (isIdentifier(name) && diagnostic.path("message").asText("").startsWith(name + " ")) {
                unresolved.add(diagnostic);
            }
        }
        return unresolved;
    }

    static Map<String, List<String>> fromActions(JsonNode actions) {
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        if (actions == null || !actions.isArray()) {
            return candidates;
        }
        for (JsonNode action : actions) {
            String qualified = qualifiedName(action);
            if (qualified == null) {
                continue;
            }
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            List<String> names = candidates.computeIfAbsent(simple, ignored -> new ArrayList<>());
            if (!names.contains(qualified)) {
                names.add(qualified);
            }
        }
        return candidates;
    }

    private static String qualifiedName(JsonNode action) {
        Matcher title = IMPORT_TITLE.matcher(action.path("title").asText(""));
        return title.matches() ? title.group(2) + "." + title.group(1) : null;
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int[] lineOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offsetOf(int line, int col, int[] lineOffsets, int length) {
        if (line < 0 || col < 0 || line >= lineOffsets.length) {
            return -1;
        }
        int lineEnd = line + 1 < lineOffsets.length ? lineOffsets[line + 1] - 1 : length;
        int offset = lineOffsets[line] + col;
        return offset > lineEnd ? -1 : offset;
    }
}
