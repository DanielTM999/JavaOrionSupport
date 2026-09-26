package dtm.ide.debug;

import dtm.ide.index.JavaLexicalSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ConditionSyntheticSource(Path path, String text, int firstLine, int firstColOffset,
                                       int conditionLines) {

    public static final String SUFFIX = "__OrionBreakpointCondition";

    private static final String OPEN = "if (";
    private static final String CLOSE = ") { }";

    public static ConditionSyntheticSource build(Path file, String source, int line, String condition) {
        Path directory = file == null ? null : file.toAbsolutePath().normalize().getParent();
        String fileName = file == null || file.getFileName() == null ? "Condition.java" : file.getFileName().toString();
        String baseName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        String syntheticName = baseName + SUFFIX;
        Path path = directory == null ? Path.of(syntheticName + ".java") : directory.resolve(syntheticName + ".java");

        String normalized = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        String renamed = renameType(normalized, baseName, syntheticName);
        List<String> lines = new ArrayList<>(List.of(renamed.split("\n", -1)));
        int target = Math.max(0, Math.min(line, Math.max(0, lines.size() - 1)));
        String indent = indentationAt(lines, target);

        String[] conditionParts = (condition == null ? "" : condition.replace("\r\n", "\n").replace('\r', '\n'))
                .split("\n", -1);
        List<String> injected = new ArrayList<>();
        for (int index = 0; index < conditionParts.length; index++) {
            String part = conditionParts[index];
            String prefix = index == 0 ? indent + OPEN : "";
            String suffix = index == conditionParts.length - 1 ? CLOSE : "";
            injected.add(prefix + part + suffix);
        }
        lines.addAll(target, injected);
        return new ConditionSyntheticSource(path, String.join("\n", lines), target,
                indent.length() + OPEN.length(), conditionParts.length);
    }

    public int toSyntheticLine(int conditionLine) {
        return firstLine + Math.max(0, conditionLine);
    }

    public int toSyntheticCol(int conditionLine, int col) {
        return Math.max(0, col) + (conditionLine <= 0 ? firstColOffset : 0);
    }

    public boolean coversSyntheticLine(int syntheticLine) {
        return syntheticLine >= firstLine && syntheticLine < firstLine + conditionLines;
    }

    public int toConditionLine(int syntheticLine) {
        return Math.max(0, Math.min(conditionLines - 1, syntheticLine - firstLine));
    }

    public int toConditionCol(int syntheticLine, int col) {
        int offset = syntheticLine == firstLine ? firstColOffset : 0;
        return Math.max(0, col - offset);
    }

    static String renameType(String source, String baseName, String replacement) {
        if (baseName == null || baseName.isBlank() || source.isEmpty()) {
            return source;
        }
        String masked = JavaLexicalSource.mask(source);
        StringBuilder result = new StringBuilder(source.length() + 64);
        int cursor = 0;
        int from = 0;
        while (true) {
            int index = masked.indexOf(baseName, from);
            if (index < 0) {
                break;
            }
            int end = index + baseName.length();
            boolean startsWord = index == 0 || !Character.isJavaIdentifierPart(masked.charAt(index - 1));
            boolean endsWord = end >= masked.length() || !Character.isJavaIdentifierPart(masked.charAt(end));
            if (startsWord && endsWord && !qualified(masked, index)) {
                result.append(source, cursor, index).append(replacement);
                cursor = end;
            }
            from = end;
        }
        result.append(source, cursor, source.length());
        return result.toString();
    }

    private static boolean qualified(String masked, int index) {
        int previous = index - 1;
        while (previous >= 0 && Character.isWhitespace(masked.charAt(previous))) {
            previous--;
        }
        return previous >= 0 && masked.charAt(previous) == '.';
    }

    private static String indentationAt(List<String> lines, int target) {
        for (int index = target; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.isBlank()) {
                int end = 0;
                while (end < line.length() && Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                return line.substring(0, end);
            }
        }
        return "";
    }
}
