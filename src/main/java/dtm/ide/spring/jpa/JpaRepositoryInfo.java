package dtm.ide.spring.jpa;

import java.nio.file.Path;
import java.util.List;

public record JpaRepositoryInfo(
        String type,
        String simpleName,
        String entityType,
        String idType,
        Path file,
        int line,
        List<JpaQueryMethod> methods
) {

    public JpaRepositoryInfo {
        type = type == null ? "" : type.trim();
        simpleName = simpleName == null ? "" : simpleName.trim();
        entityType = entityType == null ? "" : entityType.trim();
        idType = idType == null ? "" : idType.trim();
        methods = methods == null ? List.of() : List.copyOf(methods);
        line = Math.max(1, line);
    }

    public String entitySimpleName() {
        String name = entityType;
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }

    public boolean bound() {
        return !entityType.isBlank();
    }
}
