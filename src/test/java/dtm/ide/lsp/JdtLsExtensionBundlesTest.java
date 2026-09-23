package dtm.ide.lsp;

import dtm.ide.sdk.SdkDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsExtensionBundlesTest {

    @TempDir
    Path sdkRoot;

    @Test
    void loadsEveryJavaExtensionDeclaredByTheTestRunner() throws Exception {
        Path extension = sdkRoot.resolve("java-test").resolve("0.46.0").resolve("extension");
        Path server = Files.createDirectories(extension.resolve("server"));
        Path plugin = Files.createFile(server.resolve("com.microsoft.java.test.plugin-0.43.1.jar"));
        Path runtime = Files.createFile(server.resolve("org.eclipse.jdt.junit4.runtime_1.4.0.jar"));
        Files.writeString(extension.resolve("package.json"), """
                {
                  "contributes": {
                    "javaExtensions": [
                      "./server/com.microsoft.java.test.plugin-0.43.1.jar",
                      "./server/org.eclipse.jdt.junit4.runtime_1.4.0.jar"
                    ]
                  }
                }
                """);

        JdtLsExtensionBundles bundles = new JdtLsExtensionBundles(
                new SdkDownloader(null), sdkRoot);

        assertEquals(List.of(plugin.toAbsolutePath().normalize(), runtime.toAbsolutePath().normalize()),
                bundles.findJavaTestExtensions());
        assertTrue(bundles.resolveBundlePaths(false).contains(runtime.toString()));
    }

    @Test
    void dropsBundlesTheLanguageServerAlreadyShips() throws Exception {
        Path server = testRunnerServerDir();
        Path plugin = Files.createFile(server.resolve("com.microsoft.java.test.plugin-0.43.1.jar"));
        Path clash = Files.createFile(server.resolve("org.objectweb.asm_9.10.1.jar"));
        declareJavaExtensions("./server/com.microsoft.java.test.plugin-0.43.1.jar",
                "./server/org.objectweb.asm_9.10.1.jar");
        Files.createFile(jdtlsPlugins().resolve("org.objectweb.asm_9.10.1.jar"));

        List<String> resolved = bundles().resolveBundlePaths(false);

        assertTrue(resolved.contains(plugin.toString()));
        assertFalse(resolved.contains(clash.toString()));
    }

    @Test
    void keepsABundleWhoseVersionDiffersFromTheInstalledOne() throws Exception {
        Path server = testRunnerServerDir();
        Path newer = Files.createFile(server.resolve("org.objectweb.asm_9.10.1.jar"));
        declareJavaExtensions("./server/org.objectweb.asm_9.10.1.jar");
        Files.createFile(jdtlsPlugins().resolve("org.objectweb.asm_9.7.0.jar"));

        assertTrue(bundles().resolveBundlePaths(false).contains(newer.toString()));
    }

    @Test
    void identifiesBundlesByManifestRatherThanFileName() throws Exception {
        Path server = testRunnerServerDir();
        Path candidate = server.resolve("asm-shaded.jar");
        writeBundleJar(candidate, "org.objectweb.asm;singleton:=true", "9.10.1");
        declareJavaExtensions("./server/asm-shaded.jar");
        writeBundleJar(jdtlsPlugins().resolve("org.objectweb.asm_9.10.1.jar"),
                "org.objectweb.asm", "9.10.1");

        assertFalse(bundles().resolveBundlePaths(false).contains(candidate.toString()));
    }

    @Test
    void pluginsFollowingTheEclipseNamingAreIdentifiedWithoutOpeningTheJar() throws Exception {
        Path server = testRunnerServerDir();
        Path candidate = Files.createFile(server.resolve("org.objectweb.asm_9.10.1.jar"));
        declareJavaExtensions("./server/org.objectweb.asm_9.10.1.jar");
        writeBundleJar(jdtlsPlugins().resolve("org.objectweb.asm_9.10.1.jar"),
                "manifesto.que.nao.deveria.ser.lido", "0.0.1");

        assertFalse(bundles().resolveBundlePaths(false).contains(candidate.toString()));
    }

    private JdtLsExtensionBundles bundles() {
        return new JdtLsExtensionBundles(new SdkDownloader(null), sdkRoot);
    }

    private Path testRunnerServerDir() throws Exception {
        return Files.createDirectories(sdkRoot.resolve("java-test").resolve("0.46.0")
                .resolve("extension").resolve("server"));
    }

    private Path jdtlsPlugins() throws Exception {
        return Files.createDirectories(sdkRoot.resolve("jdtls")
                .resolve(JdtLsProvisioner.DEFAULT_VERSION).resolve("plugins"));
    }

    private void declareJavaExtensions(String... relativePaths) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (String relative : relativePaths) {
            if (!entries.isEmpty()) {
                entries.append(", ");
            }
            entries.append('"').append(relative).append('"');
        }
        Files.writeString(sdkRoot.resolve("java-test").resolve("0.46.0").resolve("extension")
                        .resolve("package.json"),
                """
                { "contributes": { "javaExtensions": [ %s ] } }
                """.formatted(entries));
    }

    private static void writeBundleJar(Path jar, String symbolicName, String version)
            throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Bundle-SymbolicName", symbolicName);
        attributes.putValue("Bundle-Version", version);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.flush();
        }
    }
}
