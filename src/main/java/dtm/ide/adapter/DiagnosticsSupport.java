package dtm.ide.adapter;

import dtm.ide.index.JavaLexicalSource;
import dtm.ide.inspection.DiagnosticTags;
import dtm.ide.inspection.JavaInspection;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static dtm.ide.adapter.AdapterText.text;

public final class DiagnosticsSupport {
    private DiagnosticsSupport() {
    }

    public static List<Diagnostic> unusedMethodDiagnostics(String text, Collection<Diagnostic> existing,
                                                    Function<Set<String>, Set<String>> unusedLookup) {
        return unusedDeclarationDiagnostics(text, existing, unusedLookup, SymbolKind.METHOD,
                "diagnostic.unusedMethod", "Metodo sem uso no projeto", JavaInspection.UNUSED_METHOD.id());
    }

    public static List<Diagnostic> unusedFieldDiagnostics(String text, Collection<Diagnostic> existing,
                                                   Function<Set<String>, Set<String>> unusedLookup) {
        return unusedDeclarationDiagnostics(text, existing, unusedLookup, SymbolKind.FIELD,
                "diagnostic.unusedField", "Campo sem uso no projeto", JavaInspection.UNUSED_FIELD.id());
    }

    private static List<Diagnostic> unusedDeclarationDiagnostics(String text, Collection<Diagnostic> existing,
            Function<Set<String>, Set<String>> unusedLookup, SymbolKind kind,
            String labelKey, String fallback, String inspectionId) {
        if (text == null || text.isBlank() || !DiagnosticTags.isSupported()) {
            return List.of();
        }
        List<JavaLexicalSource.Declared> methods = declarationsOf(text).stream()
                .filter(declared -> declared.kind() == kind)
                .toList();
        if (methods.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        methods.forEach(method -> names.add(method.name()));
        Set<String> unused = unusedLookup.apply(names);
        if (unused == null || unused.isEmpty()) {
            return List.of();
        }
        String label = text(labelKey, fallback);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JavaLexicalSource.Declared method : methods) {
            Range range = method.range();
            if (!unused.contains(method.name()) || alreadyMarkedUnnecessary(existing, range)) {
                continue;
            }
            Diagnostic hint = DiagnosticTags.unnecessary(new Diagnostic(range.start().line(),
                    range.start().col(), range.end().line(), range.end().col(), DiagnosticSeverity.HINT,
                    label + ": " + method.name(), inspectionId, null), true);
            if (!DiagnosticTags.isUnnecessary(hint)) {
                return List.of();
            }
            diagnostics.add(hint);
        }
        return List.copyOf(diagnostics);
    }

    private record DeclarationsMemo(String text, List<JavaLexicalSource.Declared> declarations) {
    }

    private static volatile DeclarationsMemo declarationsMemo;

    private static List<JavaLexicalSource.Declared> declarationsOf(String text) {
        DeclarationsMemo memo = declarationsMemo;
        if (memo != null && (memo.text() == text || memo.text().equals(text))) {
            return memo.declarations();
        }
        List<JavaLexicalSource.Declared> declarations = JavaLexicalSource.declarations(text);
        declarationsMemo = new DeclarationsMemo(text, declarations);
        return declarations;
    }

    private static boolean alreadyMarkedUnnecessary(Collection<Diagnostic> existing, Range range) {
        if (existing == null) {
            return false;
        }
        int line = range.start().line();
        for (Diagnostic diagnostic : existing) {
            if (DiagnosticTags.isUnnecessary(diagnostic) && diagnostic.startLine() <= line && diagnostic.endLine() >= line
                    && diagnostic.startCol() <= range.end().col()
                    && (diagnostic.endLine() > line || diagnostic.endCol() >= range.start().col())) {
                return true;
            }
        }
        return false;
    }

}
