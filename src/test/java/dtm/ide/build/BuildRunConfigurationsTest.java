package dtm.ide.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildRunConfigurationsTest {

    @TempDir
    Path root;

    @Test
    void savesAndReadsBack() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);

        assertTrue(configurations.save(
                new BuildRunConfigurations.Entry("Build", List.of("clean", "package"))));

        List<BuildRunConfigurations.Entry> all = configurations.all();
        assertEquals(1, all.size());
        assertEquals("Build", all.getFirst().name());
        assertEquals(List.of("clean", "package"), all.getFirst().goals());
    }

    @Test
    void theDisplayNameCarriesTheGoals() {
        assertEquals("Build (clean package)",
                new BuildRunConfigurations.Entry("Build", List.of("clean", "package")).display());
    }

    @Test
    void sameNameReplacesThePreviousEntry() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);
        configurations.save(new BuildRunConfigurations.Entry("Build", List.of("package")));
        configurations.save(new BuildRunConfigurations.Entry("Build", List.of("clean", "install")));

        assertEquals(1, configurations.all().size());
        assertEquals(List.of("clean", "install"), configurations.all().getFirst().goals());
    }

    @Test
    void entriesComeBackSortedByName() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);
        configurations.save(new BuildRunConfigurations.Entry("zeta", List.of("test")));
        configurations.save(new BuildRunConfigurations.Entry("alpha", List.of("compile")));

        assertEquals(List.of("alpha", "zeta"),
                configurations.all().stream().map(BuildRunConfigurations.Entry::name).toList());
    }

    @Test
    void removesByName() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);
        configurations.save(new BuildRunConfigurations.Entry("Build", List.of("package")));

        assertTrue(configurations.remove("Build"));
        assertTrue(configurations.all().isEmpty());
        assertFalse(configurations.remove("Build"));
    }

    @Test
    void anEntryWithoutGoalsOrNameIsNotSaved() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);

        assertFalse(configurations.save(new BuildRunConfigurations.Entry("Build", List.of())));
        assertFalse(configurations.save(new BuildRunConfigurations.Entry(" ", List.of("package"))));
        assertTrue(configurations.all().isEmpty());
    }

    @Test
    void goalsAcceptAnySeparatorTheUserTypes() {
        assertEquals(List.of("clean", "package"),
                BuildRunConfigurations.splitGoals("clean package"));
        assertEquals(List.of("clean", "package"),
                BuildRunConfigurations.splitGoals("clean, package"));
        assertEquals(List.of("clean", "install"),
                BuildRunConfigurations.splitGoals("Clean; Install"));
        assertTrue(BuildRunConfigurations.splitGoals("  ").isEmpty());
    }

    @Test
    void theyLiveNextToTheOtherProjectSettings() {
        new BuildRunConfigurations(root)
                .save(new BuildRunConfigurations.Entry("Build", List.of("package")));

        assertTrue(java.nio.file.Files.isRegularFile(
                root.resolve(".orion").resolve("java.properties")));
    }

    @Test
    void activeProfilesAreStoredPerProject() {
        Path otherRoot = root.resolve("other");
        BuildRunConfigurations current = new BuildRunConfigurations(root);
        BuildRunConfigurations other = new BuildRunConfigurations(otherRoot);

        assertTrue(current.saveActiveProfiles(Set.of("dev", "linux")));

        assertEquals(Set.of("dev", "linux"), current.activeProfiles());
        assertTrue(other.activeProfiles().isEmpty());
    }

    @Test
    void clearingProfilesPreservesSavedRunConfigurations() {
        BuildRunConfigurations configurations = new BuildRunConfigurations(root);
        configurations.save(new BuildRunConfigurations.Entry("Build", List.of("clean", "install")));
        configurations.saveActiveProfiles(Set.of("dev"));

        assertTrue(configurations.saveActiveProfiles(Set.of()));

        assertTrue(configurations.activeProfiles().isEmpty());
        assertEquals(List.of("clean", "install"), configurations.all().getFirst().goals());
    }
}
