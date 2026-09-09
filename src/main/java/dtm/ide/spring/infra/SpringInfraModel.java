package dtm.ide.spring.infra;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record SpringInfraModel(
        List<ScheduledTask> scheduledTasks,
        List<EventHandler> eventHandlers,
        List<EventPublication> eventPublications,
        List<CacheUsage> cacheUsages,
        List<SecurityRule> securityRules,
        boolean enablesScheduling,
        boolean enablesCaching,
        boolean enablesMethodSecurity
) {

    public SpringInfraModel {
        scheduledTasks = scheduledTasks == null ? List.of() : List.copyOf(scheduledTasks);
        eventHandlers = eventHandlers == null ? List.of() : List.copyOf(eventHandlers);
        eventPublications = eventPublications == null ? List.of() : List.copyOf(eventPublications);
        cacheUsages = cacheUsages == null ? List.of() : List.copyOf(cacheUsages);
        securityRules = securityRules == null ? List.of() : List.copyOf(securityRules);
    }

    public static SpringInfraModel empty() {
        return new SpringInfraModel(List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, false);
    }

    public boolean isEmpty() {
        return scheduledTasks.isEmpty() && eventHandlers.isEmpty() && eventPublications.isEmpty()
                && cacheUsages.isEmpty() && securityRules.isEmpty();
    }

    public SpringInfraModel merge(SpringInfraModel other) {
        if (other == null) {
            return this;
        }
        return new SpringInfraModel(
                concat(scheduledTasks, other.scheduledTasks()),
                concat(eventHandlers, other.eventHandlers()),
                concat(eventPublications, other.eventPublications()),
                concat(cacheUsages, other.cacheUsages()),
                concat(securityRules, other.securityRules()),
                enablesScheduling || other.enablesScheduling(),
                enablesCaching || other.enablesCaching(),
                enablesMethodSecurity || other.enablesMethodSecurity());
    }

    public SpringInfraModel replacingFile(Path file, SpringInfraModel parsed) {
        SpringInfraModel kept = new SpringInfraModel(
                without(scheduledTasks, file, ScheduledTask::file),
                without(eventHandlers, file, EventHandler::file),
                without(eventPublications, file, EventPublication::file),
                without(cacheUsages, file, CacheUsage::file),
                without(securityRules, file, SecurityRule::file),
                false, false, false);
        return kept.merge(parsed == null ? empty() : parsed);
    }

    public List<EventPublication> publicationsOf(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return List.of();
        }
        String needle = simpleNameOf(eventType);
        return eventPublications.stream()
                .filter(publication -> simpleNameOf(publication.eventType()).equals(needle))
                .toList();
    }

    public List<EventHandler> handlersOf(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return List.of();
        }
        String needle = simpleNameOf(eventType);
        return eventHandlers.stream()
                .filter(handler -> simpleNameOf(handler.eventType()).equals(needle))
                .toList();
    }

    public List<String> cacheNames() {
        return cacheUsages.stream()
                .flatMap(usage -> usage.names().stream())
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String simpleNameOf(String type) {
        if (type == null) {
            return "";
        }
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    private static <T> List<T> concat(List<T> left, List<T> right) {
        if (right.isEmpty()) {
            return left;
        }
        List<T> merged = new ArrayList<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private static <T> List<T> without(List<T> values, Path file,
                                       java.util.function.Function<T, Path> fileOf) {
        return values.stream().filter(value -> !file.equals(fileOf.apply(value))).toList();
    }
}
