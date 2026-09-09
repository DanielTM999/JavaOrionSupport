package dtm.ide.spring.infra;

import java.nio.file.Path;

public record EventPublication(String ownerType, String eventType, Path file, int line) {

    public EventPublication {
        ownerType = ownerType == null ? "" : ownerType.trim();
        eventType = eventType == null ? "" : eventType.trim();
        line = Math.max(1, line);
    }
}
