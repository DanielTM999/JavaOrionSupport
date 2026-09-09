package dtm.ide.spring.infra;

import java.nio.file.Path;

public record ScheduledTask(
        String ownerType,
        String methodName,
        String cron,
        String fixedDelay,
        String fixedRate,
        String initialDelay,
        Path file,
        int line
) {

    public ScheduledTask {
        ownerType = ownerType == null ? "" : ownerType.trim();
        methodName = methodName == null ? "" : methodName.trim();
        cron = cron == null ? "" : cron.trim();
        fixedDelay = fixedDelay == null ? "" : fixedDelay.trim();
        fixedRate = fixedRate == null ? "" : fixedRate.trim();
        initialDelay = initialDelay == null ? "" : initialDelay.trim();
        line = Math.max(1, line);
    }

    public boolean hasCron() {
        return !cron.isBlank();
    }

    public boolean hasFixedTiming() {
        return !fixedDelay.isBlank() || !fixedRate.isBlank();
    }

    public boolean conflicting() {
        return hasCron() && hasFixedTiming();
    }

    public boolean unscheduled() {
        return !hasCron() && !hasFixedTiming();
    }
}
