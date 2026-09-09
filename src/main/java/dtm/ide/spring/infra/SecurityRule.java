package dtm.ide.spring.infra;

import java.nio.file.Path;

public record SecurityRule(
        String ownerType,
        String methodName,
        String annotation,
        String expression,
        Path file,
        int line
) {

    public SecurityRule {
        ownerType = ownerType == null ? "" : ownerType.trim();
        methodName = methodName == null ? "" : methodName.trim();
        annotation = annotation == null ? "" : annotation.trim();
        expression = expression == null ? "" : expression.trim();
        line = Math.max(1, line);
    }

    public boolean blank() {
        return expression.isBlank();
    }
}
