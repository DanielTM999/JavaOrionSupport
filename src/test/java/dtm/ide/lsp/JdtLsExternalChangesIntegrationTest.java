package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.ide.settings.JdtBuildMode;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
class JdtLsExternalChangesIntegrationTest {

    private static final long READY_TIMEOUT_MS = 300_000;
    private static final String USER = """
            package demo;

            public class User {
                Novo novo = new Novo();
            }
            """;
    private static final String NOVO = """
            package demo;

            public class Novo {
            }
            """;

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
    void aClassCreatedWhileTheServerWasDownIsKnownAfterTheNextStart() throws Exception {
        Path project = createProject("offline");
        Path sources = Files.createDirectories(project.resolve("src/main/java/demo"));
        Path user = Files.writeString(sources.resolve("User.java"), USER);

        startServer(project);
        assertFalse(knowsType("demo.Novo"), "Novo ainda nao existe");
        service.stop();
        service = null;

        Files.writeString(sources.resolve("Novo.java"), NOVO);

        startServer(project);
        await(() -> knowsType("demo.Novo"), 60_000);
        service.openDocument(user, USER);
        await(() -> service.isWorkspaceSettled() && service.diagnostics(user).isEmpty(), 60_000);
        assertFalse(mentionsNovo(service.diagnostics(user)), String.valueOf(service.diagnostics(user)));
    }

    @Test
    void aClassCreatedWhileTheServerRunsResolvesInTheOpenEditor() throws Exception {
        Path project = createProject("live");
        Path sources = Files.createDirectories(project.resolve("src/main/java/demo"));
        Path user = Files.writeString(sources.resolve("User.java"), USER);

        startServer(project);
        service.openDocument(user, USER);
        await(() -> mentionsNovo(service.diagnostics(user)), 60_000);

        Path novo = Files.writeString(sources.resolve("Novo.java"), NOVO);
        service.pathCreated(novo);

        await(() -> !mentionsNovo(service.diagnostics(user)), 15_000);
        assertTrue(knowsType("demo.Novo"));
    }

    private boolean knowsType(String qualifiedName) {
        return service.workspaceTypes("Novo").stream()
                .anyMatch(type -> qualifiedName.equals(type.qualifiedName()));
    }

    private static boolean mentionsNovo(Collection<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.message() != null
                && diagnostic.message().contains("Novo"));
    }

    private Path createProject(String name) throws IOException {
        Path project = Files.createDirectories(workspace.resolve(name));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>" + name + "</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>",
                StandardCharsets.UTF_8);
        return project;
    }

    private void startServer(Path project) throws Exception {
        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        var jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "a JDK is required");
        String configured = System.getProperty("orion.it.sdk", "");
        Path sdk = configured.isBlank() ? jdks.sdkRoot() : Path.of(configured).toAbsolutePath().normalize();
        var provisioner = new JdtLsProvisioner(new SdkDownloader(null), sdk);
        assumeTrue(provisioner.find().isPresent(), "set -Dorion.it.sdk to an SDK with JDT LS installed");
        service = new JdtLsService(jdks, provisioner, null, null);
        service.setBuildMode(JdtBuildMode.AUTOBUILD_ISOLATED);
        service.setSourceRoots(List.of(project.resolve("src/main/java")));
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        await(() -> !service.isWarmingUp(), 60_000);
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
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
