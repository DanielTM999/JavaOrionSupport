package dtm.ide;
import dtm.ide.adapter.ProjectSyncSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
class JavaProjectRecoveryTest {
    @TempDir Path root;
    @Test void reactorBecomesEligibleAgainAfterBrokenChildIsCorrected() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><modules><module>child</module></modules></project>");
        Path child = Files.createDirectory(root.resolve("child")).resolve("pom.xml");
        Files.writeString(child, "<project>");
        assertFalse(ProjectSyncSupport.validMavenReactor(pom, new HashSet<>()));
        Files.writeString(child, "<project/>");
        assertTrue(ProjectSyncSupport.validMavenReactor(pom, new HashSet<>()));
        Path fixture = Files.createDirectory(root.resolve("fixtures")).resolve("pom.xml");
        Files.writeString(fixture, "<project>");
        assertTrue(ProjectSyncSupport.validMavenReactor(pom, new HashSet<>()));
    }
    @Test void jdkRequirementChangeIsDetectedWhenPomSwitchesFromSourceToRelease() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        dtm.ide.project.JavaProjectDescriptor before = dtm.ide.project.JavaProjectConventions.describe(root);
        Files.writeString(pom, "<project><properties><maven.compiler.release>25</maven.compiler.release>"
                + "</properties></project>");
        dtm.ide.project.JavaProjectDescriptor after = dtm.ide.project.JavaProjectConventions.describe(root);
        assertEquals(java.util.Optional.of(21), before.jdkMajor());
        assertEquals(java.util.Optional.of(25), after.jdkMajor());
        assertTrue(ProjectSyncSupport.jdkRequirementChanged(before, after));
        assertFalse(ProjectSyncSupport.jdkRequirementChanged(after, after));
        assertFalse(ProjectSyncSupport.jdkRequirementChanged(null, after));
    }
}
