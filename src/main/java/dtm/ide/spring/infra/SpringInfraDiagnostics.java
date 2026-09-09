package dtm.ide.spring.infra;

import dtm.ide.inspection.JavaInspection;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SpringInfraDiagnostics {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(SpringInfraDiagnostics.class, key, fallback);
    }

    private static final String SOURCE = "spring-infra";

    private SpringInfraDiagnostics() {
    }

    public static List<Diagnostic> analyze(SpringInfraModel model, Path file) {
        if (model == null || file == null || model.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (ScheduledTask task : model.scheduledTasks()) {
            if (!file.equals(task.file())) {
                continue;
            }
            if (task.conflicting()) {
                diagnostics.add(diagnostic(task.line(), DiagnosticSeverity.ERROR,
                        text("diagnostic.scheduleConflict",
                                "Use cron ou fixedDelay/fixedRate, nunca os dois.")));
            } else if (task.unscheduled()) {
                diagnostics.add(diagnostic(task.line(), DiagnosticSeverity.ERROR,
                        text("diagnostic.scheduleMissing",
                                "@Scheduled exige cron, fixedDelay ou fixedRate.")));
            }
            if (task.hasCron()) {
                Optional<String> error = CronExpression.validate(task.cron());
                if (error.isPresent()) {
                    diagnostics.add(diagnostic(task.line(), DiagnosticSeverity.ERROR,
                            text("diagnostic.badCron", "Expressao cron invalida")
                                    + " - " + error.get()));
                }
            }
            if (!model.enablesScheduling()) {
                diagnostics.add(diagnostic(task.line(), DiagnosticSeverity.WARNING,
                        text("diagnostic.noEnableScheduling",
                                "Nenhum @EnableScheduling encontrado no projeto."),
                        JavaInspection.INFRA_NO_ENABLE_SCHEDULING));
            }
        }

        for (CacheUsage usage : model.cacheUsages()) {
            if (!file.equals(usage.file())) {
                continue;
            }
            if (usage.names().isEmpty() && !"Caching".equals(usage.operation())) {
                diagnostics.add(diagnostic(usage.line(), DiagnosticSeverity.WARNING,
                        text("diagnostic.cacheWithoutName",
                                "Operacao de cache sem nome de cache definido."),
                        JavaInspection.INFRA_CACHE_WITHOUT_NAME));
            }
            if (!model.enablesCaching()) {
                diagnostics.add(diagnostic(usage.line(), DiagnosticSeverity.WARNING,
                        text("diagnostic.noEnableCaching",
                                "Nenhum @EnableCaching encontrado no projeto."),
                        JavaInspection.INFRA_NO_ENABLE_CACHING));
            }
        }

        for (SecurityRule rule : model.securityRules()) {
            if (!file.equals(rule.file())) {
                continue;
            }
            if (rule.blank()) {
                diagnostics.add(diagnostic(rule.line(), DiagnosticSeverity.ERROR,
                        text("diagnostic.emptyExpression", "Expressao de seguranca vazia")
                                + ": @" + rule.annotation()));
            } else if (!balanced(rule.expression())) {
                diagnostics.add(diagnostic(rule.line(), DiagnosticSeverity.ERROR,
                        text("diagnostic.unbalancedExpression",
                                "Expressao de seguranca com parenteses ou aspas desbalanceados")
                                + ": @" + rule.annotation()));
            }
            if (!model.enablesMethodSecurity()) {
                diagnostics.add(diagnostic(rule.line(), DiagnosticSeverity.WARNING,
                        text("diagnostic.noEnableMethodSecurity",
                                "Nenhum @EnableMethodSecurity encontrado no projeto."),
                        JavaInspection.INFRA_NO_ENABLE_METHOD_SECURITY));
            }
        }

        for (EventHandler handler : model.eventHandlers()) {
            if (!file.equals(handler.file()) || handler.bound()) {
                continue;
            }
            diagnostics.add(diagnostic(handler.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.listenerWithoutEvent",
                            "@EventListener precisa de um parametro de evento ou de classes.")));
        }
        return diagnostics;
    }

    static boolean balanced(String expression) {
        int parentheses = 0;
        int quotes = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                parentheses++;
            } else if (c == ')') {
                parentheses--;
                if (parentheses < 0) {
                    return false;
                }
            } else if (c == '\'') {
                quotes++;
            }
        }
        return parentheses == 0 && quotes % 2 == 0;
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message) {
        return diagnostic(line, severity, message, SOURCE);
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message,
                                         JavaInspection inspection) {
        return diagnostic(line, severity, message, inspection.id());
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message,
                                         String source) {
        int editorLine = Math.max(0, line - 1);
        return new Diagnostic(editorLine, 0, editorLine, Integer.MAX_VALUE, severity, message,
                source, null);
    }
}
