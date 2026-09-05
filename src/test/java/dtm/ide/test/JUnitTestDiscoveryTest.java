package dtm.ide.test;

import dtm.ide.project.JavaProjectConventions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitTestDiscoveryTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");
    }

    @Test
    void findsJUnit5Tests() throws IOException {
        Path file = testSource("com/example/ClienteServiceTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                class ClienteServiceTest {

                    @Test
                    void salvaUmCliente() { }

                    @Test
                    void rejeitaClienteInvalido() { }
                }
                """);

        List<JavaTest> tests = JUnitTestDiscovery.discoverInFile(file);

        assertEquals(2, tests.size());
        assertEquals("com.example.ClienteServiceTest", tests.getFirst().className());
        assertEquals("salvaUmCliente", tests.getFirst().methodName());
        assertEquals(8, tests.getFirst().line(), "a linha aponta para a assinatura, nao para a anotacao");
        assertFalse(tests.getFirst().isClassLevel());
    }

    @Test
    void findsParameterizedAndRepeatedTests() throws IOException {
        Path file = testSource("com/example/CalculoTest.java", """
                package com.example;

                class CalculoTest {

                    @ParameterizedTest
                    @ValueSource(ints = {1, 2})
                    void soma(int valor) { }

                    @RepeatedTest(3)
                    void repetido() { }

                    @TestFactory
                    Stream<DynamicTest> dinamicos() { return null; }
                }
                """);

        List<JavaTest> tests = JUnitTestDiscovery.discoverInFile(file);

        assertEquals(3, tests.size());
        assertTrue(tests.stream().allMatch(JavaTest::parameterized));
    }

    @Test
    void readsDisplayNames() throws IOException {
        Path file = testSource("com/example/PedidoTest.java", """
                package com.example;

                class PedidoTest {

                    @Test
                    @DisplayName("cria um pedido com itens")
                    void criaPedido() { }
                }
                """);

        JavaTest test = JUnitTestDiscovery.discoverInFile(file).getFirst();

        assertEquals("cria um pedido com itens", test.displayName());
        assertEquals("cria um pedido com itens", test.display());
    }

    @Test
    void methodWithoutDisplayNameShowsItsOwnName() throws IOException {
        Path file = testSource("com/example/A.java",
                "package com.example;\nclass A {\n    @Test\n    void faz() { }\n}");

        assertEquals("faz()", JUnitTestDiscovery.discoverInFile(file).getFirst().display());
    }

    @Test
    void ignoresPlainMethods() throws IOException {
        Path file = testSource("com/example/ApoioTest.java", """
                package com.example;

                class ApoioTest {

                    @Test
                    void umTeste() { }

                    private void metodoDeApoio() { }

                    void outroSemAnotacao() { }
                }
                """);

        assertEquals(1, JUnitTestDiscovery.discoverInFile(file).size());
    }

    @Test
    void ignoresTestsInsideComments() throws IOException {
        Path file = testSource("com/example/ComentadoTest.java", """
                package com.example;

                class ComentadoTest {

                    @Test
                    void ativo() { }

                    // @Test
                    // void desativado() { }
                }
                """);

        List<JavaTest> tests = JUnitTestDiscovery.discoverInFile(file);

        assertEquals(1, tests.size());
        assertEquals("ativo", tests.getFirst().methodName());
    }

    @Test
    void filesWithoutTestsYieldNothing() throws IOException {
        Path file = testSource("com/example/Apoio.java",
                "package com.example;\nclass Apoio {\n    void metodo() { }\n}");

        assertTrue(JUnitTestDiscovery.discoverInFile(file).isEmpty());
    }

    @Test
    void discoversTestsFromUnsavedEditorText() throws IOException {
        Path file = testSource("com/example/UnsavedTest.java",
                "package com.example; class UnsavedTest {}");
        String editorText = """
                package com.example;
                class UnsavedTest {
                    @Test
                    void novoTesteAindaNaoSalvo() { }
                }
                """;

        List<JavaTest> tests = JUnitTestDiscovery.discoverInSource(file, editorText);

        assertEquals(1, tests.size());
        assertEquals("novoTesteAindaNaoSalvo", tests.getFirst().methodName());
    }

    @Test
    void nullEditorTextYieldsNothing() throws IOException {
        Path file = testSource("com/example/EmptyTest.java", "class EmptyTest {}");

        assertTrue(JUnitTestDiscovery.discoverInSource(file, null).isEmpty());
    }

    @Test
    void classInTheDefaultPackageHasNoQualifier() throws IOException {
        Path file = testSource("SoltoTest.java",
                "class SoltoTest {\n    @Test\n    void faz() { }\n}");

        assertEquals("SoltoTest", JUnitTestDiscovery.discoverInFile(file).getFirst().className());
    }

    @Test
    void discoversNestedTestClassWithJvmName() throws IOException {
        Path file = testSource("com/example/OuterTest.java", """
                package com.example;
                class OuterTest {
                    @Nested
                    class Inner {
                        @Test
                        void nested() { }
                    }
                }
                """);

        JavaTest test = JUnitTestDiscovery.discoverInFile(file).getFirst();

        assertEquals("com.example.OuterTest$Inner", test.className());
        assertEquals("nested", test.methodName());
    }

    @Test
    void discoversAcrossTheTestSourceTree() throws IOException {
        testSource("com/example/ATest.java",
                "package com.example;\nclass ATest {\n    @Test\n    void a() { }\n}");
        testSource("com/example/web/BTest.java",
                "package com.example.web;\nclass BTest {\n    @Test\n    void b() { }\n}");

        List<JavaTest> tests = JUnitTestDiscovery.discover(JavaProjectConventions.describe(root));

        assertEquals(2, tests.size());
    }

    @Test
    void ignoresProductionSources() throws IOException {
        mainSource("com/example/Servico.java",
                "package com.example;\nclass Servico {\n    @Test\n    void naoConta() { }\n}");

        assertTrue(JUnitTestDiscovery.discover(JavaProjectConventions.describe(root)).isEmpty());
    }

    @Test
    void nullDescriptorYieldsNothing() {
        assertTrue(JUnitTestDiscovery.discover(null).isEmpty());
    }

    @Test
    void groupsByClassPreservingDeclarationOrder() throws IOException {
        testSource("com/example/ATest.java", """
                package com.example;

                class ATest {
                    @Test
                    void primeiro() { }

                    @Test
                    void segundo() { }
                }
                """);
        testSource("com/example/BTest.java",
                "package com.example;\nclass BTest {\n    @Test\n    void unico() { }\n}");

        var grouped = JavaTest.byClass(
                JUnitTestDiscovery.discover(JavaProjectConventions.describe(root)));

        assertEquals(2, grouped.size());
        List<JavaTest> a = grouped.get("com.example.ATest");
        assertEquals("primeiro", a.getFirst().methodName());
        assertEquals("segundo", a.get(1).methodName());
    }

    @Test
    void buildsSelectorsForMavenAndGradle() {
        JavaTest method = new JavaTest("com.example.ATest", "faz", "", root, 1, false);
        JavaTest clazz = new JavaTest("com.example.ATest", "", "", root, 1, false);

        assertEquals("com.example.ATest#faz", method.selector());
        assertEquals("com.example.ATest", clazz.selector());
        assertTrue(clazz.isClassLevel());
        assertEquals("ATest", clazz.simpleClassName());
    }

    private Path testSource(String relativePath, String content) throws IOException {
        return write(root.resolve("src/test/java").resolve(relativePath), content);
    }

    private void mainSource(String relativePath, String content) throws IOException {
        write(root.resolve("src/main/java").resolve(relativePath), content);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
