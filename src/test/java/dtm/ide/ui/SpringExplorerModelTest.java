package dtm.ide.ui;

import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringEndpoint;
import dtm.ide.spring.SpringStereotype;
import dtm.ide.spring.live.SpringActuatorClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringExplorerModelTest {

    private static final Path FILE = Path.of("project", "Controller.java");

    @Test
    void groupsBeansByStereotypeAndFiltersTheirMetadata() {
        SpringBean service = bean("pedidoService", "com.app.PedidoService",
                SpringStereotype.SERVICE, List.of("prod"), "principal");
        SpringBean repository = bean("pedidoRepository", "com.app.PedidoRepository",
                SpringStereotype.REPOSITORY, List.of(), "");

        var all = SpringExplorerModel.beansByStereotype(
                List.of(repository, service), "pedido");
        var byProfile = SpringExplorerModel.beansByStereotype(
                List.of(repository, service), "prod");
        var byQualifier = SpringExplorerModel.beansByStereotype(
                List.of(repository, service), "principal");

        assertEquals(2, all.size());
        assertEquals(List.of(service), byProfile.get(SpringStereotype.SERVICE));
        assertEquals(List.of(service), byQualifier.get(SpringStereotype.SERVICE));
    }

    @Test
    void groupsEndpointsByQualifiedControllerAndSortsRoutes() {
        SpringEndpoint admin = endpoint("GET", "/admin", "b.UserController", "admin");
        SpringEndpoint second = endpoint("POST", "/users", "a.UserController", "create");
        SpringEndpoint first = endpoint("GET", "/users", "a.UserController", "list");

        var groups = SpringExplorerModel.endpointsByController(
                List.of(second, admin, first), "");

        assertEquals(2, groups.size(), "tipos qualificados iguais no nome simples nao se misturam");
        assertEquals("a.UserController", groups.getFirst().handlerType());
        assertEquals(List.of(first, second), groups.getFirst().endpoints());
        assertEquals("b.UserController", groups.get(1).handlerType());
    }

    @Test
    void filtersEndpointsByControllerVerbRouteAndHandler() {
        SpringEndpoint endpoint = endpoint("PATCH", "/orders/{id}",
                "com.app.OrderController", "update");

        assertEquals(1, SpringExplorerModel.endpointsByController(List.of(endpoint), "order").size());
        assertEquals(1, SpringExplorerModel.endpointsByController(List.of(endpoint), "patch").size());
        assertEquals(1, SpringExplorerModel.endpointsByController(List.of(endpoint), "{id}").size());
        assertEquals(1, SpringExplorerModel.endpointsByController(List.of(endpoint), "update").size());
        assertTrue(SpringExplorerModel.endpointsByController(List.of(endpoint), "missing").isEmpty());
    }

    @Test
    void filtersLiveBeansAndPropertiesAcrossVisibleFields() {
        var bean = new SpringActuatorClient.LiveBean("mailer", "com.app.MailService",
                "prototype", List.of("smtpClient"));
        var property = new SpringActuatorClient.LiveProperty(
                "server.port", "9090", "systemEnvironment");

        assertEquals(List.of(bean), SpringExplorerModel.filterLiveBeans(List.of(bean), "smtp"));
        assertEquals(List.of(bean), SpringExplorerModel.filterLiveBeans(List.of(bean), "prototype"));
        assertEquals(List.of(property),
                SpringExplorerModel.filterLiveProperties(List.of(property), "9090"));
        assertEquals(List.of(property),
                SpringExplorerModel.filterLiveProperties(List.of(property), "environment"));
    }

    @Test
    void groupsLiveMappingsByControllerAndKeepsFrameworkLast() {
        var users = new SpringActuatorClient.LiveMapping(
                "GET", "/users", "com.app.UserController#list()");
        var orders = new SpringActuatorClient.LiveMapping(
                "POST", "/orders", "com.app.OrderController#create()");
        var framework = new SpringActuatorClient.LiveMapping(
                "", "{ [/webjars/**]}", "ResourceHttpRequestHandler");

        var groups = SpringExplorerModel.mappingsByController(
                List.of(framework, users, orders), "", "Framework / Outros");

        assertEquals("com.app.OrderController", groups.getFirst().key());
        assertEquals("com.app.UserController", groups.get(1).key());
        assertEquals(SpringExplorerModel.FRAMEWORK_GROUP, groups.getLast().key());
        assertEquals("Framework / Outros", groups.getLast().displayName());
    }

    @Test
    void filtersLiveMappingsBeforeGrouping() {
        var users = new SpringActuatorClient.LiveMapping(
                "GET", "/users", "com.app.UserController#list()");
        var orders = new SpringActuatorClient.LiveMapping(
                "POST", "/orders", "com.app.OrderController#create()");

        var groups = SpringExplorerModel.mappingsByController(
                List.of(users, orders), "post", "Framework / Other");

        assertEquals(1, groups.size());
        assertEquals("OrderController", groups.getFirst().displayName());
        assertEquals(List.of(orders), groups.getFirst().mappings());
    }

    private static SpringBean bean(String name, String type, SpringStereotype stereotype,
                                   List<String> profiles, String qualifier) {
        return new SpringBean(name, type, type.substring(type.lastIndexOf('.') + 1), stereotype,
                FILE, 1, List.of(), profiles, false, qualifier, false);
    }

    private static SpringEndpoint endpoint(String method, String path, String controller,
                                           String handler) {
        return new SpringEndpoint(method, path, controller, handler, FILE, 1, List.of());
    }
}
