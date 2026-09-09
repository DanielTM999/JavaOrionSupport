package dtm.ide.spring;

import dtm.ide.inspection.JavaInspection;
import dtm.ide.spring.config.SpringConfigIndex;
import dtm.ide.spring.config.SpringConfigMetadata;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SpringValueDiagnostics {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(SpringValueDiagnostics.class, key, fallback);
    }

    private static final String SOURCE = "spring-value";

    private SpringValueDiagnostics() {
    }

    public static List<Diagnostic> analyze(SpringIndexSnapshot snapshot, SpringConfigIndex index,
                                           SpringConfigMetadata metadata, Path file) {
        if (snapshot == null || file == null || index == null || index.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringPropertyUsage usage : snapshot.propertyUsagesIn(file)) {
            if (usage.isPrefix() || usage.hasDefault() || usage.key().isBlank()) {
                continue;
            }
            if (index.knows(usage.key())) {
                continue;
            }
            if (metadata != null && metadata.isKnown(usage.key())) {
                continue;
            }
            int line = Math.max(0, usage.line() - 1);
            diagnostics.add(new Diagnostic(line, 0, line, Integer.MAX_VALUE,
                    DiagnosticSeverity.WARNING,
                    text("diagnostic.unknownKey", "Propriedade nao definida na configuracao")
                            + ": " + usage.key(),
                    JavaInspection.VALUE_UNKNOWN_KEY.id(), null));
        }
        return diagnostics;
    }
}
