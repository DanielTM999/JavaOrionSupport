package dtm.ide.spring;

import dtm.ide.spring.jpa.JpaDerivedQuery;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaField;
import dtm.ide.spring.jpa.JpaPropertyResolver;
import dtm.ide.spring.jpa.JpaQueryMethod;
import dtm.ide.spring.jpa.JpaRepositoryInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringNavigation {

    private static final Set<String> QUALIFIER_ANNOTATIONS = Set.of("qualifier", "resource");

    private static final Pattern ANNOTATION_BEFORE =
            Pattern.compile("@([A-Za-z_][\\w.]*)\\s*\\([^()]*$");

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{\\s*([^:}\\s]+)\\s*(?::[^}]*)?}");

    private SpringNavigation() {
    }

    public enum Kind {
        BEAN,
        INJECTION,
        ENTITY,
        ENTITY_FIELD,
        CONFIG_KEY,
        PROFILE
    }

    public record Anchor(Path file, int line, String label) {

        public Anchor {
            line = Math.max(1, line);
            label = label == null ? "" : label;
        }
    }

    public record Target(Kind kind, String token, List<Anchor> anchors) {

        public Target {
            token = token == null ? "" : token;
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
        }

        public boolean isEmpty() {
            return anchors.isEmpty();
        }
    }

    public static Optional<Target> definitions(SpringIndexSnapshot snapshot, Path file, String text,
                                               int line, int col) {
        if (snapshot == null || file == null || text == null) {
            return Optional.empty();
        }
        String lineText = lineAt(text, line);
        if (lineText == null) {
            return Optional.empty();
        }
        Optional<Target> literal = insideAnnotationLiteral(snapshot, lineText, col);
        if (literal.isPresent()) {
            return literal;
        }
        Optional<Target> injection = atInjection(snapshot, file, line);
        if (injection.isPresent()) {
            return injection;
        }
        return atRepositoryMember(snapshot, file, line);
    }

    public static Optional<Target> references(SpringIndexSnapshot snapshot, Path file, int line) {
        if (snapshot == null || file == null) {
            return Optional.empty();
        }
        int indexLine = line + 1;

        for (SpringBean bean : snapshot.beansIn(file)) {
            if (bean.line() != indexLine) {
                continue;
            }
            List<Anchor> anchors = new ArrayList<>();
            for (SpringInjection injection : snapshot.injectionsOf(bean)) {
                anchors.add(new Anchor(injection.file(), injection.line(),
                        SpringBean.simpleNameOf(injection.ownerType()) + "." + injection.memberName()));
            }
            if (!anchors.isEmpty()) {
                return Optional.of(new Target(Kind.BEAN, bean.simpleName(), anchors));
            }
        }

        for (JpaEntity entity : snapshot.entitiesIn(file)) {
            if (entity.line() != indexLine) {
                continue;
            }
            List<Anchor> anchors = new ArrayList<>();
            for (JpaRepositoryInfo repository : snapshot.repositoriesFor(entity)) {
                anchors.add(new Anchor(repository.file(), repository.line(),
                        repository.simpleName()));
            }
            if (!anchors.isEmpty()) {
                return Optional.of(new Target(Kind.ENTITY, entity.simpleName(), anchors));
            }
        }
        return Optional.empty();
    }

    static Optional<Target> insideAnnotationLiteral(SpringIndexSnapshot snapshot, String lineText,
                                                    int col) {
        int[] bounds = literalBounds(lineText, col);
        if (bounds == null) {
            return Optional.empty();
        }
        String value = lineText.substring(bounds[0], bounds[1]);
        String annotation = annotationBefore(lineText, bounds[0]);
        if (annotation.isBlank()) {
            return Optional.empty();
        }

        if (QUALIFIER_ANNOTATIONS.contains(annotation)) {
            String name = value.isBlank() ? "" : value;
            return Optional.of(new Target(Kind.BEAN, name, beanAnchors(snapshot, name)));
        }
        if ("profile".equals(annotation)) {
            return Optional.of(new Target(Kind.PROFILE, value, List.of()));
        }
        if ("value".equals(annotation)) {
            Matcher placeholder = PLACEHOLDER.matcher(value);
            if (placeholder.find()) {
                return Optional.of(new Target(Kind.CONFIG_KEY, placeholder.group(1), List.of()));
            }
        }
        if ("configurationproperties".equals(annotation)) {
            return Optional.of(new Target(Kind.CONFIG_KEY, value, List.of()));
        }
        return Optional.empty();
    }

    private static List<Anchor> beanAnchors(SpringIndexSnapshot snapshot, String name) {
        List<Anchor> anchors = new ArrayList<>();
        for (SpringBean bean : snapshot.beansMatchingQualifier(name)) {
            anchors.add(new Anchor(bean.file(), bean.line(), bean.simpleName()));
        }
        return anchors;
    }

    private static Optional<Target> atInjection(SpringIndexSnapshot snapshot, Path file, int line) {
        int indexLine = line + 1;
        List<Anchor> anchors = new ArrayList<>();
        String token = "";
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            if (injection.line() != indexLine) {
                continue;
            }
            token = injection.targetSimpleName();
            for (SpringBean bean : snapshot.candidatesFor(injection)) {
                anchors.add(new Anchor(bean.file(), bean.line(), bean.simpleName()));
            }
        }
        if (anchors.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Target(Kind.INJECTION, token, distinct(anchors)));
    }

    private static Optional<Target> atRepositoryMember(SpringIndexSnapshot snapshot, Path file,
                                                       int line) {
        int indexLine = line + 1;
        JpaPropertyResolver.EntityLookup lookup = snapshot.entityLookup();

        for (JpaRepositoryInfo repository : snapshot.repositoriesIn(file)) {
            Optional<JpaEntity> entity = snapshot.entityNamed(repository.entityType());
            if (entity.isEmpty()) {
                continue;
            }
            if (repository.line() == indexLine) {
                return Optional.of(new Target(Kind.ENTITY, entity.get().simpleName(),
                        List.of(new Anchor(entity.get().file(), entity.get().line(),
                                entity.get().simpleName()))));
            }
            for (JpaQueryMethod method : repository.methods()) {
                if (method.line() != indexLine || !method.validatable()) {
                    continue;
                }
                List<Anchor> anchors = fieldAnchors(entity.get(), method, lookup);
                if (!anchors.isEmpty()) {
                    return Optional.of(new Target(Kind.ENTITY_FIELD, method.name(),
                            distinct(anchors)));
                }
            }
        }
        return Optional.empty();
    }

    private static List<Anchor> fieldAnchors(JpaEntity entity, JpaQueryMethod method,
                                             JpaPropertyResolver.EntityLookup lookup) {
        List<Anchor> anchors = new ArrayList<>();
        List<String> expressions = new ArrayList<>(method.conditions());
        expressions.addAll(JpaDerivedQuery.orderProperties(method.orderBy()));
        for (String expression : expressions) {
            JpaPropertyResolver.resolveCondition(entity, expression, lookup)
                    .ifPresent(path -> {
                        for (JpaField field : path) {
                            anchors.add(new Anchor(entity.file(), field.line(), field.name()));
                        }
                    });
        }
        return anchors;
    }

    static int[] literalBounds(String lineText, int col) {
        int caret = Math.max(0, Math.min(col, lineText.length()));
        int quotes = 0;
        int start = -1;
        for (int i = 0; i < lineText.length(); i++) {
            if (lineText.charAt(i) != '"') {
                continue;
            }
            if (quotes % 2 == 0) {
                start = i + 1;
            } else if (caret >= start && caret <= i) {
                return new int[]{start, i};
            }
            quotes++;
        }
        return null;
    }

    static String annotationBefore(String lineText, int literalStart) {
        String prefix = lineText.substring(0, Math.max(0, literalStart - 1));
        Matcher matcher = ANNOTATION_BEFORE.matcher(prefix);
        if (!matcher.find()) {
            return "";
        }
        String name = matcher.group(1);
        int lastDot = name.lastIndexOf('.');
        return (lastDot >= 0 ? name.substring(lastDot + 1) : name)
                .toLowerCase(java.util.Locale.ROOT);
    }

    static String lineAt(String text, int line) {
        if (line < 0) {
            return null;
        }
        int start = 0;
        int current = 0;
        while (current < line) {
            int next = text.indexOf('\n', start);
            if (next < 0) {
                return null;
            }
            start = next + 1;
            current++;
        }
        int end = text.indexOf('\n', start);
        String value = end < 0 ? text.substring(start) : text.substring(start, end);
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }

    private static List<Anchor> distinct(List<Anchor> anchors) {
        return List.copyOf(new LinkedHashSet<>(anchors));
    }
}
