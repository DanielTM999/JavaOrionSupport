package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InspectionSuppressions {

    public static final String IGNORE_MARKER = "orion:ignore";

    private static final int LOOKBEHIND_LINES = 6;

    private static final Pattern SUPPRESS_WARNINGS =
            Pattern.compile("@SuppressWarnings\\s*\\(([^)]*)\\)");

    private static final Pattern IGNORE_COMMENT =
            Pattern.compile("//\\s*" + IGNORE_MARKER + "\\s+([\\w.,\\s-]+)");

    private InspectionSuppressions() {
    }

    public static boolean suppressible(Diagnostic diagnostic) {
        return diagnostic != null
                && diagnostic.severity() != DiagnosticSeverity.ERROR
                && inspectionOf(diagnostic).isPresent();
    }

    public static Optional<JavaInspection> inspectionOf(Diagnostic diagnostic) {
        return diagnostic == null ? Optional.empty() : JavaInspection.byId(diagnostic.source());
    }

    public interface OccurrenceFilter {
        boolean isSuppressed(String inspectionId, String anchor);
    }

    public static List<Diagnostic> filter(Collection<Diagnostic> diagnostics, String source,
                                          Set<String> disabled) {
        return filter(diagnostics, source, disabled, null);
    }

    public static List<Diagnostic> filter(Collection<Diagnostic> diagnostics, String source,
                                          Set<String> disabled, OccurrenceFilter occurrences) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        String[] lines = source == null ? new String[0] : source.split("\n", -1);
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (!suppressible(diagnostic)) {
                kept.add(diagnostic);
                continue;
            }
            String id = diagnostic.source();
            if (disabled != null && disabled.contains(id)) {
                continue;
            }
            if (suppressedAt(lines, diagnostic.startLine(), id)) {
                continue;
            }
            if (occurrences != null
                    && occurrences.isSuppressed(id, anchorAt(lines, diagnostic.startLine()))) {
                continue;
            }
            kept.add(diagnostic);
        }
        return List.copyOf(kept);
    }

    public static String anchorAt(String[] lines, int line) {
        if (lines == null || line < 0 || line >= lines.length) {
            return "";
        }
        return anchorOf(lines[line]);
    }

    public static String anchorOf(String lineText) {
        if (lineText == null) {
            return "";
        }
        return lineText.trim().replaceAll("\\s+", " ");
    }

    static boolean suppressedAt(String[] lines, int line, String inspectionId) {
        if (lines.length == 0 || line < 0) {
            return false;
        }
        int first = Math.max(0, line - LOOKBEHIND_LINES);
        for (int i = line; i >= first; i--) {
            if (i >= lines.length) {
                continue;
            }
            if (mentions(lines[i], inspectionId)) {
                return true;
            }
            if (i < line && isDeclarationBoundary(lines[i])) {
                break;
            }
        }
        return false;
    }

    static boolean mentions(String lineText, String inspectionId) {
        if (lineText == null || lineText.isBlank()) {
            return false;
        }
        Matcher suppress = SUPPRESS_WARNINGS.matcher(lineText);
        while (suppress.find()) {
            if (containsId(suppress.group(1), inspectionId)) {
                return true;
            }
        }
        Matcher ignore = IGNORE_COMMENT.matcher(lineText);
        while (ignore.find()) {
            if (containsId(ignore.group(1), inspectionId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsId(String region, String inspectionId) {
        if (region == null) {
            return false;
        }
        String normalized = region.toLowerCase(Locale.ROOT);
        return normalized.contains(inspectionId.toLowerCase(Locale.ROOT))
                || normalized.contains("\"all\"");
    }

    private static boolean isDeclarationBoundary(String lineText) {
        String trimmed = lineText.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//")
                || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return false;
        }
        return trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}");
    }

    public static String suppressionFor(String lineText, String inspectionId) {
        String indent = indentOf(lineText);
        return indent + "@SuppressWarnings(\"" + inspectionId + "\")\n";
    }

    static String indentOf(String lineText) {
        if (lineText == null) {
            return "";
        }
        int i = 0;
        while (i < lineText.length() && Character.isWhitespace(lineText.charAt(i))) {
            i++;
        }
        return lineText.substring(0, i);
    }

    public static int anchorLineFor(String[] lines, int diagnosticLine) {
        int anchor = diagnosticLine;
        for (int i = diagnosticLine - 1; i >= 0 && i >= diagnosticLine - LOOKBEHIND_LINES; i--) {
            if (i >= lines.length) {
                continue;
            }
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("@")) {
                anchor = i;
                continue;
            }
            break;
        }
        return anchor;
    }
}
