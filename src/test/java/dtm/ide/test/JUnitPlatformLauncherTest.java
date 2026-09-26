package dtm.ide.test;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitPlatformLauncherTest {

    @TempDir
    Path root;

    @Test
    void thePlatformVersionComesFromTheEngineJarOnTheClasspath() {
        String classpath = String.join(File.pathSeparator,
                "/repo/org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar",
                "/repo/org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar");

        assertEquals(Optional.of("1.11.4"), JUnitPlatformLauncher.platformVersion(classpath));
        assertTrue(JUnitPlatformLauncher.hasJUnitEngine(classpath));
        assertFalse(JUnitPlatformLauncher.usesTestNg(classpath));
    }

    @Test
    void aTestNgProjectIsNotRunOnThePlatform() {
        assertTrue(JUnitPlatformLauncher.usesTestNg("/repo/org/testng/testng/7.10.2/testng-7.10.2.jar"));
    }

    @Test
    void onlyNoArgumentMethodsCanBeSelectedDirectly() throws Exception {
        Path source = Files.writeString(root.resolve("CalculoTest.java"), """
                class CalculoTest {
                    @Test void soma() {}
                    @ParameterizedTest @ValueSource(ints = 1) void varios(int valor) {}
                }
                """);

        assertTrue(JUnitPlatformLauncher.selectable(List.of(test(source, "soma", false))));
        assertFalse(JUnitPlatformLauncher.selectable(List.of(test(source, "varios", false))));
        assertFalse(JUnitPlatformLauncher.selectable(List.of(test(source, "soma", true))));
        assertTrue(JUnitPlatformLauncher.selectable(List.of(test(source, "", false))));
    }

    @Test
    void customizedSurefireKeepsTheMavenRun() throws Exception {
        Path plain = Files.writeString(root.resolve("plain.xml"), """
                <project><build><plugins><plugin>
                <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
                </plugin></plugins></build></project>
                """);
        Path custom = Files.writeString(root.resolve("custom.xml"), """
                <project><build><plugins><plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration><argLine>-Xmx1g</argLine></configuration>
                </plugin></plugins></build></project>
                """);

        assertFalse(JUnitPlatformLauncher.surefireCustomized(plain));
        assertTrue(JUnitPlatformLauncher.surefireCustomized(plain, custom));
    }

    @Test
    void theCommandSelectsTheRequestedTestsAndWritesSurefireStyleReports() {
        JdkInstallation jdk = new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21,
                "21.0.4", JdkInstallation.JdkOrigin.MANAGED);
        Path source = root.resolve("CalculoTest.java");

        List<String> command = JUnitPlatformLauncher.command(jdk, "1.11.4", "a.jar",
                List.of(root.resolve("console.jar")), List.of("-Dx=1"),
                List.of(test(source, "soma", false), test(source, "", false)),
                root, root.resolve("target/test-classes"), root.resolve("target/surefire-reports"));

        assertEquals(jdk.javaExecutable().toString(), command.getFirst());
        assertTrue(command.contains("-Dx=1"));
        assertTrue(command.contains("execute"));
        assertTrue(command.contains("--select-method=demo.CalculoTest#soma"));
        assertTrue(command.contains("--select-class=demo.CalculoTest"));
        assertTrue(command.contains("--reports-dir=" + root.resolve("target/surefire-reports")));
        assertEquals("a.jar" + File.pathSeparator + root.resolve("console.jar"),
                command.get(command.indexOf("-cp") + 1));
    }

    @Test
    void olderPlatformsRunWithoutTheExecuteSubcommand() {
        assertFalse(JUnitPlatformLauncher.supportsSubcommands("1.9.3"));
        assertTrue(JUnitPlatformLauncher.supportsSubcommands("1.10.0"));
        assertTrue(JUnitPlatformLauncher.supportsSubcommands("6.0.0"));
    }

    private static JavaTest test(Path file, String method, boolean parameterized) {
        return new JavaTest("demo.CalculoTest", method, "", file, 1, parameterized);
    }
}
