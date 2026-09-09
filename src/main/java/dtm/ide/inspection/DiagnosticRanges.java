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
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        List<Diagnostic> clamped = new ArrayList<>(diagnostics.size());
        for (Diagnostic diagnostic : diagnostics) {
            clamped.add(clamp(diagnostic, lines));
        }
        return List.copyOf(clamped);
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

    private static int clampLine(int line, int lineCount) {
        if (lineCount <= 0) {
            return Math.max(0, line);
        }
        return Math.max(0, Math.min(line, lineCount - 1));
    }

    private static int lengthOf(String[] lines, int line) {
        if (line < 0 || line >= lines.length) {
            return 0;
        }
        return lines[line].length();
    }
}
