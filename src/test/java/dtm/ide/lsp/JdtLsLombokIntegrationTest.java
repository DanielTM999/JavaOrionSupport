package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledIfSystemProperty(named = "orion.it.jdtls", matches = "true")
class JdtLsLombokIntegrationTest {

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
    void lombokMembersReachCompletionHoverAndNavigation() throws Exception {
        Path project = copyFixture("lombok-maven");
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);
        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        Optional<JdkInstallation> jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "o teste de integracao precisa de uma JDK 21 ou mais nova");

        JdtLsProvisioner provisioner = new JdtLsProvisioner(new SdkDownloader(null), jdks.sdkRoot());
        assumeTrue(provisioner.find().isPresent(),
                "o teste de integracao precisa do Eclipse JDT LS ja provisionado");

        LombokAgentResolver.Agent agent = new LombokAgentResolver(jdks.sdkRoot())
                .resolveAgent(descriptor, List.of());
        assumeTrue(agent.isUsable(), "o agente do Lombok nao esta disponivel neste ambiente");

        AtomicInteger diagnosticPublications = new AtomicInteger();
        service = new JdtLsService(jdks, provisioner,
                new JdtLsExtensionBundles(new SdkDownloader(null), jdks.sdkRoot()),
                path -> diagnosticPublications.incrementAndGet());
        service.setLombokAgentJar(agent.jar());
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assumeTrue(service.awaitReady(READY_TIMEOUT_MS), "o JDT LS nao ficou pronto a tempo");

        Path source = project.resolve("src/main/java/demo/CustomerService.java");
        String text = Files.readString(source);
        String anchor = "        customer.setLoyaltyPoints(10);";
        String edited = text.replace(anchor, "        customer." + System.lineSeparator() + anchor);
        int line = lineOf(edited, "        customer." + System.lineSeparator());
        service.openDocument(source, edited);

        List<AutoCompleteItem> items = service.complete(source, edited, line, 18,
                JdtLsService.CompletionTrigger.TRIGGER_CHARACTER, '.', JdtLsService.ANY_VERSION);
        List<String> labels = items.stream().map(AutoCompleteItem::label).toList();

        assertTrue(labels.stream().anyMatch(label -> label.startsWith("getName")),
                "faltou o getter gerado pelo Lombok: " + labels);
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("setLoyaltyPoints")),
                "faltou o setter gerado pelo Lombok: " + labels);

        service.changeDocument(source, text);
        int builderLine = lineOf(text, "Customer.builder()");
        int builderColumn = text.lines().toList().get(builderLine).indexOf("builder") + 2;

        HoverInfo hover = service.hover(source, text, builderLine, builderColumn);
        assertNotNull(hover, "o hover do builder() gerado deveria responder");

        List<Location> definitions = service.definitionsInteractive(source, text,
                builderLine, builderColumn);
        assertTrue(!definitions.isEmpty(), "builder() gerado deveria ter destino de navegacao");

        service.changeDocument(source, "@" + text);
        service.changeDocument(source, text);
        int publicationsAfterRapidEdit = diagnosticPublications.get();
        await(() -> diagnosticPublications.get() > publicationsAfterRapidEdit, 10_000);
        assertTrue(service.diagnostics(source).isEmpty(),
                "o diagnostico do snapshot invalido nao pode sobreviver ao texto valido");

        service.closeDocument(source);
        assertEquals(JdtLsService.ANY_VERSION, service.documentVersion(source));
        service.stop();
        assertEquals(JdtLsService.State.STOPPED, service.getState());

        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assumeTrue(service.awaitReady(READY_TIMEOUT_MS),
                "o JDT LS nao reiniciou depois do ciclo de parada");
        service.openDocument(source, text);
        assertTrue(service.documentVersion(source) > JdtLsService.ANY_VERSION);
    }

    private static int lineOf(String text, String needle) {
        List<String> lines = text.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(needle.strip())) {
                return index;
            }
        }
        throw new AssertionError("linha nao encontrada: " + needle);
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "o JDT LS nao publicou o diagnostico final a tempo");
    }

    private Path copyFixture(String name) throws IOException {
        Path origin = LombokFixturesTest.fixture(name);
        Path target = workspace.resolve(name);
        try (Stream<Path> tree = Files.walk(origin)) {
            tree.forEach(path -> {
                Path destination = target.resolve(origin.relativize(path).toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return target;
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
