package dtm.ide.lsp;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import dtm.ide.settings.JdtBuildMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsSettingsTest {

    @TempDir
    Path root;

    @Test
    @SuppressWarnings("unchecked")
    void enablesCodeLensAndMarksProjectJdkAsDefault() throws Exception {
        JdkInstallation jdk21 = jdk(21);
        JdkInstallation jdk25 = jdk(25);

        Map<String, Object> settings = JdtLsSettings.build(jdk25, List.of(jdk21, jdk25),
                JdtBuildMode.PROJECT_BUILD);
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        Map<String, Object> references = (Map<String, Object>) java.get("referencesCodeLens");
        Map<String, Object> imports = (Map<String, Object>) java.get("import");
        Map<String, Object> configuration = (Map<String, Object>) java.get("configuration");
        List<Map<String, Object>> runtimes =
                (List<Map<String, Object>>) configuration.get("runtimes");

        assertEquals(true, references.get("enabled"));
        assertEquals("all", java.get("implementationCodeLens"));
        assertEquals(false, imports.get("generatesMetadataFilesAtProjectRoot"));
        assertTrue(runtimes.stream().anyMatch(runtime ->
                "JavaSE-25".equals(runtime.get("name")) && Boolean.TRUE.equals(runtime.get("default"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void autobuildFollowsTheConfiguredBuildMode() throws Exception {
        JdkInstallation jdk = jdk(21);

        assertEquals(false, autobuildEnabled(
                JdtLsSettings.build(jdk, List.of(jdk), JdtBuildMode.PROJECT_BUILD)));
        assertEquals(true, autobuildEnabled(
                JdtLsSettings.build(jdk, List.of(jdk), JdtBuildMode.AUTOBUILD_ISOLATED)));
        assertEquals(false, autobuildEnabled(
                JdtLsSettings.build(jdk, List.of(jdk), null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAwtTypesAvailableForImportChoices() throws Exception {
        JdkInstallation jdk = jdk(21);

        Map<String, Object> java = (Map<String, Object>) JdtLsSettings.build(jdk, List.of(jdk),
                JdtBuildMode.PROJECT_BUILD).get("java");
        List<String> filtered = (List<String>) ((Map<String, Object>) java.get("completion"))
                .get("filteredTypes");

        assertTrue(filtered.contains("sun.*"));
        assertTrue(filtered.stream().noneMatch(pattern -> pattern.startsWith("java.")));
    }

    @SuppressWarnings("unchecked")
    private Object autobuildEnabled(Map<String, Object> settings) {
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        return ((Map<String, Object>) java.get("autobuild")).get("enabled");
    }

    private JdkInstallation jdk(int major) throws Exception {
        Path home = root.resolve("jdk-" + major);
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "javac.exe" : "javac"));
        return new JdkInstallation(home, JdkVendor.TEMURIN, major, major + ".0.1",
                JdkInstallation.JdkOrigin.MANAGED);
    }
}
