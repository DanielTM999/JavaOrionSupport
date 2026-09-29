package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.build.BuildRunConfigurations;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildRunConfigurationBridgeTest {

    @Test
    void mavenEntryBecomesAnEditableMavenRunConfiguration() {
        RunConfigurationData data = BuildRunConfigurationBridge.toRunConfiguration(
                new BuildRunConfigurations.Entry("Empacotar", List.of("clean", "package", "-DskipTests")), false, null);

        assertNull(data.getId());
        assertEquals(JavaRunTypes.MAVEN, data.getType());
        assertEquals("Empacotar", data.getTitle());
        assertEquals("clean package -DskipTests", data.getProperties().get(JavaRunTypes.GOALS));
    }

    @Test
    void gradleEntryUsesTasks() {
        RunConfigurationData data = BuildRunConfigurationBridge.toRunConfiguration(
                new BuildRunConfigurations.Entry("Build", List.of("clean", "build")), true, "id-1");

        assertEquals("id-1", data.getId());
        assertEquals(JavaRunTypes.GRADLE, data.getType());
        assertEquals("clean build", data.getProperties().get(JavaRunTypes.TASKS));
    }

    @Test
    void listsOnlyConfigurationsOfTheProjectBuildTool() {
        List<RunConfigurationData> configurations = List.of(
                configuration("b", JavaRunTypes.MAVEN, "Zeta", Map.of(JavaRunTypes.GOALS, "install")),
                configuration("a", JavaRunTypes.MAVEN, "alfa", Map.of(JavaRunTypes.GOALS, "clean package")),
                configuration("c", JavaRunTypes.APPLICATION, "App", Map.of()),
                configuration("d", JavaRunTypes.MAVEN, "Vazio", Map.of()));

        List<BuildRunConfigurations.Entry> entries = BuildRunConfigurationBridge.entries(configurations, false);

        assertEquals(List.of("alfa", "Zeta"), entries.stream().map(BuildRunConfigurations.Entry::name).toList());
        assertEquals(List.of("clean", "package"), entries.get(0).goals());
        assertEquals("b", BuildRunConfigurationBridge.idOf(configurations, " Zeta ", false).orElseThrow());
        assertTrue(BuildRunConfigurationBridge.idOf(configurations, "App", false).isEmpty());
    }

    @Test
    void onlyAnIdAssignedByTheIdeCountsAsSaved() {
        assertFalse(BuildRunConfigurationBridge.saved(null));
        assertFalse(BuildRunConfigurationBridge.saved(RunConfigurationData.builder().build()));
        assertTrue(BuildRunConfigurationBridge.saved(RunConfigurationData.builder().id("x").build()));
    }

    private static RunConfigurationData configuration(String id, String type, String title,
                                                      Map<String, Object> properties) {
        return RunConfigurationData.builder().id(id).type(type).title(title)
                .properties(new LinkedHashMap<>(properties)).build();
    }
}
