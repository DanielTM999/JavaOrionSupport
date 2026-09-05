package dtm.ide.build;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.JdkDetector;
import dtm.ide.sdk.JdkInstallation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacBuildServiceTest {

    @TempDir
    Path root;

    private JdkInstallation jdk;

    @BeforeEach
    void setUp() {
        jdk = anyJdk().orElse(null);
        Assumptions.assumeTrue(jdk != null, "nenhuma JDK com javac encontrada nesta maquina");
    }

    @Test
    void compilesAPlainJavaProject() throws IOException {
        writeSource("Main.java", """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("ola");
                    }
                }
                """);

        BuildResult result = build(BuildSystem.BuildAction.COMPILE);

        assertTrue(result.successful(), () -> "build falhou: " + result.summary());
        assertTrue(Files.isRegularFile(root.resolve(".orion/out/Main.class")));
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void compilesSourcesInPackages() throws IOException {
        writeSource("com/example/App.java", """
                package com.example;

                public class App {
                    public static void main(String[] args) { }
                }
                """);

        BuildResult result = build(BuildSystem.BuildAction.COMPILE);

        assertTrue(result.successful(), () -> "build falhou: " + result.summary());
        assertTrue(Files.isRegularFile(root.resolve(".orion/out/com/example/App.class")));
    }

    @Test
    void reportsCompilerErrorsAsDiagnostics() throws IOException {
        writeSource("Quebrado.java", """
                public class Quebrado {
                    void metodo() {
                        int x = "texto";
                    }
                }
                """);

        BuildResult result = build(BuildSystem.BuildAction.COMPILE);

        assertFalse(result.successful());
        assertFalse(result.errors().isEmpty(), "o erro do javac deve virar diagnostico");
        BuildDiagnostic error = result.errors().getFirst();
        assertTrue(error.hasLocation());
        assertEquals(3, error.line());
        assertTrue(error.file().toString().endsWith("Quebrado.java"));
    }

    @Test
    void cleanRemovesTheOutputDirectory() throws IOException {
        writeSource("Main.java", "public class Main { }");
        build(BuildSystem.BuildAction.COMPILE);
        assertTrue(Files.isDirectory(root.resolve(".orion/out")));

        BuildResult result = build(BuildSystem.BuildAction.CLEAN);

        assertTrue(result.successful());
        assertFalse(Files.exists(root.resolve(".orion/out")));
    }

    @Test
    void rebuildDiscardsStaleClassFiles() throws IOException {
        writeSource("Main.java", "public class Main { }");
        writeSource("Antigo.java", "public class Antigo { }");
        build(BuildSystem.BuildAction.COMPILE);
        assertTrue(Files.isRegularFile(root.resolve(".orion/out/Antigo.class")));

        Files.delete(root.resolve("src/Antigo.java"));
        BuildResult result = build(BuildSystem.BuildAction.REBUILD);

        assertTrue(result.successful(), () -> "build falhou: " + result.summary());
        assertFalse(Files.exists(root.resolve(".orion/out/Antigo.class")),
                "o rebuild deve limpar a saida antes de compilar");
    }

    @Test
    void failsClearlyWhenTheSourcesDisappearedAfterTheProjectWasOpened() throws IOException {
        writeSource("Main.java", "public class Main { }");
        JavacBuildService service = service();
        Files.delete(root.resolve("src/Main.java"));

        BuildResult result = service.execute(BuildRequest.of(BuildSystem.BuildAction.COMPILE),
                line -> {
                });

        assertFalse(result.successful());
        assertTrue(result.diagnostics().getFirst().message().contains("Nenhum arquivo .java"));
    }

    @Test
    void compilesProjectsWithManySourceFiles() throws IOException {
        for (int i = 0; i < 40; i++) {
            writeSource("Classe" + i + ".java", "public class Classe" + i + " { }");
        }

        BuildResult result = build(BuildSystem.BuildAction.COMPILE);

        assertTrue(result.successful(), () -> "build falhou: " + result.summary());
        assertEquals(40, countClassFiles(root.resolve(".orion/out")));
    }

    @Test
    void packageProducesAJar() throws IOException {
        writeSource("Main.java", """
                public class Main {
                    public static void main(String[] args) { }
                }
                """);

        BuildResult result = build(BuildSystem.BuildAction.PACKAGE);

        assertTrue(result.successful(), () -> "empacotamento falhou: " + result.summary());
        assertTrue(Files.isRegularFile(root.resolve(root.getFileName() + ".jar")));
    }

    @Test
    void runtimeClasspathIncludesOutputAndLooseJars() throws IOException {
        writeSource("Main.java", "public class Main { }");
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/dependencia.jar"), "");

        String classpath = service().resolveRuntimeClasspath(descriptor().rootModule()).orElseThrow();

        assertTrue(classpath.contains(".orion"));
        assertTrue(classpath.contains("dependencia.jar"));
    }

    @Test
    void testActionExplainsThatThereIsNoSuite() throws IOException {
        writeSource("Main.java", "public class Main { }");

        BuildResult result = build(BuildSystem.BuildAction.TEST);

        assertFalse(result.successful());
        assertTrue(result.diagnostics().getFirst().message().contains("sem build system"));
    }

    private BuildResult build(BuildSystem.BuildAction action) {
        List<String> output = new ArrayList<>();
        return service().execute(BuildRequest.of(action), output::add);
    }

    private JavacBuildService service() {
        return new JavacBuildService(descriptor(), () -> jdk);
    }

    private JavaProjectDescriptor descriptor() {
        JavaProjectDescriptor described = JavaProjectConventions.describe(root);
        assertEquals(JavaProjectKind.PLAIN_JAVA, described.kind());
        return described;
    }

    private void writeSource(String relativePath, String content) throws IOException {
        Path file = root.resolve("src").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static long countClassFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> path.toString().endsWith(".class")).count();
        }
    }

    private static Optional<JdkInstallation> anyJdk() {
        return JdkDetector.detect(null).stream()
                .filter(JdkInstallation::isJdk)
                .min(Comparator.naturalOrder());
    }
}
