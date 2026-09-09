package dtm.ide.spring.jpa;

import java.util.List;

public record JpaQueryMethod(
        String name,
        int line,
        Subject subject,
        List<String> conditions,
        String orderBy,
        String jpql,
        boolean derived,
        List<String> parameters,
        boolean modifying,
        boolean nativeQuery
) {

    public enum Subject {
        FIND,
        COUNT,
        EXISTS,
        DELETE,
        CUSTOM
    }

    public JpaQueryMethod {
        name = name == null ? "" : name.trim();
        orderBy = orderBy == null ? "" : orderBy.trim();
        jpql = jpql == null ? "" : jpql.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        subject = subject == null ? Subject.CUSTOM : subject;
        line = Math.max(1, line);
    }

    public JpaQueryMethod(String name, int line, Subject subject, List<String> conditions,
                          String orderBy, String jpql, boolean derived) {
        this(name, line, subject, conditions, orderBy, jpql, derived, List.of(), false, false);
    }

    public boolean hasDeclaredQuery() {
        return !jpql.isBlank();
    }

    public boolean validatable() {
        return derived && !hasDeclaredQuery() && !conditions.isEmpty();
    }

    public boolean validatableQuery() {
        return hasDeclaredQuery() && !nativeQuery;
    }
}
