package dtm.ide.build;

import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;

public record BuildDiagnostic(
        Path file,
        int line,
        int column,
        DiagnosticSeverity severity,
        String message,
        String source
) {

    public BuildDiagnostic {
        message = message == null ? "" : message.trim();
        source = source == null || source.isBlank() ? "build" : source;
        severity = severity == null ? DiagnosticSeverity.ERROR : severity;
        line = Math.max(0, line);
        column = Math.max(0, column);
    }

    public boolean isError() {
        return severity == DiagnosticSeverity.ERROR;
    }

    public boolean hasLocation() {
        return file != null && line > 0;
    }

    public dtm.ide.api.project.diagnostics.IdeProblem toIdeProblem() {
        return new dtm.ide.api.project.diagnostics.IdeProblem(
                file, line, column, severity, message, source, "");
    }

    public dtm.stools.component.panels.editor.code.diagnostics.Diagnostic toEditorDiagnostic() {
        int editorLine = Math.max(0, line - 1);
        int editorColumn = Math.max(0, column - 1);
        return new dtm.stools.component.panels.editor.code.diagnostics.Diagnostic(
                editorLine, editorColumn, editorLine, editorColumn + 1,
                severity, message, source, null);
    }
}
