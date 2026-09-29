package dtm.ide.wizard;

import dtm.ide.project.JavaModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileTemplatesTest {

    private static final Path ROOT = Path.of("/projeto").toAbsolutePath();

    private static final JavaModule MODULE = new JavaModule(
            ROOT, "demo", "com.example", "demo", "jar",
            List.of(ROOT.resolve("src/main/java"), ROOT.resolve("src/main/resources")),
            List.of(ROOT.resolve("src/test/java")),
            ROOT.resolve("target/classes"));

    @Test
    void derivesThePackageFromTheDirectory() {
        String packageName = JavaFileTemplates.packageOf(
                ROOT.resolve("src/main/java/com/example/servico"), MODULE);

        assertEquals("com.example.servico", packageName);
    }

    @Test
    void sourceRootItselfIsTheDefaultPackage() {
        assertEquals("", JavaFileTemplates.packageOf(ROOT.resolve("src/main/java"), MODULE));
    }

    @Test
    void directoryOutsideAnySourceRootHasNoPackage() {
        assertEquals("", JavaFileTemplates.packageOf(ROOT.resolve("docs"), MODULE));
        assertEquals("", JavaFileTemplates.packageOf(null, MODULE));
        assertEquals("", JavaFileTemplates.packageOf(ROOT, null));
    }

    @Test
    void derivesThePackageInsideTestSources() {
        assertEquals("com.example",
                JavaFileTemplates.packageOf(ROOT.resolve("src/test/java/com/example"), MODULE));
    }

    @Test
    void normalizesWhatTheUserTyped() {
        assertEquals("Cliente", JavaFileTemplates.sanitizeTypeName("Cliente"));
        assertEquals("Cliente", JavaFileTemplates.sanitizeTypeName("Cliente.java"));
        assertEquals("Cliente", JavaFileTemplates.sanitizeTypeName("  cliente  "));
        assertEquals("Cliente", JavaFileTemplates.sanitizeTypeName("com.example.Cliente"));
        assertEquals("_2Fatores", JavaFileTemplates.sanitizeTypeName("2Fatores"));
        assertEquals("SemNome", JavaFileTemplates.sanitizeTypeName(""));
        assertEquals("SemNome", JavaFileTemplates.sanitizeTypeName(null));
    }

    @Test
    void buildsTheFileNameWithASingleExtension() {
        assertEquals("Cliente.java", JavaFileTemplates.fileNameOf("Cliente"));
        assertEquals("Cliente.java", JavaFileTemplates.fileNameOf("Cliente.java"));
    }

    @Test
    void rendersAPlainClass() {
        String source = JavaFileTemplates.render(
                JavaFileTemplates.Kind.CLASS, "com.example", "Cliente");

        assertTrue(source.startsWith("package com.example;\n\n"));
        assertTrue(source.contains("public class Cliente {"));
    }

    @Test
    void omitsThePackageStatementForTheDefaultPackage() {
        String source = JavaFileTemplates.render(JavaFileTemplates.Kind.CLASS, "", "Main");

        assertFalse(source.contains("package"));
        assertTrue(source.startsWith("public class Main {"));
    }

    @Test
    void rendersEachTypeKind() {
        assertTrue(render(JavaFileTemplates.Kind.INTERFACE).contains("public interface Exemplo {"));
        assertTrue(render(JavaFileTemplates.Kind.ENUM).contains("public enum Exemplo {"));
        assertTrue(render(JavaFileTemplates.Kind.RECORD).contains("public record Exemplo() {"));
        assertTrue(render(JavaFileTemplates.Kind.ANNOTATION).contains("public @interface Exemplo {"));
    }

    @Test
    void springStereotypesBringTheirImport() {
        String service = render(JavaFileTemplates.Kind.SERVICE);
        assertTrue(service.contains("import org.springframework.stereotype.Service;"));
        assertTrue(service.contains("@Service\npublic class Exemplo {"));

        String repository = render(JavaFileTemplates.Kind.REPOSITORY);
        assertTrue(repository.contains("import org.springframework.stereotype.Repository;"));
        assertTrue(repository.contains("@Repository\npublic interface Exemplo {"));

        String configuration = render(JavaFileTemplates.Kind.CONFIGURATION);
        assertTrue(configuration.contains("import org.springframework.context.annotation.Configuration;"));
    }

    @Test
    void repositoryUsesSelectedEntityAndConfiguredId() {
        String source = JavaFileTemplates.renderRepository("com.example.repository",
                "ClienteRepository", "com.example.domain.Cliente", "java.util.UUID");

        assertTrue(source.contains("import org.springframework.data.jpa.repository.JpaRepository;"));
        assertTrue(source.contains("import com.example.domain.Cliente;"));
        assertTrue(source.contains("import java.util.UUID;"));
        assertTrue(source.contains("public interface ClienteRepository extends JpaRepository<Cliente, UUID>"));
    }

    @Test
    void repositoryBoxesPrimitiveIdAndAvoidsSamePackageImport() {
        String source = JavaFileTemplates.renderRepository("com.example",
                "PedidoRepository", "com.example.Pedido", "long");

        assertFalse(source.contains("import com.example.Pedido;"));
        assertTrue(source.contains("JpaRepository<Pedido, Long>"));
    }

    @Test
    void restControllerSuggestsARouteFromTheTypeName() {
        String source = JavaFileTemplates.render(
                JavaFileTemplates.Kind.REST_CONTROLLER, "com.example", "ClienteController");

        assertTrue(source.contains("@RequestMapping(\"/clientes\")"),
                "o sufixo Controller sai e o recurso vai para o plural");
        assertTrue(source.contains("@RestController"));
        assertTrue(source.contains("@GetMapping"));
    }

    @Test
    void routeDoesNotDuplicateAnExistingPlural() {
        String source = JavaFileTemplates.render(
                JavaFileTemplates.Kind.REST_CONTROLLER, "com.example", "PedidosController");

        assertTrue(source.contains("@RequestMapping(\"/pedidos\")"));
    }

    @Test
    void testClassComesWithARunnableTest() {
        String source = JavaFileTemplates.render(
                JavaFileTemplates.Kind.TEST, "com.example", "ClienteTest");

        assertTrue(source.contains("import org.junit.jupiter.api.Test;"));
        assertTrue(source.contains("@Test"));
        assertTrue(source.contains("class ClienteTest {"));
        assertFalse(source.contains("public class"), "classe de teste JUnit 5 nao precisa ser publica");
    }

    @Test
    void springKindsAreFlaggedAsSuch() {
        assertTrue(JavaFileTemplates.Kind.SERVICE.isSpring());
        assertTrue(JavaFileTemplates.Kind.REST_CONTROLLER.isSpring());
        assertFalse(JavaFileTemplates.Kind.CLASS.isSpring());
        assertFalse(JavaFileTemplates.Kind.TEST.isSpring());
    }

    private static String render(JavaFileTemplates.Kind kind) {
        return JavaFileTemplates.render(kind, "com.example", "Exemplo");
    }
}
