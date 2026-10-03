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

    @Test
    void shutdownAlsoTerminatesAProcessAlreadyRetiringAsynchronously() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java";
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), IdleServer.class.getName()).start();
        JdtLsService service = new JdtLsService(null, null, null, null);
        try {
            assertEquals('R', child.getInputStream().read());
            var processField = JdtLsService.class.getDeclaredField("process");
            processField.setAccessible(true);
            processField.set(service, child);

            service.stopAsync();
            service.shutdown();

            assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
            service.shutdown();
        }
    }

    @Test
    void deletingASourceDoesNotReimportTheMavenProject() {
        assertFalse(JdtLsService.affectsProjectStructure(Path.of("/p/src/main/java/a/App.java")));
        assertTrue(JdtLsService.affectsProjectStructure(Path.of("/p/modulo/pom.xml")));
        assertTrue(JdtLsService.affectsProjectStructure(Path.of("/p/src/test")));
        assertFalse(JdtLsService.affectsProjectStructure(Path.of("/p/src/main/java/a/servico")));
    }

    @Test
    void theCompletionCacheIsKeyedByTheTextBeforeTheWordBeingTyped() {
        String text = "class A {\n    void m() { lista.st }\n}";

        assertEquals("    void m() { lista.", JdtLsService.linePrefixAtWordStart(text, 1, 22));
        assertEquals("    void m() { lista.", JdtLsService.linePrefixAtWordStart(text, 1, 21));
        assertEquals("    void m() { ", JdtLsService.linePrefixAtWordStart(text, 1, 20));
    }

    @Test
    void reusesTheCachedCompletionOnlyWhenTheWordWasExtendedAtTheCaret() {
        String cached = "class A {\n    void m() { Str }\n}";
        String typed = "class A {\n    void m() { Strin }\n}";

        assertTrue(JdtLsService.extendsCachedWord(cached, 1, 18, typed, 20));
        assertTrue(JdtLsService.extendsCachedWord(cached, 1, 18, cached, 18));
        assertFalse(JdtLsService.extendsCachedWord(cached, 1, 18,
                "class A {\n    void m() { Str. }\n}", 19));
        assertFalse(JdtLsService.extendsCachedWord(cached, 1, 18,
                "import x.Y;\nclass A {\n    void m() { Strin }\n}", 20));
        assertFalse(JdtLsService.extendsCachedWord(cached, 1, 18,
                "class A {\n    void m() { St }\n}", 17));
        assertFalse(JdtLsService.extendsCachedWord(cached, 1, 18,
                "class A {\n    void m() { Strin }\n}\n", 20));
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
    void aModernHotSpotServerBootsWithTheSharedArchiveAndWithoutScanningForJdks() {
        List<String> command = commandFor(JdkVendor.TEMURIN, 21);

        assertTrue(command.contains("-DDetectVMInstallationsJob.disabled=true"));
        assertTrue(command.contains("-XX:+UseParallelGC"));
        assertTrue(command.contains("-XX:+AutoCreateSharedArchive"));
        assertTrue(command.contains("-XX:SharedArchiveFile="
                + root.resolve("jdtls").toAbsolutePath().normalize().resolve("jdtls-jdk21.jsa")));
        assertTrue(command.indexOf("-XX:+AutoCreateSharedArchive") < command.indexOf("-jar"));
    }

    @Test
    void aServerJdkOlderThanNineteenSkipsTheSharedArchive() {
        List<String> command = commandFor(JdkVendor.TEMURIN, 17);

        assertTrue(command.contains("-XX:+UseParallelGC"));
        assertTrue(command.stream().noneMatch(argument -> argument.contains("SharedArchive")));
    }

    @Test
    void anOpenJ9ServerReceivesNoHotSpotOptions() {
        List<String> command = commandFor(JdkVendor.SEMERU, 21);

        assertTrue(command.stream().noneMatch(argument -> argument.startsWith("-XX:")));
        assertTrue(command.contains("-DDetectVMInstallationsJob.disabled=true"));
    }

    @Test
    void theLombokAgentNeverShipsTogetherWithTheSharedArchive() {
        JdtLsService service = new JdtLsService(null, null, null, null);
        service.setLombokAgentJar(root.resolve("lombok.jar"));

        List<String> command = service.buildCommand(hotSpot(21), installation(), root.resolve("ws"));

        assertTrue(command.contains("-javaagent:" + root.resolve("lombok.jar")));
        assertTrue(command.stream().noneMatch(argument -> argument.contains("SharedArchive")));
    }

    @Test
    void theRealJvmAcceptsTheServerOptionsWithAndWithoutAnAgent() throws Exception {
        String vmName = System.getProperty("java.vm.name", "");
        JdkInstallation runtime = new JdkInstallation(Path.of(System.getProperty("java.home")),
                vmName.contains("OpenJ9") ? JdkVendor.SEMERU : JdkVendor.TEMURIN,
                Runtime.version().feature(), System.getProperty("java.version"),
                JdkInstallation.JdkOrigin.MANAGED);
        Path agent = noOpAgentJar();

        for (Path lombok : java.util.Arrays.asList(agent, null)) {
            Path home = Files.createDirectories(
                    root.resolve(lombok == null ? "jdtls-sem-agente" : "jdtls-com-agente"));
            JdtLsService service = new JdtLsService(null, null, null, null);
            service.setLombokAgentJar(lombok);
            List<String> command = service.buildCommand(runtime,
                    new JdtLsProvisioner.JdtLsInstallation(home, home.resolve("launcher.jar"),
                            home.resolve("config_win")),
                    root.resolve("ws"));
            List<String> vmOnly = new ArrayList<>(command.subList(0, command.indexOf("-jar")));
            vmOnly.set(0, javaExecutable());
            vmOnly.add("-version");

            Process jvm = new ProcessBuilder(vmOnly).redirectErrorStream(true).start();
            String output = new String(jvm.getInputStream().readAllBytes());

            assertTrue(jvm.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, jvm.exitValue(), "agente=" + lombok + " saida=" + output);
        }
    }

    @Test
    void aRunningServerStartedWithAnotherAgentNeedsARestart() throws Exception {
        Process child = new ProcessBuilder(javaExecutable(), "-cp",
                System.getProperty("java.class.path"), IdleServer.class.getName()).start();
        JdtLsService service = new JdtLsService(null, null, null, null);
        try {
            assertEquals('R', child.getInputStream().read());
            service.setLombokAgentJar(root.resolve("lombok-1.jar"));
            service.buildCommand(hotSpot(21), installation(), root.resolve("ws"));
            var processField = JdtLsService.class.getDeclaredField("process");
            processField.setAccessible(true);
            processField.set(service, child);

            assertFalse(service.needsRestartForLombokAgent());
            service.setLombokAgentJar(root.resolve("lombok-2.jar"));
            assertTrue(service.needsRestartForLombokAgent());
        } finally {
            child.destroyForcibly();
            service.shutdown();
        }
    }

    public static class NoOpAgent implements java.lang.instrument.ClassFileTransformer {
        public static void premain(String arguments, java.lang.instrument.Instrumentation instrumentation) {
            instrumentation.addTransformer(new NoOpAgent());
        }
    }

    private Path noOpAgentJar() throws Exception {
        String entry = NoOpAgent.class.getName().replace('.', '/') + ".class";
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", NoOpAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        Path jar = root.resolve("noop-agent.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
             var in = NoOpAgent.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new java.util.jar.JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
        return jar;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private JdkInstallation hotSpot(int major) {
        return new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, major,
                major + ".0.1", JdkInstallation.JdkOrigin.MANAGED);
    }

    private JdtLsProvisioner.JdtLsInstallation installation() {
        return new JdtLsProvisioner.JdtLsInstallation(
                root.resolve("jdtls").toAbsolutePath().normalize(),
                root.resolve("jdtls/plugins/launcher.jar"),
                root.resolve("jdtls/config_win"));
    }

    @Test
    void settingTheLombokAgentBeforeTheServerStartsNeedsNoRestart() {
        JdtLsService service = new JdtLsService(null, null, null, null);

        assertTrue(service.setLombokAgentJar(root.resolve("lombok.jar")));
        assertFalse(service.needsRestartForLombokAgent());
    }

    private List<String> commandFor(JdkVendor vendor, int major) {
        JdtLsService service = new JdtLsService(null, null, null, null);
        JdkInstallation runtime = new JdkInstallation(root.resolve("jdk"), vendor, major,
                major + ".0.1", JdkInstallation.JdkOrigin.MANAGED);
        return service.buildCommand(runtime, new JdtLsProvisioner.JdtLsInstallation(
                        root.resolve("jdtls").toAbsolutePath().normalize(),
                        root.resolve("jdtls/plugins/launcher.jar"),
                        root.resolve("jdtls/config_win")),
                root.resolve("workspace"));
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
    void formattingMovesPublishedDiagnosticsAndIdenticalPublicationIsIgnored() {
        List<Path> published = new ArrayList<>();
        JdtLsService service = new JdtLsService(null, null, null, published::add);
        Path source = root.resolve("Moving.java").toAbsolutePath().normalize();
        String original = "class Moving {\n    private int value;\n}";
        service.openDocument(source, original);
        service.settleDiagnostics();
        var params = new ObjectMapper().valueToTree(Map.of(
                "uri", source.toUri().toString(),
                "diagnostics", List.of(Map.of(
                        "range", Map.of(
                                "start", Map.of("line", 1, "character", 16),
                                "end", Map.of("line", 1, "character", 21)),
                        "severity", 2,
                        "message", "unused field",
                        "source", "Java"))));

        service.onPublishDiagnostics(params);
        published.clear();
        service.onPublishDiagnostics(params);
        assertTrue(published.isEmpty());

        service.changeDocument(source, "class Moving {\n\n    private int value;\n}");
        assertEquals(2, service.diagnostics(source).iterator().next().startLine());
        assertEquals(List.of(source), published);

        service.changeDocument(source, "class Moving {\n\n    private int renamed;\n}");
        assertTrue(service.diagnostics(source).isEmpty());
    }

    @Test
    void codeLensRefreshAfterTypingIsDebounced() throws Exception {
        JdtLsService service = new JdtLsService(null, null, null, null);
        Path source = root.resolve("Debounced.java").toAbsolutePath().normalize();
        service.openDocument(source, "class Debounced {}");
        java.util.concurrent.atomic.AtomicInteger refreshed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> latestText =
                new java.util.concurrent.atomic.AtomicReference<>();
        service.setCodeLensRefreshListener(path -> {
            latestText.set(service.documentContent(path));
            refreshed.incrementAndGet();
        });
        try {
            service.changeDocument(source, "class Debounced {");
            service.changeDocument(source, "class Debounced {}");

            assertEquals(0, refreshed.get());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (refreshed.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, refreshed.get());
            assertEquals("class Debounced {}", latestText.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void closingDocumentCancelsPendingCodeLensRefresh() throws Exception {
        JdtLsService service = new JdtLsService(null, null, null, null);
        Path source = root.resolve("Closed.java").toAbsolutePath().normalize();
        service.openDocument(source, "class Closed {}");
        java.util.concurrent.atomic.AtomicInteger refreshed = new java.util.concurrent.atomic.AtomicInteger();
        service.setCodeLensRefreshListener(path -> refreshed.incrementAndGet());
        try {
            service.changeDocument(source, "class Closed { }");
            service.closeDocument(source);
            Thread.sleep(1_000);
            assertEquals(0, refreshed.get());
        } finally {
            service.shutdown();
        }
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

    @Test
    void codeActionsOnlyCarryTheDiagnosticsOfTheRequestedLines() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> diagnostics = List.of(
                json.readTree("{\"message\":\"a\",\"range\":{\"start\":{\"line\":2,\"character\":0},"
                        + "\"end\":{\"line\":2,\"character\":4}}}"),
                json.readTree("{\"message\":\"b\",\"range\":{\"start\":{\"line\":5,\"character\":1},"
                        + "\"end\":{\"line\":7,\"character\":2}}}"),
                json.readTree("{\"message\":\"c\",\"range\":{\"start\":{\"line\":9,\"character\":0},"
                        + "\"end\":{\"line\":9,\"character\":3}}}"));

        List<String> onLineTwo = JdtLsService.diagnosticsIntersecting(diagnostics,
                dtm.stools.component.panels.editor.code.api.Range.point(2, 3))
                .stream().map(node -> node.path("message").asText()).toList();
        List<String> insideTheSpan = JdtLsService.diagnosticsIntersecting(diagnostics,
                dtm.stools.component.panels.editor.code.api.Range.point(6, 0))
                .stream().map(node -> node.path("message").asText()).toList();
        List<String> elsewhere = JdtLsService.diagnosticsIntersecting(diagnostics,
                dtm.stools.component.panels.editor.code.api.Range.point(4, 0))
                .stream().map(node -> node.path("message").asText()).toList();

        assertEquals(List.of("a"), onLineTwo);
        assertEquals(List.of("b"), insideTheSpan);
        assertTrue(elsewhere.isEmpty());
    }

    @Test
    void completionItemsFollowTheServerSortTextBeforeTruncating() throws Exception {
        StringBuilder json = new StringBuilder("{\"isIncomplete\":false,\"items\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) json.append(',');
            json.append("{\"label\":\"noise").append(i)
                    .append("\",\"kind\":6,\"sortText\":\"999999").append(100 + i).append("\"}");
        }
        json.append(",{\"label\":\"second\",\"kind\":6,\"sortText\":\"000000002\"}");
        json.append(",{\"label\":\"first\",\"kind\":6,\"sortText\":\"000000001\"}");
        json.append("]}");

        JdtLsService.CompletionAnswer answer = JdtLsService.completionItems(
                new ObjectMapper().readTree(json.toString()));

        assertEquals("first", answer.items().get(0).label());
        assertEquals("second", answer.items().get(1).label());
        assertEquals("noise0", answer.items().get(2).label());
        assertEquals(80, answer.items().size());
        assertTrue(answer.incomplete());
    }

    @Test
    void completionItemsFallBackToLabelWhenSortTextIsMissing() throws Exception {
        JdtLsService.CompletionAnswer answer = JdtLsService.completionItems(new ObjectMapper().readTree(
                "[{\"label\":\"beta\",\"kind\":6},{\"label\":\"alpha\",\"kind\":6}]"));

        assertEquals(List.of("alpha", "beta"), answer.items().stream().map(item -> item.label()).toList());
        assertFalse(answer.incomplete());
    }
}
