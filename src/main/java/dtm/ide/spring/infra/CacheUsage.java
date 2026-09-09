package dtm.ide.spring.infra;

import java.nio.file.Path;
import java.util.List;

public record CacheUsage(
        String ownerType,
        String methodName,
        String operation,
        List<String> names,
        Path file,
        int line
) {

    public CacheUsage {
        ownerType = ownerType == null ? "" : ownerType.trim();
        methodName = methodName == null ? "" : methodName.trim();
        operation = operation == null ? "" : operation.trim();
        names = names == null ? List.of() : List.copyOf(names);
        line = Math.max(1, line);
    }
}
