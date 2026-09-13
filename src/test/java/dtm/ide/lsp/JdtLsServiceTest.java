package dtm.ide.lsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsServiceTest {

    @Test
    void exposesEssentialEditingWhileTheWorkspaceIsIndexing() {
        assertFalse(JdtLsService.isInteractiveState(JdtLsService.State.STARTING));
        assertTrue(JdtLsService.isInteractiveState(JdtLsService.State.INDEXING));
        assertTrue(JdtLsService.isInteractiveState(JdtLsService.State.READY));
        assertFalse(JdtLsService.isInteractiveState(JdtLsService.State.ERROR));
    }

    @TempDir
    Path root;

    @Test
    void stopUnblocksAReaderWaitingOnAProcessPipe() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                IdleServer.class.getName()).start();
        JdtLsService service = new JdtLsService(null, null, null, null);
        try {
            assertEquals('R', child.getInputStream().read());
            LspJsonRpcClient client = new LspJsonRpcClient(child.getInputStream(), child.getOutputStream(),
                    "idle-server-test");
            var processField = JdtLsService.class.getDeclaredField("process");
            processField.setAccessible(true);
            processField.set(service, child);
            var clientField = JdtLsService.class.getDeclaredField("client");
            clientField.setAccessible(true);
            clientField.set(service, client);
            java.util.concurrent.CompletableFuture.runAsync(service::stop)
                    .get(12, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(child.isAlive());
            assertTrue(client.isClosed());
        } finally {
            child.destroyForcibly();
            service.shutdown();
        }
    }

    public static class IdleServer {
        public static void main(String[] args) throws Exception {
            System.out.print("R");
            System.out.flush();
            Thread.sleep(60_000);
        }
    }

    @Test
    void launchCommandPreventsMetadataAtProjectRoot() {
        JdtLsService service = new JdtLsService(null, null, null, null);
        JdkInstallation runtime = new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN,
                21, "21.0.10", JdkInstallation.JdkOrigin.MANAGED);
        Path launcher = root.resolve("jdtls/plugins/launcher.jar");
        Path config = root.resolve("jdtls/config_win");
        Path workspace = root.resolve("project/.orion/jdtls/workspace");

        List<String> command = service.buildCommand(runtime,
                new JdtLsProvisioner.JdtLsInstallation(root.resolve("jdtls"), launcher, config),
                workspace);

        assertTrue(command.contains("-Djava.import.generatesMetadataFilesAtProjectRoot=false"));
        assertEquals(workspace.toString(), command.get(command.indexOf("-data") + 1));
    }

    @Test
    void recognizesOnlyJdtLsProcessesUsingTheSameWorkspace() {
        Path workspace = root.resolve("workspace").toAbsolutePath().normalize();
        String[] matching = {
                "-jar", root.resolve("plugins/org.eclipse.equinox.launcher_1.jar").toString(),
                "-configuration", root.resolve("config_win").toString(),
                "-data", workspace.toString()
        };

        assertTrue(JdtLsService.isJdtLsForWorkspace(
                root.resolve("jdk/bin/java.exe").toString(), matching, workspace));
        assertFalse(JdtLsService.isJdtLsForWorkspace(
                root.resolve("jdk/bin/java.exe").toString(), matching, root.resolve("other")));
        assertFalse(JdtLsService.isJdtLsForWorkspace(
                root.resolve("jdk/bin/java.exe").toString(),
                new String[]{"-data", workspace.toString()}, workspace));
    }

    @Test
    void resolvesScopedWorkspaceConfiguration() {
        Map<String, Object> completion = Map.of("enabled", true);
        Map<String, Object> settings = Map.of("java", Map.of("completion", completion));

        assertEquals(completion, JdtLsService.configurationValue(settings, "java.completion"));
        assertEquals(settings, JdtLsService.configurationValue(settings, ""));
        assertEquals(null, JdtLsService.configurationValue(settings, "java.missing"));
    }

    @Test
    void serializesClasspathOptionsAsTheJdtlsCommandExpects() {
        List<String> arguments = JdtLsService.runtimeClasspathArguments(root);

        assertEquals(root.toUri().toString(), arguments.get(0));
        assertEquals("{\"scope\":\"runtime\"}", arguments.get(1));
    }

    @Test
    void removesOnlyTheLegacyOverlappingWorkspace() throws Exception {
        Path metadata = root.resolve(".orion/jdtls/workspace/.metadata");
        Path searchIndex = root.resolve(".orion/search-index/index.bin");
        Files.createDirectories(metadata);
        Files.createDirectories(searchIndex.getParent());
        Files.writeString(searchIndex, "preservar");

        JdtLsService.removeLegacyOverlappingWorkspace(root);

        assertEquals(false, Files.exists(root.resolve(".orion/jdtls")));
        assertEquals(true, Files.isRegularFile(searchIndex));
    }

    @Test
    void recognizesJdtDocumentDesynchronizationForAutomaticRecovery() {
        assertTrue(JdtLsService.isRecoverableDocumentError(
                "org.eclipse.jface.text.BadLocationException"));
        assertTrue(JdtLsService.isRecoverableDocumentError("BadLocationException"));
        assertFalse(JdtLsService.isRecoverableDocumentError("Failed to import projects"));
        assertFalse(JdtLsService.isRecoverableDocumentError(null));
    }

    @Test
    void recognizesBrokenJavadocDuringCompletionResolve() {
        assertTrue(JdtLsService.isCompletionDocumentationFailure(
                "Unable to read documentation"));
        assertTrue(JdtLsService.isCompletionDocumentationFailure(
                "CompletionResolveHandler: StringIndexOutOfBoundsException"));
        assertFalse(JdtLsService.isCompletionDocumentationFailure(
                "Failed to import projects"));
        assertFalse(JdtLsService.isCompletionDocumentationFailure(null));
    }

    @Test
    void clearsCachedDiagnosticsAndNotifiesAffectedEditors() {
        List<Path> published = new ArrayList<>();
        JdtLsService service = new JdtLsService(null, null, null, published::add);
        Path source = root.resolve("Example.java").toAbsolutePath().normalize();
        var params = new ObjectMapper().valueToTree(Map.of(
                "uri", source.toUri().toString(),
                "diagnostics", List.of(Map.of(
                        "range", Map.of(
                                "start", Map.of("line", 1, "character", 2),
                                "end", Map.of("line", 1, "character", 6)),
                        "severity", 1,
                        "message", "stale diagnostic",
                        "source", "test"))));

        service.onPublishDiagnostics(params);
        assertEquals(1, service.diagnostics(source).size());

        published.clear();
        service.clearDiagnostics();

        assertTrue(service.diagnostics(source).isEmpty());
        assertEquals(List.of(source), published);
        service.clearDiagnostics();
        assertEquals(List.of(source), published);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsMinimalIncrementalDocumentChange() {
        var change = JdtLsService.incrementalDocumentChange(
                "one\ntwo", "one\nthree");
        var range = (java.util.Map<String, Object>) change.get("range");

        assertEquals(java.util.Map.of("line", 1, "character", 1), range.get("start"));
        assertEquals(java.util.Map.of("line", 1, "character", 3), range.get("end"));
        assertEquals(2, change.get("rangeLength"));
        assertEquals("hree", change.get("text"));
    }
}
