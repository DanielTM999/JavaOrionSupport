package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenWorkspaceAggregatorTest {

    @TempDir
    Path root;

    @Test
    void theAggregatorListsEverySiblingRelativeToItsOwnFolder() throws IOException {
        siblings();

        Path pom = MavenWorkspaceAggregator.ensure(JavaProjectConventions.describe(root)).orElseThrow();

        assertEquals(root.resolve(".orion/maven-workspace/pom.xml"), pom);
        MavenPom parsed = MavenPom.parse(pom);
        assertEquals("pom", parsed.value("packaging"));
        assertEquals(List.of("../../client", "../../engine", "../../server"), parsed.values("modules", "module"));
    }

    @Test
    void anUnchangedAggregatorIsNotRewritten() throws IOException {
        siblings();
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        Path pom = MavenWorkspaceAggregator.ensure(descriptor).orElseThrow();
        FileTime stamp = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(pom, stamp);

        MavenWorkspaceAggregator.ensure(descriptor);

        assertEquals(stamp, Files.getLastModifiedTime(pom));
    }

    @Test
    void siblingsWithTheSameCoordinatesAreNotDuplicatedInTheReactor() throws IOException {
        write(root.resolve("v1/pom.xml"), project("dtm", "engine", "2.0.0"));
        write(root.resolve("v2/pom.xml"), project("dtm", "engine", "2.0.0"));
        write(root.resolve("app/pom.xml"), project("dtm", "app", "1.0.0"));

        Path pom = MavenWorkspaceAggregator.ensure(JavaProjectConventions.describe(root)).orElseThrow();

        assertEquals(List.of("../../app", "../../v1"), MavenPom.parse(pom).values("modules", "module"));
    }

    @Test
    void anythingButAWorkspaceRemovesALeftoverAggregator() throws IOException {
        siblings();
        Path pom = MavenWorkspaceAggregator.ensure(JavaProjectConventions.describe(root)).orElseThrow();
        write(root.resolve("pom.xml"), """
                <project><groupId>g</groupId><artifactId>root</artifactId><version>1</version>
                <packaging>pom</packaging><modules><module>engine</module></modules></project>""");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertTrue(MavenWorkspaceAggregator.ensure(descriptor).isEmpty());
        assertFalse(Files.exists(pom));
        assertEquals(JavaProjectKind.MAVEN_MULTIMODULE, descriptor.kind());
    }

    @Test
    void aRegularProjectWithoutLeftoversIsLeftUntouched() throws IOException {
        write(root.resolve("pom.xml"), project("g", "single", "1"));

        assertTrue(MavenWorkspaceAggregator.ensure(JavaProjectConventions.describe(root)).isEmpty());
        assertFalse(Files.exists(root.resolve(".orion")));
    }

    private void siblings() throws IOException {
        write(root.resolve("engine/pom.xml"), project("dtm.manager", "ProcessOrchestrator", "2.0.0"));
        write(root.resolve("server/pom.xml"), project("dtm.manager", "ProcessOrchestratorServer", "1.0.0"));
        write(root.resolve("client/pom.xml"), project("dtm.ide", "ProcessOrchestratorOrionClient", "1.0.0"));
    }

    private static String project(String groupId, String artifactId, String version) {
        return "<project><groupId>" + groupId + "</groupId><artifactId>" + artifactId
                + "</artifactId><version>" + version + "</version></project>";
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
