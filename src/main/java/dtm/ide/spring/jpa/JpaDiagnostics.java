package dtm.ide.spring.jpa;

import dtm.ide.inspection.JavaInspection;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JpaDiagnostics {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(JpaDiagnostics.class, key, fallback);
    }

    private static final String SOURCE = "jpa";

    private JpaDiagnostics() {
    }

    public static List<Diagnostic> analyze(SpringIndexSnapshot snapshot, Path file) {
        if (snapshot == null || file == null) {
            return List.of();
        }
        List<JpaEntity> entities = snapshot.entitiesIn(file);
        List<JpaRepositoryInfo> repositories = snapshot.repositoriesIn(file);
        if (entities.isEmpty() && repositories.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JpaEntity entity : entities) {
            diagnostics.addAll(entityRules(snapshot, entity));
        }
        for (JpaRepositoryInfo repository : repositories) {
            diagnostics.addAll(repositoryRules(snapshot, repository));
        }
        return diagnostics;
    }

    private static List<Diagnostic> entityRules(SpringIndexSnapshot snapshot, JpaEntity entity) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (entity.persistent() && entity.idField().isEmpty() && !inheritsId(snapshot, entity)) {
            diagnostics.add(diagnostic(entity.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.missingId", "Entidade sem @Id")
                            + ": " + entity.simpleName()));
        }
        if (entity.persistent() && !entity.hasNoArgConstructor()) {
            diagnostics.add(diagnostic(entity.line(), DiagnosticSeverity.WARNING,
                    text("diagnostic.missingNoArgConstructor",
                            "Entidade JPA precisa de um construtor sem argumentos."),
                    JavaInspection.JPA_NO_ARG_CONSTRUCTOR));
        }
        for (JpaField field : entity.fields()) {
            if (field.relation() == JpaField.Relation.MANY_TO_ONE
                    || field.relation() == JpaField.Relation.ONE_TO_ONE) {
                diagnostics.add(diagnostic(field.line(), DiagnosticSeverity.HINT,
                        text("diagnostic.eagerRelation",
                                "Relacao singular e EAGER por padrao; considere fetch = FetchType.LAZY."),
                        JavaInspection.JPA_EAGER_RELATION));
            }
        }
        return diagnostics;
    }

    private static boolean inheritsId(SpringIndexSnapshot snapshot, JpaEntity entity) {
        JpaEntity current = entity;
        int depth = 0;
        while (!current.superType().isBlank() && depth++ < 8) {
            Optional<JpaEntity> parent = snapshot.entityNamed(current.superType());
            if (parent.isEmpty()) {
                return true;
            }
            if (parent.get().idField().isPresent()) {
                return true;
            }
            current = parent.get();
        }
        return false;
    }

    private static List<Diagnostic> repositoryRules(SpringIndexSnapshot snapshot,
                                                    JpaRepositoryInfo repository) {
        if (!repository.bound()) {
            return List.of();
        }
        Optional<JpaEntity> entity = snapshot.entityNamed(repository.entityType());
        if (entity.isEmpty()) {
            return List.of();
        }
        if (!entity.get().persistent()) {
            return List.of(diagnostic(repository.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.notAnEntity", "O tipo do repositorio nao e uma @Entity")
                            + ": " + repository.entitySimpleName()));
        }

        JpaPropertyResolver.EntityLookup lookup = snapshot.entityLookup();
        List<String> known = JpaPropertyResolver.knownProperties(entity.get(), lookup);
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (JpaQueryMethod method : repository.methods()) {
            if (!method.validatable()) {
                continue;
            }
            for (String condition : method.conditions()) {
                if (JpaPropertyResolver.resolveCondition(entity.get(), condition, lookup).isPresent()) {
                    continue;
                }
                diagnostics.add(unknownProperty(method, condition, entity.get(), known));
            }
            for (String property : JpaDerivedQuery.orderProperties(method.orderBy())) {
                if (JpaPropertyResolver.resolvePath(entity.get(), property, lookup).isPresent()) {
                    continue;
                }
                diagnostics.add(unknownProperty(method, property, entity.get(), known));
            }
        }
        return diagnostics;
    }

    private static Diagnostic unknownProperty(JpaQueryMethod method, String condition,
                                              JpaEntity entity, List<String> known) {
        String property = JpaNaming.uncapitalize(JpaDerivedQuery.stripKeywords(condition));
        if (property.isBlank()) {
            property = JpaNaming.uncapitalize(condition);
        }
        StringBuilder message = new StringBuilder()
                .append(text("diagnostic.unknownProperty", "Propriedade inexistente em"))
                .append(' ')
                .append(entity.simpleName())
                .append(": ")
                .append(property);
        String suggestion = JpaNaming.closest(property, known);
        if (!suggestion.isBlank()) {
            message.append(". ")
                    .append(text("diagnostic.useInstead", "Voce quis dizer"))
                    .append(' ')
                    .append(suggestion)
                    .append('?');
        }
        return diagnostic(method.line(), DiagnosticSeverity.ERROR, message.toString());
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
