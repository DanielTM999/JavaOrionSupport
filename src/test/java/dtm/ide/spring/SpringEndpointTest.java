package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringEndpointTest {

    private static final Path FILE = Path.of("/projeto/ClienteController.java");

    @Test
    void normalizesPaths() {
        assertEquals("/clientes", SpringEndpoint.normalizePath("clientes"));
        assertEquals("/clientes", SpringEndpoint.normalizePath("/clientes/"));
        assertEquals("/", SpringEndpoint.normalizePath(""));
        assertEquals("/", SpringEndpoint.normalizePath(null));
        assertEquals("/a/b", SpringEndpoint.normalizePath("//a//b"));
    }

    @Test
    void joinsClassAndMethodPaths() {
        assertEquals("/clientes/ativos", SpringEndpoint.join("/clientes", "/ativos"));
        assertEquals("/clientes/ativos", SpringEndpoint.join("clientes", "ativos"));
        assertEquals("/clientes", SpringEndpoint.join("/clientes", ""));
        assertEquals("/clientes", SpringEndpoint.join("", "/clientes"));
        assertEquals("/clientes/{id}", SpringEndpoint.join("/clientes", "/{id}"));
    }

    @Test
    void buildsBrowsableUrls() {
        SpringEndpoint endpoint = new SpringEndpoint("GET", "/clientes", "p.C", "listar", FILE, 1, List.of());

        assertEquals("http://localhost:8080/clientes", endpoint.urlOn(null));
        assertEquals("http://localhost:9090/clientes", endpoint.urlOn("http://localhost:9090/"));
        assertFalse(endpoint.hasPathVariables());
    }

    @Test
    void detectsPathVariables() {
        assertTrue(new SpringEndpoint("GET", "/clientes/{id}", "p.C", "buscar", FILE, 1, List.of())
                .hasPathVariables());
    }

    @Test
    void sortsByPathThenMethod() {
        SpringEndpoint post = new SpringEndpoint("POST", "/a", "p.C", "criar", FILE, 1, List.of());
        SpringEndpoint getA = new SpringEndpoint("GET", "/a", "p.C", "ler", FILE, 1, List.of());
        SpringEndpoint getB = new SpringEndpoint("GET", "/b", "p.C", "ler", FILE, 1, List.of());

        assertTrue(getA.compareTo(post) < 0);
        assertTrue(getA.compareTo(getB) < 0);
    }

    @Test
    void readsEndpointsCombiningClassAndMethodPaths() {
        List<SpringEndpoint> endpoints = parse("""
                package com.example;

                @RestController
                @RequestMapping("/clientes")
                public class ClienteController {

                    @GetMapping
                    public List<Cliente> listar() { return null; }

                    @GetMapping("/{id}")
                    public Cliente buscar(@PathVariable Long id) { return null; }

                    @PostMapping
                    public Cliente criar(@RequestBody Cliente cliente) { return null; }

                    @DeleteMapping("/{id}")
                    public void remover(@PathVariable Long id) { }
                }
                """);

        assertEquals(4, endpoints.size());
        assertTrue(endpoints.stream().anyMatch(e -> e.method().equals("GET") && e.path().equals("/clientes")));
        assertTrue(endpoints.stream().anyMatch(e -> e.method().equals("GET") && e.path().equals("/clientes/{id}")));
        assertTrue(endpoints.stream().anyMatch(e -> e.method().equals("POST") && e.path().equals("/clientes")));
        assertTrue(endpoints.stream().anyMatch(e -> e.method().equals("DELETE")));
    }

    @Test
    void readsTheHandlerMethodName() {
        SpringEndpoint endpoint = parse("""
                @RestController
                @RequestMapping("/clientes")
                public class ClienteController {
                    @GetMapping
                    public String listar() { return ""; }
                }
                """).getFirst();

        assertEquals("listar", endpoint.handlerName());
        assertEquals("ClienteController", endpoint.handlerSimpleType());
        assertEquals(5, endpoint.line());
    }

    @Test
    void readsRequestMappingWithExplicitMethod() {
        SpringEndpoint endpoint = parse("""
                @RestController
                public class ClienteController {
                    @RequestMapping(path = "/clientes", method = RequestMethod.PUT)
                    public void atualizar() { }
                }
                """).getFirst();

        assertEquals("PUT", endpoint.method());
        assertEquals("/clientes", endpoint.path());
    }

    @Test
    void requestMappingWithoutMethodMatchesAnyVerb() {
        assertEquals(SpringEndpoint.ANY_METHOD, parse("""
                @RestController
                public class ClienteController {
                    @RequestMapping("/tudo")
                    public void tudo() { }
                }
                """).getFirst().method());
    }

    @Test
    void readsPathFromTheValueAttribute() {
        assertEquals("/clientes", parse("""
                @RestController
                public class ClienteController {
                    @GetMapping(value = "/clientes")
                    public void listar() { }
                }
                """).getFirst().path());
    }

    @Test
    void readsProducesWithoutSwallowingOtherArguments() {
        SpringEndpoint endpoint = parse("""
                @RestController
                public class ClienteController {
                    @GetMapping(value = "/clientes", produces = "application/json")
                    public void listar() { }
                }
                """).getFirst();

        assertEquals("/clientes", endpoint.path());
        assertEquals(List.of("application/json"), endpoint.produces());
    }

    @Test
    void controllerWithoutClassLevelMappingUsesTheMethodPath() {
        assertEquals("/saude", parse("""
                @RestController
                public class SaudeController {
                    @GetMapping("/saude")
                    public String ok() { return "ok"; }
                }
                """).getFirst().path());
    }

    @Test
    void plainMethodsAreNotEndpoints() {
        assertEquals(1, parse("""
                @RestController
                @RequestMapping("/clientes")
                public class ClienteController {
                    @GetMapping
                    public String listar() { return ""; }

                    private String auxiliar() { return ""; }
                }
                """).size());
    }

    @Test
    void typesThatAreNotControllersHaveNoEndpoints() {
        assertTrue(parse("""
                @Service
                public class ClienteService {
                    @GetMapping("/nao-e-rota")
                    public void metodo() { }
                }
                """).isEmpty());
    }

    @Test
    void plainControllersAlsoCount() {
        assertEquals(1, parse("""
                @Controller
                public class PaginaController {
                    @GetMapping("/inicio")
                    public String inicio() { return "index"; }
                }
                """).size());
    }

    private static List<SpringEndpoint> parse(String source) {
        return SpringSourceParser.parse(FILE, source).endpoints();
    }
}
