package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildRunConfigurations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BuildRunConfigurationBridge {

    private BuildRunConfigurationBridge() {
    }

    public static String typeFor(boolean gradle) {
        return gradle ? JavaRunTypes.GRADLE : JavaRunTypes.MAVEN;
    }

    private static String targetKey(boolean gradle) {
        return gradle ? JavaRunTypes.TASKS : JavaRunTypes.GOALS;
    }

    public static RunConfigurationData toRunConfiguration(BuildRunConfigurations.Entry entry, boolean gradle,
                                                          String existingId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(targetKey(gradle), BuildCommand.joinArguments(entry.goals()));
        return RunConfigurationData.builder()
                .id(existingId)
                .type(typeFor(gradle))
                .title(entry.name())
                .properties(properties)
                .build();
    }

    public static List<BuildRunConfigurations.Entry> entries(List<RunConfigurationData> configurations,
                                                             boolean gradle) {
        List<BuildRunConfigurations.Entry> entries = new ArrayList<>();
        for (RunConfigurationData configuration : safe(configurations)) {
            if (!matches(configuration, gradle)) {
                continue;
            }
            Object goals = configuration.getProperties() == null
                    ? null : configuration.getProperties().get(targetKey(gradle));
            BuildRunConfigurations.Entry entry = new BuildRunConfigurations.Entry(configuration.getTitle(),
                    BuildRunConfigurations.splitGoals(goals == null ? "" : goals.toString()));
            if (entry.isValid()) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(BuildRunConfigurations.Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    public static Optional<String> idOf(List<RunConfigurationData> configurations, String name, boolean gradle) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return safe(configurations).stream()
                .filter(configuration -> matches(configuration, gradle))
                .filter(configuration -> configuration.getTitle() != null && wanted.equals(configuration.getTitle().trim()))
                .map(RunConfigurationData::getId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    public static boolean saved(RunConfigurationData result) {
        return result != null && result.getId() != null && !result.getId().isBlank();
    }

    private static boolean matches(RunConfigurationData configuration, boolean gradle) {
        return configuration != null && typeFor(gradle).equals(configuration.getType());
    }

    private static List<RunConfigurationData> safe(List<RunConfigurationData> configurations) {
        return configurations == null ? List.of() : configurations;
    }
}
