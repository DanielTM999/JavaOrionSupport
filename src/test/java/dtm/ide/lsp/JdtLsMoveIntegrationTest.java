package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.api.TextEdit;
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
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledIfSystemProperty(named = "orion.it.jdtls", matches = "true")
class JdtLsMoveIntegrationTest {

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
    void movingAClassToAnotherPackageUpdatesPackageImportsAndSiblings() throws Exception {
        Path project = createProject("move");
        Path a = Files.createDirectories(project.resolve("src/main/java/demo/a"));
        Path b = Files.createDirectories(project.resolve("src/main/java/demo/b"));
        Path c = Files.createDirectories(project.resolve("src/main/java/demo/c"));
        Path foo = Files.writeString(a.resolve("Foo.java"), """
                package demo.a;

                public class Foo {
                    Sibling sibling() {
                        return new Sibling();
                    }
                }
                """);
        Path sibling = Files.writeString(a.resolve("Sibling.java"), """
                package demo.a;

                public class Sibling {
                    Foo foo = new Foo();
                }
                """);
        Path user = Files.writeString(b.resolve("User.java"), """
                package demo.b;

                import demo.a.Foo;

                public class User {
                    Foo foo;
                    demo.a.Foo qualified;
                }
                """);
        Files.writeString(c.resolve("Existing.java"), """
                package demo.c;

                public class Existing {
                }
                """);

        startServer(project);

        IdeWorkspaceEdit edit = service.moveTypesWorkspace(List.of(foo), c);
        System.out.println("[move-it] problema=" + service.lastMoveProblem());
        describe(edit);
        assertFalse(edit.isEmpty(), "java/move vazio: " + service.lastMoveProblem());

        String fooAfter = TextEditApplier.apply(Files.readString(foo), edit.editsFor(foo));
        String siblingAfter = TextEditApplier.apply(Files.readString(sibling), edit.editsFor(sibling));
        String userAfter = TextEditApplier.apply(Files.readString(user), edit.editsFor(user));
        System.out.println("[move-it] Foo depois:\n" + fooAfter);
        System.out.println("[move-it] Sibling depois:\n" + siblingAfter);
        System.out.println("[move-it] User depois:\n" + userAfter);
        assertTrue(fooAfter.contains("package demo.c;"), fooAfter);
        assertTrue(fooAfter.contains("import demo.a.Sibling;"), fooAfter);
        assertTrue(siblingAfter.contains("import demo.c.Foo;"), siblingAfter);
        assertTrue(userAfter.contains("import demo.c.Foo;"), userAfter);
        assertTrue(userAfter.contains("demo.c.Foo qualified;"), userAfter);
    }

    @Test
    void movingAPackageFolderUnderAnotherPackage() throws Exception {
        Path project = createProject("folder");
        Path sub = Files.createDirectories(project.resolve("src/main/java/demo/a/sub"));
        Path inner = Files.createDirectories(sub.resolve("inner"));
        Path x = Files.createDirectories(project.resolve("src/main/java/demo/x"));
        Path b = Files.createDirectories(project.resolve("src/main/java/demo/b"));
        Path bar = Files.writeString(sub.resolve("Bar.java"), """
                package demo.a.sub;

                import demo.a.sub.inner.Deep;

                public class Bar {
                    Deep deep;
                }
                """);
        Path deep = Files.writeString(inner.resolve("Deep.java"), """
                package demo.a.sub.inner;

                public class Deep {
                }
                """);
        Path user = Files.writeString(b.resolve("User.java"), """
                package demo.b;

                import demo.a.sub.Bar;
                import demo.a.sub.inner.Deep;

                public class User {
                    Bar bar;
                    Deep deep;
                }
                """);
        Files.writeString(x.resolve("X.java"), """
                package demo.x;

                public class X {
                }
                """);

        startServer(project);

        Path newSub = x.resolve("sub");
        IdeWorkspaceEdit edit = service.willRenameFilesWorkspace(Map.of(sub, newSub));
        System.out.println("[move-it] willRenameFiles problema=" + service.lastMoveProblem());
        describe(edit);
        String userAfter = TextEditApplier.apply(Files.readString(user), edit.editsFor(user));
        String barAfter = TextEditApplier.apply(Files.readString(bar), edit.editsFor(bar));
        String deepAfter = TextEditApplier.apply(Files.readString(deep), edit.editsFor(deep));
        assertFalse(edit.isEmpty(), "willRenameFiles vazio: " + service.lastMoveProblem());
        assertTrue(edit.operations().stream().noneMatch(IdeWorkspaceEdit.RenameFile.class::isInstance),
                "quem move a pasta e a IDE");
        assertTrue(userAfter.contains("import demo.x.sub.Bar;"), userAfter);
        assertTrue(userAfter.contains("import demo.x.sub.inner.Deep;"), userAfter);
        assertTrue(barAfter.contains("package demo.x.sub;"), barAfter);
        assertTrue(barAfter.contains("import demo.x.sub.inner.Deep;"), barAfter);
        assertTrue(deepAfter.contains("package demo.x.sub.inner;"), deepAfter);
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

    private static void describe(IdeWorkspaceEdit edit) {
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits) {
                System.out.println("[move-it] TextEdits " + textEdits.file());
                for (TextEdit textEdit : textEdits.edits()) {
                    System.out.println("[move-it]   " + textEdit.range() + " -> "
                            + textEdit.newText().replace("\n", "\\n"));
                }
            } else if (operation instanceof IdeWorkspaceEdit.RenameFile renameFile) {
                System.out.println("[move-it] RenameFile " + renameFile.oldPath() + " -> " + renameFile.newPath());
            }
        }
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
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        await(() -> !service.isWarmingUp(), 60_000);
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
