package dtm.ide.spring.jpa;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JpqlDiagnostics {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(JpqlDiagnostics.class, key, fallback);
    }

    private static final String SOURCE = "jpql";

    private JpqlDiagnostics() {
    }

    public static List<Diagnostic> analyze(SpringIndexSnapshot snapshot, Path file) {
        if (snapshot == null || file == null) {
            return List.of();
        }
        List<JpaRepositoryInfo> repositories = snapshot.repositoriesIn(file);
        if (repositories.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JpaRepositoryInfo repository : repositories) {
            for (JpaQueryMethod method : repository.methods()) {
                if (!method.validatableQuery()) {
                    continue;
                }
                diagnostics.addAll(analyzeMethod(snapshot, method));
            }
        }
        return diagnostics;
    }

    private static List<Diagnostic> analyzeMethod(SpringIndexSnapshot snapshot,
                                                  JpaQueryMethod method) {
        JpqlQuery query = JpqlQuery.parse(method.jpql());
        List<Diagnostic> diagnostics = new ArrayList<>();
        JpaPropertyResolver.EntityLookup lookup = snapshot.entityLookup();

        for (String alias : query.aliases().keySet()) {
            String entityName = query.entityOf(alias);
            if (entityName.isBlank() || snapshot.entityNamed(entityName).isPresent()) {
                continue;
            }
            diagnostics.add(diagnostic(method.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.unknownEntity", "Entidade desconhecida na consulta")
                            + ": " + entityName));
        }

        for (JpqlQuery.PropertyPath path : query.paths()) {
            Optional<JpaEntity> owner = ownerOf(snapshot, query, path.alias());
            if (owner.isEmpty()) {
                continue;
            }
            if (JpaPropertyResolver.resolveJpqlPath(owner.get(), path.expression(), lookup).isPresent()) {
                continue;
            }
            StringBuilder message = new StringBuilder()
                    .append(text("diagnostic.unknownProperty", "Propriedade inexistente em"))
                    .append(' ')
                    .append(owner.get().simpleName())
                    .append(": ")
                    .append(path.expression());
            String first = firstSegment(path.expression());
            String closest = JpaNaming.closest(first,
                    JpaPropertyResolver.knownProperties(owner.get(), lookup));
            if (!closest.isBlank() && !closest.equals(first)) {
                message.append(". ")
                        .append(text("diagnostic.useInstead", "Voce quis dizer"))
                        .append(' ')
                        .append(closest)
                        .append('?');
            }
            diagnostics.add(diagnostic(method.line(), DiagnosticSeverity.ERROR, message.toString()));
        }

        for (String parameter : query.namedParameters()) {
            if (method.parameters().contains(parameter)) {
                continue;
            }
            diagnostics.add(diagnostic(method.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.unknownParameter", "Parametro sem argumento correspondente")
                            + ": :" + parameter));
        }

        for (Integer position : query.positionalParameters()) {
            if (position >= 1 && position <= method.parameters().size()) {
                continue;
            }
            diagnostics.add(diagnostic(method.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.badPosition", "Parametro posicional fora do intervalo")
                            + ": ?" + position));
        }

        if (query.modifiesData() && !method.modifying()) {
            diagnostics.add(diagnostic(method.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.missingModifying",
                            "Consulta de update ou delete exige @Modifying.")));
        }
        return diagnostics;
    }

    private static Optional<JpaEntity> ownerOf(SpringIndexSnapshot snapshot, JpqlQuery query,
                                               String alias) {
        String relation = query.relationOf(alias);
        Optional<JpaEntity> root = snapshot.entityNamed(query.entityOf(alias));
        if (relation.isBlank()) {
            return root;
        }
        if (root.isEmpty()) {
            return Optional.empty();
        }
        return JpaPropertyResolver.resolveJpqlPath(root.get(), relation, snapshot.entityLookup())
                .map(fields -> fields.getLast())
                .filter(field -> !field.targetEntity().isBlank())
                .flatMap(field -> snapshot.entityNamed(field.targetEntity()));
    }

    private static String firstSegment(String expression) {
        int dot = expression.indexOf('.');
        return dot < 0 ? expression : expression.substring(0, dot);
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message) {
        int editorLine = Math.max(0, line - 1);
        return new Diagnostic(editorLine, 0, editorLine, Integer.MAX_VALUE, severity, message,
                SOURCE, null);
    }
}
