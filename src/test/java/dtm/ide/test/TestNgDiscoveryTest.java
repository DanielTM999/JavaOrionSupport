package dtm.ide.test;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNgDiscoveryTest {

    private static final Path FILE = Path.of("src/test/java/com/exemplo/PedidoTest.java");

    @Test
    void aClassLevelTestAnnotationMakesEveryPublicMethodATest() {
        List<String> methods = methodsOf("""
                package com.exemplo;

                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    public void deveCriar() {
                    }

                    public void deveCancelar() {
                    }
                }
                """);

        assertEquals(List.of("deveCriar", "deveCancelar"), methods);
    }

    @Test
    void lifecycleMethodsAreNotTests() {
        List<String> methods = methodsOf("""
                package com.exemplo;

                import org.testng.annotations.AfterMethod;
                import org.testng.annotations.BeforeMethod;
                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    @BeforeMethod
                    public void prepara() {
                    }

                    @AfterMethod
                    public void limpa() {
                    }

                    public void deveCriar() {
                    }
                }
                """);

        assertEquals(List.of("deveCriar"), methods);
    }

    @Test
    void dataProvidersAreNotTests() {
        List<String> methods = methodsOf("""
                package com.exemplo;

                import org.testng.annotations.DataProvider;
                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    @DataProvider(name = "valores")
                    public Object[][] valores() {
                        return new Object[][] {};
                    }

                    public void deveSomar() {
                    }
                }
                """);

        assertEquals(List.of("deveSomar"), methods);
    }

    @Test
    void privateAndProtectedMethodsAreIgnored() {
        List<String> methods = methodsOf("""
                package com.exemplo;

                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    private void auxiliar() {
                    }

                    protected void tambemAuxiliar() {
                    }

                    public void deveCriar() {
                    }
                }
                """);

        assertEquals(List.of("deveCriar"), methods);
    }

    @Test
    void methodLevelTestNgAnnotationsKeepWorkingAndAreNotDuplicated() {
        List<JavaTest> tests = JUnitTestDiscovery.discoverInSource(FILE, """
                package com.exemplo;

                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    @Test
                    public void deveCriar() {
                    }
                }
                """);

        assertEquals(1, tests.size());
        assertEquals("deveCriar", tests.getFirst().methodName());
    }

    @Test
    void junitClassesAreUnaffectedByTheTestNgSupport() {
        List<JavaTest> tests = JUnitTestDiscovery.discoverInSource(FILE, """
                package com.exemplo;

                import org.junit.jupiter.api.Test;

                class PedidoTest {

                    @Test
                    void deveCriar() {
                    }

                    void auxiliar() {
                    }
                }
                """);

        assertEquals(1, tests.size());
        assertEquals("com.exemplo.PedidoTest", tests.getFirst().className());
        assertEquals("deveCriar", tests.getFirst().methodName());
    }

    @Test
    void classNamesAreQualifiedWithThePackage() {
        List<JavaTest> tests = JUnitTestDiscovery.discoverInSource(FILE, """
                package com.exemplo;

                import org.testng.annotations.Test;

                @Test
                public class PedidoTest {

                    public void deveCriar() {
                    }
                }
                """);

        assertEquals("com.exemplo.PedidoTest", tests.getFirst().className());
        assertFalse(tests.getFirst().isClassLevel());
    }

    @Test
    void sourcesWithoutTestAnnotationsAreSkipped() {
        assertTrue(JUnitTestDiscovery.discoverInSource(FILE, """
                package com.exemplo;

                public class Pedido {

                    public void criar() {
                    }
                }
                """).isEmpty());
    }

    private static List<String> methodsOf(String source) {
        return JUnitTestDiscovery.discoverInSource(FILE, source).stream()
                .map(JavaTest::methodName)
                .toList();
    }
}
