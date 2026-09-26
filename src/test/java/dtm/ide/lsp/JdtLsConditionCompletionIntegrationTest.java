package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.debug.ConditionSyntheticSource;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledIfSystemProperty(named = "orion.it.jdtls", matches = "true")
class JdtLsConditionCompletionIntegrationTest {

    private static final long READY_TIMEOUT_MS = 300_000;

    @TempDir
    Path workspace;

    private JdtLsService service;

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void aConditionDocumentThatOnlyExistsInMemoryGetsCompletionAndDiagnostics() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("conditions"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>conditions</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        Path file = Files.createDirectories(project.resolve("src/main/java/demo")).resolve("Demo.java");
        String source = """
                package demo;
                public class Demo {
                    private int total;
                    private String label = "x";
                    public Demo() {}
                    void run(int count) {
                        total += count;
                    }
                }
                """;
        Files.writeString(file, source);

        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        var jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "a JDK is required");
        Path sdk = integrationSdk(jdks);
        var provisioner = new JdtLsProvisioner(new SdkDownloader(null), sdk);
        assumeTrue(provisioner.find().isPresent(), "set -Dorion.it.sdk to an SDK with JDT LS installed");
        service = new JdtLsService(jdks, provisioner, null, null);
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        await(() -> !service.isWarmingUp(), 30_000);
        service.openDocument(file, source);

        ConditionSyntheticSource members = ConditionSyntheticSource.build(file, source, 6, "this.");
        List<AutoCompleteItem> items = service.complete(members.path(), members.text(),
                members.toSyntheticLine(0), members.toSyntheticCol(0, 5),
                JdtLsService.CompletionTrigger.TRIGGER_CHARACTER, '.', JdtLsService.ANY_VERSION);
        List<String> labels = items.stream().map(AutoCompleteItem::label).toList();
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("total")), labels.toString());
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("label")), labels.toString());

        ConditionSyntheticSource locals = ConditionSyntheticSource.build(file, source, 6, "cou");
        List<String> localLabels = service.complete(locals.path(), locals.text(),
                locals.toSyntheticLine(0), locals.toSyntheticCol(0, 3)).stream()
                .map(AutoCompleteItem::label).toList();
        assertTrue(localLabels.stream().anyMatch(label -> label.startsWith("count")), localLabels.toString());

        ConditionSyntheticSource unknown = ConditionSyntheticSource.build(file, source, 6, "undefinedVar > 1");
        service.changeDocument(unknown.path(), unknown.text());
        await(() -> hasErrorOn(unknown), 30_000);

        ConditionSyntheticSource notBoolean = ConditionSyntheticSource.build(file, source, 6, "count + 1");
        service.changeDocument(notBoolean.path(), notBoolean.text());
        await(() -> hasErrorOn(notBoolean), 30_000);

        ConditionSyntheticSource valid = ConditionSyntheticSource.build(file, source, 6, "count > total");
        service.changeDocument(valid.path(), valid.text());
        await(() -> !hasErrorOn(valid), 30_000);

        service.closeDocument(valid.path());
        assertFalse(Files.exists(valid.path()), "o documento sintetico nunca pode ir para o disco");
        await(() -> service.diagnostics(file).stream()
                .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR), 30_000);
    }

    private boolean hasErrorOn(ConditionSyntheticSource synthetic) {
        Collection<Diagnostic> diagnostics = service.diagnostics(synthetic.path());
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR
                && synthetic.coversSyntheticLine(diagnostic.startLine()));
    }

    private static Path integrationSdk(JdkService jdks) {
        String configured = System.getProperty("orion.it.sdk", "");
        return configured.isBlank() ? jdks.sdkRoot() : Path.of(configured).toAbsolutePath().normalize();
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condicao nao atingida a tempo");
    }

    private static Resource resourceAt(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new Resource() {
            @Override
            public Path getResourcePath() {
                return directory;
            }

            @Override
            public Path getResourcePath(String path) {
                return directory.resolve(path);
            }

            @Override
            public Path getResourcePath(Path path) {
                return directory.resolve(path);
            }

            @Override
            public URL getResource(String name) {
                return null;
            }

            @Override
            public List<URL> getResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getResourcesAsStreams(Collection<String> name) {
                return List.of();
            }

            @Override
            public Path getSharedResourcePath() {
                return directory;
            }

            @Override
            public URL getSharedResource(String name) {
                return null;
            }

            @Override
            public List<URL> getSharedResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getSharedResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getSharedResourcesAsStreams(Collection<String> name) {
                return List.of();
            }
        };
    }
}
