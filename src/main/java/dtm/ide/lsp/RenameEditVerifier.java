package dtm.ide.lsp;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class RenameEditVerifier {

    record Result(IdeWorkspaceEdit edit, Path rejectedFile, String problem) {

        boolean rejected() {
            return problem != null;
        }
    }

    private RenameEditVerifier() {
    }

    static Result verify(IdeWorkspaceEdit edit, String oldName, String newName, Function<Path, String> contentOf) {
        if (edit == null || edit.isEmpty() || oldName == null || oldName.isEmpty()
                || newName == null || newName.isEmpty()) {
            return new Result(edit, null, null);
        }
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>(edit.operations().size());
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (!(operation instanceof IdeWorkspaceEdit.TextEdits textEdits)) {
                operations.add(operation);
                continue;
            }
            String raw = contentOf.apply(textEdits.file());
            if (raw == null) {
                return new Result(null, textEdits.file(), "o conteudo atual do arquivo nao esta disponivel");
            }
            String content = LspConversions.normalizeLineBreaks(raw);
            List<TextEdit> minimal = minimize(content, textEdits.edits(), namePairs(oldName, newName));
            if (minimal == null) {
                return new Result(null, textEdits.file(),
                        "a edicao do servidor muda mais do que o nome '" + oldName + "'");
            }
            if (!minimal.isEmpty()) {
                operations.add(new IdeWorkspaceEdit.TextEdits(textEdits.file(), minimal));
            }
        }
        return new Result(new IdeWorkspaceEdit(operations), null, null);
    }

    record NamePair(String oldName, String newName) {
    }

    static List<NamePair> namePairs(String oldName, String newName) {
        List<NamePair> pairs = new ArrayList<>();
        pairs.add(new NamePair(oldName, newName));
        if (isSimpleIdentifier(oldName) && isSimpleIdentifier(newName)) {
            String oldCapitalized = Character.toUpperCase(oldName.charAt(0)) + oldName.substring(1);
            String newCapitalized = Character.toUpperCase(newName.charAt(0)) + newName.substring(1);
            for (String prefix : List.of("get", "set", "is")) {
                pairs.add(new NamePair(prefix + oldCapitalized, prefix + newCapitalized));
            }
        }
        return pairs;
    }

    private static boolean isSimpleIdentifier(String name) {
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

    private static List<TextEdit> minimize(String content, List<TextEdit> edits, List<NamePair> pairs) {
        int[] lineStarts = lineStarts(content);
        List<TextEdit> result = new ArrayList<>();
        for (TextEdit edit : edits) {
            if (edit == null || edit.range() == null) {
                continue;
            }
            int start = offsetOf(edit.range().start(), lineStarts, content.length());
            int end = offsetOf(edit.range().end(), lineStarts, content.length());
            if (start < 0 || end < start) {
                return null;
            }
            String replacement = LspConversions.normalizeLineBreaks(edit.newText() == null ? "" : edit.newText());
            List<int[]> hits = new ArrayList<>();
            if (!align(new Alignment(content, end, replacement, pairs), start, 0, hits, new HashSet<>())) {
                return null;
            }
            for (int[] hit : hits) {
                NamePair pair = pairs.get(hit[1]);
                result.add(new TextEdit(new Range(positionOf(hit[0], lineStarts),
                        positionOf(hit[0] + pair.oldName().length(), lineStarts)), pair.newName()));
            }
        }
        return result;
    }

    private record Alignment(String content, int end, String replacement, List<NamePair> pairs) {
    }

    private static boolean align(Alignment a, int i, int j, List<int[]> hits, Set<Long> failed) {
        while (true) {
            if (i == a.end() && j == a.replacement().length()) {
                return true;
            }
            boolean sameChar = i < a.end() && j < a.replacement().length()
                    && a.content().charAt(i) == a.replacement().charAt(j);
            boolean canRename = false;
            for (NamePair pair : a.pairs()) {
                if (renamesAt(a, pair, i, j)) {
                    canRename = true;
                    break;
                }
            }
            if (!canRename) {
                if (!sameChar) {
                    return false;
                }
                i++;
                j++;
                continue;
            }
            long key = ((long) i << 32) | (j & 0xFFFFFFFFL);
            if (failed.contains(key)) {
                return false;
            }
            for (int index = 0; index < a.pairs().size(); index++) {
                NamePair pair = a.pairs().get(index);
                if (!renamesAt(a, pair, i, j)) {
                    continue;
                }
                hits.add(new int[]{i, index});
                if (align(a, i + pair.oldName().length(), j + pair.newName().length(), hits, failed)) {
                    return true;
                }
                hits.remove(hits.size() - 1);
            }
            if (sameChar && align(a, i + 1, j + 1, hits, failed)) {
                return true;
            }
            failed.add(key);
            return false;
        }
    }

    private static boolean renamesAt(Alignment a, NamePair pair, int i, int j) {
        return i + pair.oldName().length() <= a.end()
                && a.content().startsWith(pair.oldName(), i)
                && a.replacement().startsWith(pair.newName(), j)
                && isWholeName(a.content(), i, pair.oldName().length());
    }

    private static boolean isWholeName(String text, int start, int length) {
        boolean startsClean = start == 0 || !Character.isJavaIdentifierPart(text.charAt(start - 1));
        int end = start + length;
        boolean endsClean = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
        return startsClean && endsClean;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    private static int offsetOf(Position position, int[] lineStarts, int length) {
        if (position == null || position.line() < 0 || position.col() < 0) {
            return -1;
        }
        if (position.line() >= lineStarts.length) {
            return position.line() == lineStarts.length && position.col() == 0 ? length : -1;
        }
        int lineEnd = position.line() + 1 < lineStarts.length ? lineStarts[position.line() + 1] - 1 : length;
        long offset = (long) lineStarts[position.line()] + position.col();
        return offset > lineEnd ? -1 : (int) offset;
    }

    private static Position positionOf(int offset, int[] lineStarts) {
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
        return new Position(low, offset - lineStarts[low]);
    }
}
