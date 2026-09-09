package dtm.ide.spring;

import java.nio.file.Path;

public record SpringInjection(
        String ownerType,
        String targetType,
        String memberName,
        Kind kind,
        Path file,
        int line,
        String qualifier,
        java.util.List<String> annotations
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
        annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        kind = kind == null ? Kind.FIELD : kind;
        line = Math.max(1, line);
    }

    public SpringInjection(String ownerType, String targetType, String memberName, Kind kind,
                           Path file, int line, String qualifier) {
        this(ownerType, targetType, memberName, kind, file, line, qualifier, java.util.List.of());
    }

    public boolean hasQualifier() {
        return !qualifier.isBlank();
    }

    public String targetSimpleName() {
        return SpringBean.simpleNameOf(targetType);
    }
}
