package dtm.ide.build.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCompilerOptionsTest {

    @TempDir
    Path root;

    @Test
    void theModuleOverridesTheReleaseInheritedFromTheRootPom() throws IOException {
        Path rootPom = write("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release>"
                + "</properties></project>");
        Path modulePom = write("web/pom.xml", "<project><properties><maven.compiler.release>21"
                + "</maven.compiler.release></properties></project>");

        MavenCompilerOptions options = MavenCompilerOptions.of(modulePom, rootPom);

        assertEquals(Optional.of(21), options.releaseMajor(false));
        assertTrue(options.isSupported());
    }

    @Test
    void pluginConfigurationWinsOverPropertiesAndIsInterpolated() throws IOException {
        Path rootPom = write("pom.xml", """
                <project><properties><jdk>11</jdk><maven.compiler.release>8</maven.compiler.release></properties>
                <build><pluginManagement><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                <configuration><release>${jdk}</release><testRelease>17</testRelease>
                <encoding>windows-1252</encoding></configuration></plugin></plugins></pluginManagement></build>
                </project>""");
        Path modulePom = write("web/pom.xml", "<project/>");

        MavenCompilerOptions options = MavenCompilerOptions.of(modulePom, rootPom);

        assertEquals(Optional.of(11), options.releaseMajor(false));
        assertEquals(Optional.of(17), options.releaseMajor(true));
        assertEquals("windows-1252", options.encoding());
    }

    @Test
    void sourceAndTargetAreKeptWhenThereIsNoRelease() throws IOException {
        Path pom = write("pom.xml", "<project><properties><maven.compiler.source>1.8</maven.compiler.source>"
                + "<maven.compiler.target>1.8</maven.compiler.target></properties></project>");

        MavenCompilerOptions options = MavenCompilerOptions.of(pom, pom);

        assertTrue(options.releaseMajor(false).isEmpty());
        assertEquals(Optional.of("1.8"), options.sourceLevel(false));
        assertEquals(Optional.of("1.8"), options.targetLevel(false));
        assertTrue(options.declaresLanguageLevel(false));
    }

    @Test
    void anUnresolvedLanguageLevelFallsBackToTheProjectDefault() throws IOException {
        Path pom = write("pom.xml", "<project><properties><maven.compiler.release>${java.version}"
                + "</maven.compiler.release></properties></project>");

        MavenCompilerOptions options = MavenCompilerOptions.of(pom, pom);

        assertFalse(options.declaresLanguageLevel(false));
        assertEquals("UTF-8", options.encoding());
        assertEquals("full", options.proc());
        assertTrue(options.isSupported());
    }

    @Test
    void theSpringBootParentTurnsParametersOn() throws IOException {
        Path pom = write("pom.xml", "<project><parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId><version>3.3.0</version></parent></project>");

        assertTrue(MavenCompilerOptions.of(pom, pom).parameters());
    }

    @Test
    void compilerArgumentsProcessorsAndPreviewArePassedThrough() throws IOException {
        Path pom = write("pom.xml", """
                <project><groupId>g</groupId><artifactId>app</artifactId>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration>
                <enablePreview>true</enablePreview><proc>none</proc>
                <annotationProcessors><annotationProcessor>a.Processor</annotationProcessor></annotationProcessors>
                <compilerArgs><arg>-Xlint:all</arg><arg>-Aproject=${project.groupId}/${project.artifactId}</arg>
                </compilerArgs></configuration></plugin></plugins></build></project>""");

        MavenCompilerOptions options = MavenCompilerOptions.of(pom, pom);

        assertTrue(options.isSupported());
        assertTrue(options.enablePreview());
        assertEquals("none", options.proc());
        assertEquals(List.of("a.Processor"), options.processors());
        assertEquals(List.of("-Xlint:all", "-Aproject=g/app"), options.compilerArgs());
    }

    @Test
    void configurationJavacCannotReproduceIsReportedAsUnsupported() throws IOException {
        assertFalse(options("<compilerId>eclipse</compilerId>").isSupported());
        assertFalse(options("<fork>true</fork><executable>C:/jdk8/bin/javac</executable>").isSupported());
        assertFalse(options("<compilerArgs><arg>-proc:none</arg></compilerArgs>").isSupported());
        assertFalse(options("<compilerArgs><arg>-J-Xmx2g</arg></compilerArgs>").isSupported());
        assertFalse(options("<compilerArgs><arg>-Adir=${undefined.dir}</arg></compilerArgs>").isSupported());
        assertTrue(options("<fork>true</fork>").isSupported());
        assertTrue(options("<compilerId>javac</compilerId>").isSupported());
    }

    private MavenCompilerOptions options(String configuration) throws IOException {
        Path pom = write("pom.xml", "<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration>" + configuration + "</configuration></plugin></plugins></build></project>");
        return MavenCompilerOptions.of(pom, pom);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
