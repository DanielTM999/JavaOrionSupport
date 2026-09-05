package dtm.ide.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkDetectorTest {

    @TempDir
    Path root;

    @Test
    void readsVersionAndVendorFromReleaseFile() throws IOException {
        Path home = fakeJdk(root.resolve("temurin-21"), """
                JAVA_VERSION="21.0.4"
                IMPLEMENTOR="Eclipse Adoptium"
                IMPLEMENTOR_VERSION="Temurin-21.0.4+7"
                """);

        JdkInstallation installation = JdkDetector
                .inspect(home, JdkInstallation.JdkOrigin.MANAGED)
                .orElseThrow();

        assertEquals(21, installation.major());
        assertEquals("21.0.4", installation.fullVersion());
        assertEquals(JdkVendor.TEMURIN, installation.vendor());
        assertEquals(JdkInstallation.JdkOrigin.MANAGED, installation.origin());
        assertTrue(installation.isJdk());
    }

    @Test
    void normalizesLegacyVersionScheme() throws IOException {
        Path home = fakeJdk(root.resolve("jdk8"), """
                JAVA_VERSION="1.8.0_402"
                IMPLEMENTOR="Oracle Corporation"
                """);

        JdkInstallation installation = JdkDetector
                .inspect(home, JdkInstallation.JdkOrigin.SYSTEM)
                .orElseThrow();

        assertEquals(8, installation.major());
        assertEquals(JdkVendor.ORACLE, installation.vendor());
    }

    @Test
    void fallsBackToPathWhenReleaseHasNoImplementor() throws IOException {
        Path home = fakeJdk(root.resolve("corretto-17"), "JAVA_VERSION=\"17.0.9\"\n");

        assertEquals(JdkVendor.CORRETTO,
                JdkDetector.inspect(home, JdkInstallation.JdkOrigin.SYSTEM).orElseThrow().vendor());
    }

    @Test
    void recognizesMacOsBundleLayout() throws IOException {
        Path bundle = root.resolve("temurin-21.jdk");
        fakeJdk(bundle.resolve("Contents").resolve("Home"), "JAVA_VERSION=\"21.0.1\"\n");

        JdkInstallation installation = JdkDetector
                .inspect(bundle, JdkInstallation.JdkOrigin.SYSTEM)
                .orElseThrow();

        assertEquals(21, installation.major());
        assertTrue(installation.home().endsWith(Path.of("Contents", "Home")));
    }

    @Test
    void skipsDirectoryWithoutJavaExecutable() throws IOException {
        Path empty = Files.createDirectories(root.resolve("leftovers"));

        assertTrue(JdkDetector.inspect(empty, JdkInstallation.JdkOrigin.MANAGED).isEmpty());
    }

    @Test
    void skipsMissingDirectory() {
        assertTrue(JdkDetector.inspect(root.resolve("nope"), JdkInstallation.JdkOrigin.MANAGED).isEmpty());
        assertTrue(JdkDetector.inspect(null, JdkInstallation.JdkOrigin.MANAGED).isEmpty());
    }

    @Test
    void detectsManagedInstallationsUnderTheManagedRoot() throws IOException {
        fakeJdk(root.resolve("temurin-21"), "JAVA_VERSION=\"21.0.4\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        fakeJdk(root.resolve("temurin-17"), "JAVA_VERSION=\"17.0.9\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        List<JdkInstallation> managed = JdkDetector.detect(root).stream()
                .filter(JdkInstallation::isManaged)
                .toList();

        assertEquals(2, managed.size());
        assertEquals(21, managed.getFirst().major(), "a mais nova vem primeiro");
        assertEquals(17, managed.get(1).major());
    }

    @Test
    void jreWithoutJavacIsNotAJdk() throws IOException {
        Path home = root.resolve("jre-21");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve(executable("java")), "");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.4\"\n");

        JdkInstallation installation = JdkDetector
                .inspect(home, JdkInstallation.JdkOrigin.SYSTEM)
                .orElseThrow();

        assertTrue(installation.isUsable());
        assertFalse(installation.isJdk());
    }

    @Test
    void parsesMajorVersionStrings() {
        assertEquals(21, JdkDetector.majorOf("21.0.4"));
        assertEquals(8, JdkDetector.majorOf("1.8.0_402"));
        assertEquals(25, JdkDetector.majorOf("25-ea"));
        assertEquals(11, JdkDetector.majorOf("11"));
        assertNull(JdkDetector.majorOf(""));
        assertNull(JdkDetector.majorOf(null));
        assertNull(JdkDetector.majorOf("sem-numero"));
    }

    @Test
    void installationsSortNewestFirst() {
        JdkInstallation older = new JdkInstallation(root.resolve("a"), JdkVendor.TEMURIN, 17, "17.0.9",
                JdkInstallation.JdkOrigin.SYSTEM);
        JdkInstallation newer = new JdkInstallation(root.resolve("b"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.SYSTEM);

        assertTrue(newer.compareTo(older) < 0);
    }

    static Path fakeJdk(Path home, String releaseContent) throws IOException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve(executable("java")), "");
        Files.writeString(bin.resolve(executable("javac")), "");
        Files.writeString(home.resolve("release"), releaseContent);
        return home;
    }

    private static String executable(String name) {
        return name + Platform.current().executableSuffix();
    }
}
