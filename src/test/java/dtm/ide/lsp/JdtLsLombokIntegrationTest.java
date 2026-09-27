package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        JdtLsProvisioner provisioner = new JdtLsProvisioner(new SdkDownloader(null), integrationSdk(jdks));
        assumeTrue(provisioner.find().isPresent(),
                "o teste de integracao precisa do Eclipse JDT LS ja provisionado");

        LombokAgentResolver.Agent agent = new LombokAgentResolver(integrationSdk(jdks))
                .resolveAgent(descriptor, List.of());
        assumeTrue(agent.isUsable(), "o agente do Lombok nao esta disponivel neste ambiente");

        AtomicInteger diagnosticPublications = new AtomicInteger();
        service = new JdtLsService(jdks, provisioner,
                new JdtLsExtensionBundles(new SdkDownloader(null), integrationSdk(jdks)),
                path -> diagnosticPublications.incrementAndGet());
        service.setLombokAgentJar(agent.jar());
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assumeTrue(service.awaitReady(READY_TIMEOUT_MS), "o JDT LS nao ficou pronto a tempo");

        Path source = project.resolve("src/main/java/demo/CustomerService.java");
        String text = Files.readString(source);
        int initialPublications = diagnosticPublications.get();
        service.openDocument(source, text);
        await(() -> diagnosticPublications.get() > initialPublications && !service.isWarmingUp(), 30_000);
        String anchor = "        customer.setLoyaltyPoints(10);";
        String edited = text.replace(anchor, "        customer." + System.lineSeparator() + anchor);
        int line = lineOf(edited, "        customer." + System.lineSeparator());
        service.changeDocument(source, edited);

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

    @Test
    void renamingALombokFieldRenamesGeneratedMethodCallsAcrossTheProject() throws Exception {
        Path project = copyFixture("lombok-maven");
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);
        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        Optional<JdkInstallation> jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "o teste de integracao precisa de uma JDK 21 ou mais nova");
        JdtLsProvisioner provisioner = new JdtLsProvisioner(new SdkDownloader(null), integrationSdk(jdks));
        assumeTrue(provisioner.find().isPresent(), "o teste de integracao precisa do Eclipse JDT LS ja provisionado");
        LombokAgentResolver.Agent agent = new LombokAgentResolver(integrationSdk(jdks))
                .resolveAgent(descriptor, List.of());
        assumeTrue(agent.isUsable(), "o agente do Lombok nao esta disponivel neste ambiente");
        Path sources = project.resolve("src/main/java/demo");
        Path funcionario = sources.resolve("Funcionario.java");
        Files.writeString(funcionario, """
                package demo;

                import lombok.*;

                @Data
                @Builder(toBuilder = true)
                @With
                @NoArgsConstructor
                @AllArgsConstructor
                public class Funcionario {
                    private Long idFuncionario;
                    private boolean ativo;
                    private Boolean gerente;
                }
                """);
        Path uso = sources.resolve("UsoFuncionario.java");
        Files.writeString(uso, """
                package demo;

                public class UsoFuncionario {
                    String rodar() {
                        Funcionario f = Funcionario.builder().idFuncionario(1L).ativo(true).gerente(false).build();
                        f.setIdFuncionario(2L);
                        f.setAtivo(false);
                        Funcionario g = f.withIdFuncionario(3L).withAtivo(true).toBuilder().build();
                        String idFuncionario = "idFuncionario(1L)";
                        return f.getIdFuncionario() + " " + f.isAtivo() + g.getGerente() + idFuncionario;
                    }
                }
                """);
        service = new JdtLsService(jdks, provisioner,
                new JdtLsExtensionBundles(new SdkDownloader(null), integrationSdk(jdks)), path -> { });
        service.setLombokAgentJar(agent.jar());
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assumeTrue(service.awaitReady(READY_TIMEOUT_MS), "o JDT LS nao ficou pronto a tempo");
        await(() -> !service.isWarmingUp(), 60_000);

        renameLombokField(funcionario, "private Long idFuncionario;", "idFuncionario", "codigo", sources);
        renameLombokField(funcionario, "private boolean ativo;", "ativo", "habilitado", sources);

        String usoText = Files.readString(uso);
        assertTrue(usoText.contains("Funcionario.builder().codigo(1L).habilitado(true).gerente(false).build();"), usoText);
        assertTrue(usoText.contains("f.setCodigo(2L);"), usoText);
        assertTrue(usoText.contains("f.setHabilitado(false);"), usoText);
        assertTrue(usoText.contains("f.withCodigo(3L).withHabilitado(true).toBuilder().build();"), usoText);
        assertTrue(usoText.contains("String idFuncionario = \"idFuncionario(1L)\";"), usoText);
        assertTrue(usoText.contains("f.getCodigo() + \" \" + f.isHabilitado() + g.getGerente() + idFuncionario;"), usoText);
        String serviceText = Files.readString(sources.resolve("CustomerService.java"));
        assertTrue(serviceText.contains(".name(\"Ana\")"), "builder de outra classe nao pode mudar: " + serviceText);
        assertCompilesWithLombok(List.of(funcionario, uso), agent.jar());
    }

    private void renameLombokField(Path file, String context, String name, String newName, Path sources)
            throws Exception {
        String text = Files.readString(file);
        int line = lineOf(text, context);
        int col = text.lines().toList().get(line).indexOf(name);
        service.openDocument(file, text);
        IdeWorkspaceEdit edit = service.renameWorkspace(file, text, line, col, newName);
        assertNull(service.lastRenameProblem(), service.lastRenameProblem());
        List<Path> javaFiles;
        try (Stream<Path> tree = Files.list(sources)) {
            javaFiles = tree.filter(path -> path.toString().endsWith(".java")).toList();
        }
        Path current = file.toAbsolutePath().normalize();
        LombokAccessorRename.Result result = LombokAccessorRename.apply(service, current, text, line, col, newName,
                edit, ignored -> javaFiles, path -> path.equals(current) ? text : readQuietly(path));
        System.out.println("[lombok-rename] " + name + " -> " + newName + " acessores=" + result.accessors()
                + " chamadas=" + result.calls());
        for (IdeWorkspaceEdit.Operation operation : result.edit().operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits) {
                Path target = textEdits.file().toAbsolutePath().normalize();
                String before = target.equals(current) ? text : Files.readString(target);
                Files.writeString(target, TextEditApplier.apply(before, textEdits.edits()));
                if (!target.equals(current)) {
                    service.pathChanged(target);
                }
            }
        }
        String updated = Files.readString(file);
        service.changeDocument(file, updated);
        service.saveDocument(file, updated);
        service.closeDocument(file);
        Thread.sleep(2_000);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private void assertCompilesWithLombok(List<Path> sources, Path lombok) throws IOException {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "a suite precisa rodar sobre uma JDK");
        Path output = Files.createTempDirectory(workspace, "classes");
        List<String> arguments = new java.util.ArrayList<>(List.of("-encoding", "UTF-8", "-d", output.toString(),
                "-cp", lombok.toString(), "-processorpath", lombok.toString()));
        sources.forEach(source -> arguments.add(source.toString()));
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        int status = compiler.run(null, null, errors, arguments.toArray(String[]::new));
        assertEquals(0, status, errors.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Path integrationSdk(JdkService jdks) {
        String configured = System.getProperty("orion.it.sdk", "");
        return configured.isBlank() ? jdks.sdkRoot() : Path.of(configured).toAbsolutePath().normalize();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plain", "maven", "maven-multimodule", "gradle"})
    void navigationAndLensesRespectBindingsInRealProjects(String layout) throws Exception {
        Path project = Files.createDirectories(workspace.resolve(layout));
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>"
                + "<artifactId>navigation</artifactId><version>1</version><properties>"
                + "<maven.compiler.source>21</maven.compiler.source><maven.compiler.target>21</maven.compiler.target>"
                + "</properties></project>";
        Path module = project;
        if (layout.equals("maven")) Files.writeString(project.resolve("pom.xml"), pom);
        if (layout.equals("maven-multimodule")) {
            Files.writeString(project.resolve("pom.xml"), pom.replace("</project>",
                    "<packaging>pom</packaging><modules><module>app</module></modules></project>"));
            module = Files.createDirectories(project.resolve("app"));
            Files.writeString(module.resolve("pom.xml"), pom.replace("<artifactId>navigation</artifactId>",
                    "<artifactId>app</artifactId>"));
        }
        if (layout.equals("gradle")) {
            Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'navigation'\n");
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n"
                    + "java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }\n");
        }
        Path file = Files.createDirectories(module.resolve("src/main/java/demo")).resolve("Demo.java");
        String source = """
                package demo;
                interface Worker { void run(); }
                class WorkerImpl implements Worker { public void run() {} }
                class Demo {
                    int value;
                    void update(int value) { this.value = value; }
                    int read() { return value; }
                    void call(Worker worker) { worker.run(); }
                }
                class Base { int inherited; }
                class Derived extends Base { int read() { return inherited; } }
                class Overloads {
                    void use(int n) {}
                    void use(String n) {}
                    void call() { use(1); use(""); }
                }
                """;
        Files.writeString(file, source);
        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        var jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "a JDK is required");
        var provisioner = new JdtLsProvisioner(new SdkDownloader(null), integrationSdk(jdks));
        assumeTrue(provisioner.find().isPresent(), "set -Dorion.it.sdk to an isolated SDK with JDT LS installed");
        service = new JdtLsService(jdks, provisioner, null, null);
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        await(() -> !service.isWarmingUp(), 15_000);
        service.openDocument(file, source);
        int fieldCol = source.lines().toList().get(5).indexOf("this.value") + 5;
        var field = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.DEFINITION, file, source, 5, fieldCol);
        assertEquals(1, field.locations().size(), field.toString());
        assertEquals(4, field.locations().getFirst().range().start().line());
        int parameterCol = source.lines().toList().get(5).lastIndexOf("value");
        var parameter = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.DEFINITION, file, source, 5, parameterCol);
        assertEquals(5, parameter.locations().getFirst().range().start().line());
        assertEquals(source.lines().toList().get(5).indexOf("value"), parameter.locations().getFirst().range().start().col());
        var usages = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.REFERENCES, file, source, 4, 8);
        assertEquals(2, usages.locations().size(), usages.toString());
        var implementations = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.IMPLEMENTATION, file, source, 1, 11);
        assertEquals(1, implementations.locations().size(), implementations.toString());
        assertEquals(2, implementations.locations().getFirst().range().start().line());
        int runColumn = source.lines().toList().get(7).indexOf("run()");
        var methodImpl = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.IMPLEMENTATION,
                file, source, 7, runColumn);
        assertEquals(1, methodImpl.locations().size(), methodImpl.toString());
        assertEquals(2, methodImpl.locations().getFirst().range().start().line());
        int inheritedColumn = source.lines().toList().get(10).indexOf("inherited");
        var inherited = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.DEFINITION,
                file, source, 10, inheritedColumn);
        assertEquals(1, inherited.locations().size(), inherited.toString());
        assertEquals(9, inherited.locations().getFirst().range().start().line());
        int useColumn = source.lines().toList().get(12).indexOf("use(");
        var overload = service.navigation(dtm.ide.navigation.JavaNavigation.Kind.REFERENCES,
                file, source, 12, useColumn);
        assertEquals(1, overload.locations().size(), overload.toString());
        assertEquals(source.lines().toList().get(14).indexOf("use(1)"),
                overload.locations().getFirst().range().start().col());
        await(() -> service.codeLenses(file, source).stream().anyMatch(lens ->
                lens.status() == dtm.ide.navigation.JavaNavigation.Status.COMPLETE
                        && lens.command().equals("java.show.implementations") && !lens.locations().isEmpty()), 30_000);
        String edited = source.replace("return value;", "return value + value;");
        service.changeDocument(file, edited);
        assertEquals(3, service.navigation(dtm.ide.navigation.JavaNavigation.Kind.REFERENCES,
                file, edited, 4, 8).locations().size());
        service.closeDocument(file);
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
