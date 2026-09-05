package dtm.ide.spring;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBeanIndexTest {

    @TempDir
    Path root;

    private SpringBeanIndex index;

    @BeforeEach
    void setUp() throws IOException {
        index = new SpringBeanIndex();
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                </project>
                """);
    }

    @Test
    void indexesBeansAcrossTheSourceTree() throws Exception {
        source("com/example/ClienteService.java", """
                package com.example;

                @Service
                public class ClienteService { }
                """);
        source("com/example/web/ClienteController.java", """
                package com.example.web;

                @RestController
                public class ClienteController {
                    public ClienteController(ClienteService clientes) { }
                }
                """);

        SpringIndexSnapshot snapshot = rebuild();

        assertEquals(2, snapshot.beans().size());
        assertEquals(1, snapshot.injections().size());
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void ignoresBeansDeclaredInTestSources() throws Exception {
        source("com/example/ClienteService.java", """
                package com.example;

                @Service
                public class ClienteService { }
                """);
        testSource("com/example/MockClienteService.java", """
                package com.example;

                @Service
                public class MockClienteService { }
                """);

        SpringIndexSnapshot snapshot = rebuild();

        assertEquals(1, snapshot.beans().size(),
                "beans de teste nao sobem no contexto da aplicacao");
        assertEquals("clienteService", snapshot.beans().getFirst().name());
    }

    @Test
    void emptyProjectYieldsAnEmptySnapshot() throws Exception {
        source("com/example/Simples.java", """
                package com.example;

                public class Simples { }
                """);

        assertTrue(rebuild().isEmpty());
    }

    @Test
    void nullDescriptorIsHandled() throws Exception {
        assertTrue(index.rebuild(null).get().isEmpty());
    }

    @Test
    void resolvesInjectionThroughTheInterface() throws Exception {
        source("com/example/ClienteService.java", """
                package com.example;

                public interface ClienteService { }
                """);
        source("com/example/ClienteServiceImpl.java", """
                package com.example;

                @Service
                public class ClienteServiceImpl implements ClienteService { }
                """);
        source("com/example/PedidoService.java", """
                package com.example;

                @Service
                public class PedidoService {
                    public PedidoService(ClienteService clientes) { }
                }
                """);

        SpringIndexSnapshot snapshot = rebuild();
        SpringInjection injection = snapshot.injections().stream()
                .filter(i -> i.targetType().equals("ClienteService"))
                .findFirst()
                .orElseThrow();

        SpringBean resolved = snapshot.resolve(injection).orElseThrow();
        assertEquals("ClienteServiceImpl", resolved.simpleName());
    }

    @Test
    void ambiguousInjectionResolvesToNothing() throws Exception {
        twoImplementations("");

        SpringIndexSnapshot snapshot = rebuild();
        SpringInjection injection = injectionOfClienteService(snapshot);

        assertTrue(snapshot.resolve(injection).isEmpty(),
                "duas implementacoes sem desempate sao ambiguas, como no Spring");
        assertEquals(2, snapshot.beansProviding("ClienteService").size());
    }

    @Test
    void primaryBreaksTheTie() throws Exception {
        twoImplementations("@Primary");

        SpringIndexSnapshot snapshot = rebuild();

        assertEquals("ClienteServiceA",
                snapshot.resolve(injectionOfClienteService(snapshot)).orElseThrow().simpleName());
    }

    @Test
    void qualifierBreaksTheTie() throws Exception {
        source("com/example/ClienteService.java", "package com.example;\npublic interface ClienteService { }");
        source("com/example/ClienteServiceA.java", """
                package com.example;

                @Service("rapido")
                public class ClienteServiceA implements ClienteService { }
                """);
        source("com/example/ClienteServiceB.java", """
                package com.example;

                @Service("lento")
                public class ClienteServiceB implements ClienteService { }
                """);
        source("com/example/PedidoService.java", """
                package com.example;

                @Service
                public class PedidoService {
                    public PedidoService(@Qualifier("lento") ClienteService clientes) { }
                }
                """);

        SpringIndexSnapshot snapshot = rebuild();

        assertEquals("ClienteServiceB",
                snapshot.resolve(injectionOfClienteService(snapshot)).orElseThrow().simpleName());
    }

    @Test
    void listsWhoInjectsABean() throws Exception {
        source("com/example/ClienteService.java", """
                package com.example;

                @Service
                public class ClienteService { }
                """);
        source("com/example/PedidoService.java", """
                package com.example;

                @Service
                public class PedidoService {
                    public PedidoService(ClienteService clientes) { }
                }
                """);
        source("com/example/RelatorioService.java", """
                package com.example;

                @Service
                public class RelatorioService {
                    @Autowired
                    private ClienteService clientes;
                }
                """);

        SpringIndexSnapshot snapshot = rebuild();
        SpringBean clientes = snapshot.beanNamed("clienteService").orElseThrow();

        assertEquals(2, snapshot.injectionsOf(clientes).size());
        assertEquals(1, snapshot.dependenciesOf(
                snapshot.beanNamed("pedidoService").orElseThrow()).size());
    }

    @Test
    void groupsBeansByStereotype() throws Exception {
        source("com/example/A.java", "package com.example;\n@Service\npublic class A { }");
        source("com/example/B.java", "package com.example;\n@Repository\npublic class B { }");
        source("com/example/C.java", "package com.example;\n@Service\npublic class C { }");

        var grouped = rebuild().byStereotype();

        assertEquals(2, grouped.get(SpringStereotype.SERVICE).size());
        assertEquals(1, grouped.get(SpringStereotype.REPOSITORY).size());
        assertEquals("A", grouped.get(SpringStereotype.SERVICE).getFirst().simpleName());
    }

    @Test
    void findsBeansAndInjectionsByFile() throws Exception {
        Path file = source("com/example/PedidoService.java", """
                package com.example;

                @Service
                public class PedidoService {
                    public PedidoService(ClienteService clientes) { }
                }
                """);

        SpringIndexSnapshot snapshot = rebuild();

        assertEquals(1, snapshot.beansIn(file).size());
        assertEquals(1, snapshot.injectionsIn(file).size());
        assertTrue(snapshot.beansIn(root.resolve("outro.java")).isEmpty());
    }

    @Test
    void refreshingOneFileReplacesOnlyItsContribution() throws Exception {
        source("com/example/A.java", "package com.example;\n@Service\npublic class A { }");
        Path b = source("com/example/B.java", "package com.example;\n@Service\npublic class B { }");
        assertEquals(2, rebuild().beans().size());

        Files.writeString(b, "package com.example;\npublic class B { }");
        SpringIndexSnapshot updated = index.refreshFile(b).get();

        assertEquals(1, updated.beans().size());
        assertEquals("a", updated.beans().getFirst().name());
    }

    @Test
    void refreshingAddsNewlyAnnotatedTypes() throws Exception {
        Path a = source("com/example/A.java", "package com.example;\npublic class A { }");
        assertTrue(rebuild().isEmpty());

        Files.writeString(a, "package com.example;\n@Repository\npublic class A { }");
        SpringIndexSnapshot updated = index.refreshFile(a).get();

        assertEquals(1, updated.beans().size());
        assertEquals(SpringStereotype.REPOSITORY, updated.beans().getFirst().stereotype());
    }

    @Test
    void refreshingANonJavaFileChangesNothing() throws Exception {
        source("com/example/A.java", "package com.example;\n@Service\npublic class A { }");
        rebuild();

        SpringIndexSnapshot updated = index.refreshFile(root.resolve("pom.xml")).get();

        assertEquals(1, updated.beans().size());
    }

    @Test
    void clearForgetsEverything() throws Exception {
        source("com/example/A.java", "package com.example;\n@Service\npublic class A { }");
        rebuild();

        index.clear();

        assertTrue(index.snapshot().isEmpty());
    }

    private SpringIndexSnapshot rebuild() throws ExecutionException, InterruptedException {
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        return index.rebuild(descriptor).get();
    }

    private SpringInjection injectionOfClienteService(SpringIndexSnapshot snapshot) {
        return snapshot.injections().stream()
                .filter(injection -> injection.targetType().equals("ClienteService"))
                .findFirst()
                .orElseThrow();
    }

    private void twoImplementations(String extraAnnotationOnA) throws IOException {
        source("com/example/ClienteService.java",
                "package com.example;\npublic interface ClienteService { }");
        source("com/example/ClienteServiceA.java", """
                package com.example;

                @Service
                %s
                public class ClienteServiceA implements ClienteService { }
                """.formatted(extraAnnotationOnA));
        source("com/example/ClienteServiceB.java", """
                package com.example;

                @Service
                public class ClienteServiceB implements ClienteService { }
                """);
        source("com/example/PedidoService.java", """
                package com.example;

                @Service
                public class PedidoService {
                    public PedidoService(ClienteService clientes) { }
                }
                """);
    }

    private Path source(String relativePath, String content) throws IOException {
        return write(root.resolve("src/main/java").resolve(relativePath), content);
    }

    private void testSource(String relativePath, String content) throws IOException {
        write(root.resolve("src/test/java").resolve(relativePath), content);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
