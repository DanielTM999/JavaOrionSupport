package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DiagnosticRanges {

    private DiagnosticRanges() {
    }

    public static List<Diagnostic> clamp(Collection<Diagnostic> diagnostics, String text) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        String[] lines = linesOf(text);
        List<Diagnostic> clamped = new ArrayList<>(diagnostics.size());
        for (Diagnostic diagnostic : diagnostics) {
            clamped.add(clamp(diagnostic, lines));
        }
        return List.copyOf(clamped);
    }

    /**
     * Keeps the precise server range for single-line diagnostics and reduces a multi-line
     * range to its first meaningful source line. Language servers sometimes attach a parser
     * error to a complete declaration; painting that range literally makes a whole file look
     * broken even though it is only one diagnostic.
     */
    public static List<Diagnostic> compactMultiline(Collection<Diagnostic> diagnostics,
                                                     String text) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        String[] lines = linesOf(text);
        List<Diagnostic> compacted = new ArrayList<>(diagnostics.size());
        for (Diagnostic diagnostic : diagnostics) {
            Diagnostic bounded = clamp(diagnostic, lines);
            compacted.add(compactMultiline(bounded, lines));
        }
        return List.copyOf(compacted);
    }

    static Diagnostic clamp(Diagnostic diagnostic, String[] lines) {
        if (diagnostic == null) {
            return null;
        }
        int startLine = clampLine(diagnostic.startLine(), lines.length);
        int endLine = clampLine(Math.max(diagnostic.endLine(), startLine), lines.length);
        int startCol = Math.max(0, Math.min(diagnostic.startCol(), lengthOf(lines, startLine)));
        int endCol = Math.max(startCol, Math.min(diagnostic.endCol(), lengthOf(lines, endLine)));
        if (startLine == diagnostic.startLine() && endLine == diagnostic.endLine()
                && startCol == diagnostic.startCol() && endCol == diagnostic.endCol()) {
            return diagnostic;
        }
        return new Diagnostic(startLine, startCol, endLine, endCol, diagnostic.severity(),
                diagnostic.message(), diagnostic.source(), diagnostic.overrideColor());
    }

    private static Diagnostic compactMultiline(Diagnostic diagnostic, String[] lines) {
        if (diagnostic == null || diagnostic.endLine() <= diagnostic.startLine()) {
            return diagnostic;
        }
        int anchorLine = firstMeaningfulLine(lines, diagnostic.startLine(), diagnostic.endLine());
        int lineLength = lengthOf(lines, anchorLine);
        int startCol = anchorLine == diagnostic.startLine()
                ? Math.min(diagnostic.startCol(), lineLength)
                : firstNonWhitespaceColumn(lines, anchorLine);
        if (lineLength > 0 && startCol >= lineLength) {
            startCol = firstNonWhitespaceColumn(lines, anchorLine);
        }
        int endCol = Math.max(startCol, lineLength);
        return new Diagnostic(anchorLine, startCol, anchorLine, endCol, diagnostic.severity(),
                diagnostic.message(), diagnostic.source(), diagnostic.overrideColor());
    }

    private static int firstMeaningfulLine(String[] lines, int startLine, int endLine) {
        for (int line = startLine; line <= endLine && line < lines.length; line++) {
            if (!lineText(lines, line).isBlank()) {
                return line;
            }
        }
        return startLine;
    }

    private static int firstNonWhitespaceColumn(String[] lines, int line) {
        String value = lineText(lines, line);
        int length = lengthOf(lines, line);
        int column = 0;
        while (column < length && Character.isWhitespace(value.charAt(column))) {
            column++;
        }
        return column;
    }

    private static String[] linesOf(String text) {
        return text == null ? new String[0] : text.split("\n", -1);
    }

    private static int clampLine(int line, int lineCount) {
        if (lineCount <= 0) {
            return Math.max(0, line);
        }
        return Math.max(0, Math.min(line, lineCount - 1));
    }

    private static int lengthOf(String[] lines, int line) {
        String value = lineText(lines, line);
        return value.endsWith("\r") ? value.length() - 1 : value.length();
    }

    private static String lineText(String[] lines, int line) {
        return line < 0 || line >= lines.length ? "" : lines[line];
    }
}
