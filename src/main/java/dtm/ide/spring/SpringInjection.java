package dtm.ide.spring;

import java.nio.file.Path;

public record SpringInjection(
        String ownerType,
        String targetType,
        String memberName,
        Kind kind,
        Path file,
        int line,
        String qualifier
) {

    public enum Kind {
        CONSTRUCTOR,
        FIELD,
        SETTER
    }

    public SpringInjection {
        ownerType = ownerType == null ? "" : ownerType.trim();
        targetType = targetType == null ? "" : targetType.trim();
        memberName = memberName == null ? "" : memberName.trim();
        qualifier = qualifier == null ? "" : qualifier.trim();
        kind = kind == null ? Kind.FIELD : kind;
        line = Math.max(1, line);
    }

    public boolean hasQualifier() {
        return !qualifier.isBlank();
    }

    public String targetSimpleName() {
        return SpringBean.simpleNameOf(targetType);
    }
}
