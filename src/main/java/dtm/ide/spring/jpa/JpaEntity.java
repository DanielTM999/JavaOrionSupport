package dtm.ide.spring.jpa;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record JpaEntity(
        String type,
        String simpleName,
        String table,
        Path file,
        int line,
        List<JpaField> fields,
        String superType,
        boolean mappedSuperclass,
        boolean embeddable,
        boolean hasNoArgConstructor
) {

    public JpaEntity {
        type = type == null ? "" : type.trim();
        simpleName = simpleName == null ? "" : simpleName.trim();
        table = table == null ? "" : table.trim();
        superType = superType == null ? "" : superType.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        line = Math.max(1, line);
    }

    public boolean persistent() {
        return !mappedSuperclass && !embeddable;
    }

    public String effectiveTable() {
        return table.isBlank() ? JpaNaming.toSnakeCase(simpleName) : table;
    }

    public Optional<JpaField> idField() {
        return fields.stream().filter(JpaField::id).findFirst();
    }

    public Optional<JpaField> fieldNamed(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = JpaNaming.uncapitalize(name.trim());
        return fields.stream()
                .filter(field -> !field.ignored())
                .filter(field -> field.name().equals(wanted))
                .findFirst();
    }

    public List<String> fieldNames() {
        return fields.stream().filter(field -> !field.ignored()).map(JpaField::name).toList();
    }
}
