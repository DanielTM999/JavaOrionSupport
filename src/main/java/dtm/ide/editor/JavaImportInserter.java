package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaImportInserter {

    public record Result(String text, int firstLine, int insertedLines, List<TextEdit> edits) {
    }

    private record ImportLine(int line, String name, boolean isStatic) {
    }

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w$.]+(?:\\s*\\.\\s*\\*)?)\\s*;");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w$.]+)\\s*;");

    private JavaImportInserter() {
    }

    public static Result insert(String source, Collection<String> qualifiedNames) {
        String text = source == null ? "" : source;
        if (qualifiedNames == null || qualifiedNames.isEmpty()) {
            return new Result(text, -1, 0, List.of());
        }
        List<String> original = Arrays.asList(text.split("\n", -1));
        List<String> lines = new ArrayList<>(original);
        int originalCount = lines.size();
        int firstLine = Integer.MAX_VALUE;
        for (String name : new TreeSet<>(qualifiedNames)) {
            if (name == null || name.isBlank() || !isNeeded(lines, name.strip())) {
                continue;
            }
            firstLine = Math.min(firstLine, insertOne(lines, name.strip()));
        }
        if (lines.size() == originalCount) {
            return new Result(text, -1, 0, List.of());
        }
        return new Result(String.join("\n", lines), firstLine, lines.size() - originalCount,
                insertionEdits(original, lines));
    }

    private static List<TextEdit> insertionEdits(List<String> original, List<String> updated) {
        List<TextEdit> edits = new ArrayList<>();
        List<String> block = new ArrayList<>();
        int i = 0;
        for (String line : updated) {
            if (i < original.size() && original.get(i).equals(line)) {
                if (!block.isEmpty()) {
                    edits.add(TextEdit.insert(Position.of(i, 0), String.join("\n", block) + "\n"));
                    block.clear();
                }
                i++;
            } else {
                block.add(line);
            }
        }
        if (!block.isEmpty()) {
            int last = original.size() - 1;
            edits.add(TextEdit.insert(Position.of(last, original.get(last).length()),
                    "\n" + String.join("\n", block)));
        }
        return edits;
    }

    private static boolean isNeeded(List<String> lines, String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String owner = name.substring(0, dot);
        String simple = name.substring(dot + 1);
        String currentPackage = packageName(lines);
        if (owner.equals(currentPackage) || owner.equals("java.lang")) {
            return false;
        }
        for (ImportLine existing : imports(lines)) {
            if (existing.isStatic()) {
                continue;
            }
            String imported = existing.name();
            if (imported.equals(name) || imported.equals(owner + ".*")
                    || imported.endsWith("." + simple)) {
                return false;
            }
        }
        return true;
    }

    private static int insertOne(List<String> lines, String name) {
        String statement = "import " + name + ";";
        List<ImportLine> regular = imports(lines).stream().filter(line -> !line.isStatic()).toList();
        if (!regular.isEmpty()) {
            String root = rootOf(name);
            List<ImportLine> group = regular.stream()
                    .filter(line -> rootOf(line.name()).equals(root)).toList();
            List<ImportLine> pool = group.isEmpty() ? regular : group;
            ImportLine previous = null;
            for (ImportLine line : pool) {
                if (line.name().compareTo(name) < 0) {
                    previous = line;
                }
            }
            int at = previous == null ? pool.getFirst().line() : previous.line() + 1;
            lines.add(at, statement);
            return at;
        }
        List<ImportLine> statics = imports(lines);
        if (!statics.isEmpty()) {
            int at = statics.getFirst().line();
            lines.add(at, "");
            lines.add(at, statement);
            return at;
        }
        int packageLine = packageLine(lines);
        if (packageLine >= 0) {
            int at = packageLine + 1;
            if (at < lines.size() && !lines.get(at).isBlank()) {
                lines.add(at, "");
            }
            lines.add(at, statement);
            lines.add(at, "");
            return at;
        }
        lines.add(0, "");
        lines.add(0, statement);
        return 0;
    }

    private static List<ImportLine> imports(List<String> lines) {
        String[] code = JavaSourceText.blankComments(String.join("\n", lines)).split("\n", -1);
        List<ImportLine> imports = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            Matcher matcher = IMPORT.matcher(code[i]);
            if (matcher.find()) {
                imports.add(new ImportLine(i, matcher.group(2).replaceAll("\\s+", ""),
                        matcher.group(1) != null));
            } else if (isTypeStart(code[i])) {
                break;
            }
        }
        return imports;
    }

    private static int packageLine(List<String> lines) {
        String[] code = JavaSourceText.blankComments(String.join("\n", lines)).split("\n", -1);
        for (int i = 0; i < code.length; i++) {
            if (PACKAGE.matcher(code[i]).find()) {
                return i;
            }
            if (isTypeStart(code[i])) {
                return -1;
            }
        }
        return -1;
    }

    private static String packageName(List<String> lines) {
        int line = packageLine(lines);
        if (line < 0) {
            return "";
        }
        Matcher matcher = PACKAGE.matcher(lines.get(line));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isTypeStart(String code) {
        String stripped = code.strip();
        return !stripped.isEmpty() && !stripped.startsWith("import") && !stripped.startsWith("package")
                && !stripped.startsWith("@") && !stripped.equals(";");
    }

    private static String rootOf(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
