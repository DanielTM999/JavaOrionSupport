package dtm.ide.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenPluginGoalsTest {

    @TempDir
    Path repository;

    private static final String DESCRIPTOR = """
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-surefire-plugin</artifactId>
              <goalPrefix>surefire</goalPrefix>
              <mojos>
                <mojo>
                  <goal>test</goal>
                  <description>Run <b>tests</b>
                  using surefire.</description>
                </mojo>
                <mojo>
                  <goal>help</goal>
                  <description>Display help.</description>
                </mojo>
              </mojos>
            </plugin>
            """;

    @Test
    void readsEveryGoalWithItsPrefix() {
        List<MavenPluginGoals.Goal> goals = MavenPluginGoals.parse(DESCRIPTOR);

        assertEquals(List.of("help", "test"),
                goals.stream().map(MavenPluginGoals.Goal::name).toList());
        assertEquals("surefire:test", goals.get(1).invocation());
    }

    @Test
    void flattensTheDescriptionIntoASingleLine() {
        MavenPluginGoals.Goal test = MavenPluginGoals.parse(DESCRIPTOR).get(1);

        assertEquals("Run tests using surefire.", test.description());
    }

    @Test
    void aDescriptorWithoutPrefixFallsBackToTheBareGoal() {
        List<MavenPluginGoals.Goal> goals = MavenPluginGoals.parse(
                "<plugin><mojos><mojo><goal>run</goal></mojo></mojos></plugin>");

        assertEquals("run", goals.getFirst().invocation());
    }

    @Test
    void anEmptyDescriptorYieldsNothing() {
        assertTrue(MavenPluginGoals.parse("").isEmpty());
        assertTrue(MavenPluginGoals.parse(null).isEmpty());
    }

    @Test
    void readsTheGoalsFromTheJarInTheLocalRepository() throws IOException {
        installPlugin("3.2.5");

        List<MavenPluginGoals.Goal> goals = new MavenPluginGoals(repository)
                .goalsOf("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5");

        assertEquals(List.of("help", "test"),
                goals.stream().map(MavenPluginGoals.Goal::name).toList());
    }

    @Test
    void withoutADeclaredVersionTheNewestInstalledOneIsUsed() throws IOException {
        installPlugin("3.1.0");
        installPlugin("3.2.5");

        List<MavenPluginGoals.Goal> goals = new MavenPluginGoals(repository)
                .goalsOf("org.apache.maven.plugins", "maven-surefire-plugin", "");

        assertEquals(2, goals.size());
    }

    @Test
    void aPluginOutsideTheLocalRepositoryYieldsNothing() {
        assertTrue(new MavenPluginGoals(repository)
                .goalsOf("org.example", "never-installed-plugin", "1.0").isEmpty());
    }

    @Test
    void invalidCoordinatesYieldNothing() {
        MavenPluginGoals goals = new MavenPluginGoals(repository);

        assertTrue(goals.goalsOf("", "maven-surefire-plugin", "3.2.5").isEmpty());
        assertTrue(goals.goalsOf("org.apache.maven.plugins", null, "3.2.5").isEmpty());
    }

    private void installPlugin(String version) throws IOException {
        Path jar = repository.resolve("org/apache/maven/plugins/maven-surefire-plugin")
                .resolve(version)
                .resolve("maven-surefire-plugin-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/maven/plugin.xml"));
            zip.write(DESCRIPTOR.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
