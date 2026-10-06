package dtm.ide;
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
        assertFalse(JavaIdeAdapter.validMavenReactor(pom, new HashSet<>()));
        Files.writeString(child, "<project/>");
        assertTrue(JavaIdeAdapter.validMavenReactor(pom, new HashSet<>()));
        Path fixture = Files.createDirectory(root.resolve("fixtures")).resolve("pom.xml");
        Files.writeString(fixture, "<project>");
        assertTrue(JavaIdeAdapter.validMavenReactor(pom, new HashSet<>()));
    }
}
