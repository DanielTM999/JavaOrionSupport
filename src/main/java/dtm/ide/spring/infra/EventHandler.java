package dtm.ide.spring.infra;

import java.nio.file.Path;

public record EventHandler(
        String ownerType,
        String methodName,
        String eventType,
        boolean transactional,
        Path file,
        int line
) {

    public EventHandler {
        ownerType = ownerType == null ? "" : ownerType.trim();
        methodName = methodName == null ? "" : methodName.trim();
        eventType = eventType == null ? "" : eventType.trim();
        line = Math.max(1, line);
    }

    public boolean bound() {
        return !eventType.isBlank();
    }
}
