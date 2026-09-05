package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageLevelEditorTest {

    @TempDir
    Path root;

    @Test
    void readsTheReleaseProperty() {
        assertEquals(Optional.of(21), LanguageLevelEditor.readMaven("""
                <project><properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties></project>
                """));
    }

    @Test
    void readsTheSourceProperty() {
        assertEquals(Optional.of(17), LanguageLevelEditor.readMaven("""
                <project><properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                </properties></project>
                """));
    }

    @Test
    void readsTheOldOnePointEightForm() {
        assertEquals(Optional.of(8), LanguageLevelEditor.readMaven("""
                <project><properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                </properties></project>
                """));
    }

    @Test
    void readsTheGradleToolchain() {
        assertEquals(Optional.of(21), LanguageLevelEditor.readGradle("""
                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(21) }
                }
                """));
    }

    @Test
    void readsTheGradleCompatibilityForm() {
        assertEquals(Optional.of(17),
                LanguageLevelEditor.readGradle("sourceCompatibility = JavaVersion.VERSION_17\n"));
    }

    @Test
    void releaseWinsWhenTheProjectAlreadyUsesIt() {
        String updated = LanguageLevelEditor.writeMaven("""
                <project><properties>
                    <maven.compiler.release>17</maven.compiler.release>
                </properties></project>
                """, 21);

        assertTrue(updated.contains("<maven.compiler.release>21</maven.compiler.release>"));
        assertTrue(!updated.contains("maven.compiler.source"));
    }

    @Test
    void updatesBothSourceAndTarget() {
        String updated = LanguageLevelEditor.writeMaven("""
                <project><properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                </properties></project>
                """, 21);

        assertTrue(updated.contains("<maven.compiler.source>21</maven.compiler.source>"));
        assertTrue(updated.contains("<maven.compiler.target>21</maven.compiler.target>"));
    }

    @Test
    void createsThePropertiesBlockWhenTheProjectHasNone() {
        String updated = LanguageLevelEditor.writeMaven(
                "<project><groupId>x</groupId></project>", 21);

        assertTrue(updated.contains("<properties>"));
        assertTrue(updated.contains("<maven.compiler.source>21</maven.compiler.source>"));
        assertTrue(updated.contains("<maven.compiler.target>21</maven.compiler.target>"));
    }

    @Test
    void addsToAnExistingPropertiesBlockWithoutTouchingTheRest() {
        String updated = LanguageLevelEditor.writeMaven("""
                <project><properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties></project>
                """, 21);

        assertTrue(updated.contains("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"));
        assertTrue(updated.contains("<maven.compiler.source>21</maven.compiler.source>"));
    }

    @Test
    void updatesTheGradleToolchainInPlace() {
        String updated = LanguageLevelEditor.writeGradle("""
                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(17) }
                }
                """, 21);

        assertTrue(updated.contains("JavaLanguageVersion.of(21)"));
    }

    @Test
    void appendsAToolchainWhenTheScriptDeclaresNothing() {
        String updated = LanguageLevelEditor.writeGradle("plugins { id 'java' }\n", 21);

        assertTrue(updated.contains("JavaLanguageVersion.of(21)"));
        assertTrue(updated.startsWith("plugins { id 'java' }"));
    }

    @Test
    void writesThroughToTheFileOnDisk() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><groupId>x</groupId></project>");

        assertTrue(LanguageLevelEditor.write(root, 21));
        assertEquals(Optional.of(21), LanguageLevelEditor.read(root));
    }
}
