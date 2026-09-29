package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

public final class DiagnosticTags {

    private static volatile boolean supported = true;

    private DiagnosticTags() {
    }

    public static boolean isSupported() {
        return supported;
    }

    public static Diagnostic unnecessary(Diagnostic diagnostic, boolean unnecessary) {
        if (diagnostic == null || !unnecessary || !supported) {
            return diagnostic;
        }
        try {
            return diagnostic.withUnnecessary(true);
        } catch (LinkageError e) {
            supported = false;
            return diagnostic;
        }
    }

    public static boolean isUnnecessary(Diagnostic diagnostic) {
        if (diagnostic == null || !supported) {
            return false;
        }
        try {
            return diagnostic.unnecessary();
        } catch (LinkageError e) {
            supported = false;
            return false;
        }
    }
}
