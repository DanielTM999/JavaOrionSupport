package dtm.ide.spring.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JpaPropertyResolver {

    public interface EntityLookup {
        Optional<JpaEntity> byType(String type);
    }

    private JpaPropertyResolver() {
    }

    public static Optional<List<JpaField>> resolveJpqlPath(JpaEntity root, String expression,
                                                           EntityLookup lookup) {
        if (root == null || expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        List<JpaField> path = new ArrayList<>();
        JpaEntity current = root;
        for (String part : expression.split("\\.", -1)) {
            if (current == null || part.isBlank()) {
                return Optional.empty();
            }
            Optional<JpaField> field = exactFieldOf(current, part, lookup);
            if (field.isEmpty()) {
                return Optional.empty();
            }
            path.add(field.get());
            current = targetOf(field.get(), lookup).orElse(null);
        }
        return Optional.of(List.copyOf(path));
    }

    private static Optional<JpaField> exactFieldOf(JpaEntity entity, String name,
                                                   EntityLookup lookup) {
        JpaEntity current = entity;
        for (int depth = 0; current != null && depth <= 9; depth++) {
            Optional<JpaField> field = current.fields().stream()
                    .filter(candidate -> !candidate.ignored() && candidate.name().equals(name))
                    .findFirst();
            if (field.isPresent()) {
                return field;
            }
            current = lookup == null || current.superType().isBlank() ? null
                    : lookup.byType(current.superType()).orElse(null);
        }
        return Optional.empty();
    }

    public static Optional<List<JpaField>> resolveCondition(JpaEntity root, String condition,
                                                            EntityLookup lookup) {
        for (String candidate : JpaDerivedQuery.propertyCandidates(condition)) {
            Optional<List<JpaField>> path = resolvePath(root, candidate, lookup);
            if (path.isPresent()) {
                return path;
            }
        }
        return Optional.empty();
    }

    public static Optional<List<JpaField>> resolvePath(JpaEntity root, String expression,
                                                       EntityLookup lookup) {
        if (root == null || expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        if (expression.indexOf('_') >= 0) {
            return resolveExplicit(root, expression, lookup);
        }
        List<JpaField> path = new ArrayList<>();
        return resolveGreedy(root, expression, lookup, path, 0) ? Optional.of(List.copyOf(path))
                : Optional.empty();
    }

    private static Optional<List<JpaField>> resolveExplicit(JpaEntity root, String expression,
                                                            EntityLookup lookup) {
        List<JpaField> path = new ArrayList<>();
        JpaEntity current = root;
        for (String part : expression.split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (current == null) {
                return Optional.empty();
            }
            Optional<JpaField> field = fieldOf(current, part, lookup);
            if (field.isEmpty()) {
                return Optional.empty();
            }
            path.add(field.get());
            current = targetOf(field.get(), lookup).orElse(null);
        }
        return path.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(path));
    }

    private static boolean resolveGreedy(JpaEntity current, String expression, EntityLookup lookup,
                                         List<JpaField> path, int depth) {
        if (current == null || expression.isBlank() || depth > 8) {
            return false;
        }
        List<String> segments = JpaNaming.camelSegments(expression);
        for (int take = segments.size(); take >= 1; take--) {
            String head = String.join("", segments.subList(0, take));
            Optional<JpaField> field = fieldOf(current, head, lookup);
            if (field.isEmpty()) {
                continue;
            }
            path.add(field.get());
            if (take == segments.size()) {
                return true;
            }
            String tail = String.join("", segments.subList(take, segments.size()));
            JpaEntity target = targetOf(field.get(), lookup).orElse(null);
            if (target != null && resolveGreedy(target, tail, lookup, path, depth + 1)) {
                return true;
            }
            path.removeLast();
        }
        return false;
    }

    private static Optional<JpaField> fieldOf(JpaEntity entity, String name, EntityLookup lookup) {
        Optional<JpaField> direct = entity.fieldNamed(name);
        if (direct.isPresent()) {
            return direct;
        }
        return inheritedFieldOf(entity, name, lookup, 0);
    }

    private static Optional<JpaField> inheritedFieldOf(JpaEntity entity, String name,
                                                       EntityLookup lookup, int depth) {
        if (entity.superType().isBlank() || lookup == null || depth > 8) {
            return Optional.empty();
        }
        Optional<JpaEntity> parent = lookup.byType(entity.superType());
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        Optional<JpaField> direct = parent.get().fieldNamed(name);
        return direct.isPresent() ? direct
                : inheritedFieldOf(parent.get(), name, lookup, depth + 1);
    }

    private static Optional<JpaEntity> targetOf(JpaField field, EntityLookup lookup) {
        if (lookup == null || field.targetEntity().isBlank()) {
            return Optional.empty();
        }
        return lookup.byType(field.targetEntity());
    }

    public static List<String> knownProperties(JpaEntity entity, EntityLookup lookup) {
        List<String> names = new ArrayList<>(entity.fieldNames());
        JpaEntity current = entity;
        int depth = 0;
        while (lookup != null && !current.superType().isBlank() && depth++ < 8) {
            Optional<JpaEntity> parent = lookup.byType(current.superType());
            if (parent.isEmpty()) {
                break;
            }
            names.addAll(parent.get().fieldNames());
            current = parent.get();
        }
        return List.copyOf(names);
    }
}
