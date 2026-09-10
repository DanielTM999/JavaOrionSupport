package dtm.ide.deps;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenLocalRepositoryResolverTest {

    @TempDir
    Path temporary;

    @Test
    void readsTheRepositoryMovedByUserSettings() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path repository = temporary.resolve("repository");
        Path settings = Files.createDirectories(home.resolve(".m2")).resolve("settings.xml");
        Files.writeString(settings, "<settings><localRepository>" + repository
                + "</localRepository></settings>");

        MavenLocalRepositoryResolver.Resolution result = resolver(home, Map.of())
                .resolve(descriptor(temporary.resolve("project")), null);

        assertEquals(repository.toAbsolutePath(), result.repository());
        assertTrue(result.configurationFiles().contains(settings.toAbsolutePath()));
        assertFalse(result.fallback());
    }

    @Test
    void projectPropertyOverridesSettingsAndExpandsEnvironment() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path config = Files.createDirectories(project.resolve(".mvn")).resolve("maven.config");
        Files.writeString(config, "-Dmaven.repo.local=${env.REPOSITORY_HOME}/cache");
        Path customHome = temporary.resolve("custom");

        MavenLocalRepositoryResolver.Resolution result = resolver(home,
                Map.of("REPOSITORY_HOME", customHome.toString()))
                .resolve(descriptor(project), null);

        assertEquals(customHome.resolve("cache").toAbsolutePath(), result.repository());
        assertFalse(result.fallback());
    }

    @Test
    void honorsTheSettingsFileSelectedByMavenConfig() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path selected = project.resolve("team-settings.xml");
        Path repository = temporary.resolve("team-repository");
        Files.writeString(selected, "<settings><localRepository>" + repository
                + "</localRepository></settings>");
        Path config = Files.createDirectories(project.resolve(".mvn")).resolve("maven.config");
        Files.writeString(config, "--settings team-settings.xml");

        MavenLocalRepositoryResolver.Resolution result = resolver(home, Map.of())
                .resolve(descriptor(project), null);

        assertEquals(repository.toAbsolutePath(), result.repository());
        assertTrue(result.configurationFiles().contains(selected.toAbsolutePath()));
    }

    @Test
    void fallsBackToM2WhenNoConfigurationExists() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));

        MavenLocalRepositoryResolver.Resolution result = resolver(home, Map.of())
                .resolve(descriptor(Files.createDirectories(temporary.resolve("project"))), null);

        assertEquals(home.resolve(".m2/repository").toAbsolutePath(), result.repository());
        assertTrue(result.fallback());
    }

    @Test
    void readsGlobalSettingsFromTheConfiguredMavenHome() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path mavenHome = Files.createDirectories(temporary.resolve("apache-maven"));
        Path settings = Files.createDirectories(mavenHome.resolve("conf")).resolve("settings.xml");
        Path repository = temporary.resolve("global-repository");
        Files.writeString(settings, "<settings><localRepository>" + repository
                + "</localRepository></settings>");

        MavenLocalRepositoryResolver.Resolution result = resolver(home,
                Map.of("MAVEN_HOME", mavenHome.toString()))
                .resolve(descriptor(Files.createDirectories(temporary.resolve("project"))), null);

        assertEquals(repository.toAbsolutePath(), result.repository());
        assertTrue(result.configurationFiles().contains(settings.toAbsolutePath()));
    }

    private static MavenLocalRepositoryResolver resolver(Path home, Map<String, String> env) {
        return new MavenLocalRepositoryResolver(home, env);
    }

    private static JavaProjectDescriptor descriptor(Path root) throws Exception {
        Files.createDirectories(root);
        JavaModule module = new JavaModule(root, "demo", "example", "demo", "jar",
                List.of(), List.of(), root.resolve("target/classes"));
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(module),
                false, false, 21, null);
    }
}
