package dtm.ide.spring.jpa;

public record JpaField(
        String name,
        String type,
        String column,
        Relation relation,
        String targetEntity,
        int line,
        boolean id,
        boolean ignored
) {

    public enum Relation {
        NONE,
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_ONE,
        MANY_TO_MANY,
        EMBEDDED;

        public boolean isAssociation() {
            return this != NONE && this != EMBEDDED;
        }

        public boolean isCollection() {
            return this == ONE_TO_MANY || this == MANY_TO_MANY;
        }

        public boolean isSingular() {
            return this == ONE_TO_ONE || this == MANY_TO_ONE;
        }
    }

    public JpaField {
        name = name == null ? "" : name.trim();
        type = type == null ? "" : type.trim();
        column = column == null ? "" : column.trim();
        targetEntity = targetEntity == null ? "" : targetEntity.trim();
        relation = relation == null ? Relation.NONE : relation;
        line = Math.max(1, line);
    }

    public boolean navigable() {
        return relation != Relation.NONE && !targetEntity.isBlank();
    }

    public String effectiveColumn() {
        return column.isBlank() ? JpaNaming.toSnakeCase(name) : column;
    }
}
