package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringSourceParserTest {

    private static final Path FILE = Path.of("/projeto/src/main/java/com/example/Arquivo.java");

    @Test
    void readsAServiceBean() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example.servico;

                import org.springframework.stereotype.Service;

                @Service
                public class ClienteService {
                }
                """);

        assertEquals(1, result.beans().size());
        SpringBean bean = result.beans().getFirst();
        assertEquals("clienteService", bean.name());
        assertEquals("com.example.servico.ClienteService", bean.type());
        assertEquals(SpringStereotype.SERVICE, bean.stereotype());
        assertEquals(6, bean.line(), "a linha aponta para a declaracao do tipo");
    }

    @Test
    void readsEveryStereotype() {
        assertEquals(SpringStereotype.COMPONENT, stereotypeOf("@Component"));
        assertEquals(SpringStereotype.REPOSITORY, stereotypeOf("@Repository"));
        assertEquals(SpringStereotype.CONTROLLER, stereotypeOf("@Controller"));
        assertEquals(SpringStereotype.REST_CONTROLLER, stereotypeOf("@RestController"));
        assertEquals(SpringStereotype.CONFIGURATION, stereotypeOf("@Configuration"));
        assertEquals(SpringStereotype.BOOT_APPLICATION, stereotypeOf("@SpringBootApplication"));
    }

    @Test
    void acceptsFullyQualifiedAnnotations() {
        assertEquals(SpringStereotype.SERVICE,
                stereotypeOf("@org.springframework.stereotype.Service"));
    }

    @Test
    void explicitBeanNameWins() {
        SpringBean bean = parse("""
                @Service("cobranca")
                public class ClienteService { }
                """).beans().getFirst();

        assertEquals("cobranca", bean.name());
    }

    @Test
    void defaultBeanNameLowercasesTheFirstLetterOnly() {
        assertEquals("clienteService", SpringBean.defaultBeanName("ClienteService"));
        assertEquals("URLService", SpringBean.defaultBeanName("URLService"));
        assertEquals("", SpringBean.defaultBeanName(""));
    }

    @Test
    void capturesSupertypesForTypeBasedInjection() {
        SpringBean bean = parse("""
                @Service
                public class ClienteServiceImpl extends BaseService implements ClienteService, Auditavel {
                }
                """).beans().getFirst();

        assertTrue(bean.supertypes().contains("ClienteService"));
        assertTrue(bean.supertypes().contains("Auditavel"));
        assertTrue(bean.supertypes().contains("BaseService"));
        assertTrue(bean.provides("ClienteService"), "o bean satisfaz a injecao pela interface");
    }

    @Test
    void readsProfilesAndPrimary() {
        SpringBean bean = parse("""
                @Service
                @Primary
                @Profile({"dev", "test"})
                public class MockClienteService { }
                """).beans().getFirst();

        assertTrue(bean.primary());
        assertEquals(List.of("dev", "test"), bean.profiles());
        assertTrue(bean.isProfileSpecific());
    }

    @Test
    void flagsConditionalBeans() {
        SpringBean bean = parse("""
                @Service
                @ConditionalOnProperty(name = "app.cobranca.ativa")
                public class CobrancaService { }
                """).beans().getFirst();

        assertTrue(bean.conditional());
    }

    @Test
    void ignoresTypesWithoutAStereotype() {
        assertTrue(parse("public class Simples { }").beans().isEmpty());
    }

    @Test
    void ignoresAnnotationsInsideComments() {
        SpringSourceParser.ParseResult result = parse("""
                // @Service
                /* @Component
                   public class Fantasma { }
                 */
                public class Real { }
                """);

        assertTrue(result.beans().isEmpty(), "nada dentro de comentario vira bean");
    }

    @Test
    void ignoresAnnotationsInsideStringLiterals() {
        SpringSourceParser.ParseResult result = parse("""
                public class Real {
                    String exemplo = "@Service public class Fantasma { }";
                }
                """);

        assertTrue(result.beans().isEmpty());
    }

    @Test
    void lineNumbersSurviveCommentBlanking() {
        SpringBean bean = parse("""
                package com.example;

                /*
                 * Comentario longo
                 * com varias linhas
                 */
                @Service
                public class ClienteService { }
                """).beans().getFirst();

        assertEquals(8, bean.line());
    }

    @Test
    void readsConstructorInjectionWithoutAutowired() {
        SpringSourceParser.ParseResult result = parse("""
                @Service
                public class PedidoService {
                    private final ClienteService clientes;
                    private final EstoqueService estoque;

                    public PedidoService(ClienteService clientes, EstoqueService estoque) {
                        this.clientes = clientes;
                        this.estoque = estoque;
                    }
                }
                """);

        List<SpringInjection> injections = result.injections();
        assertEquals(2, injections.size());
        assertEquals(SpringInjection.Kind.CONSTRUCTOR, injections.getFirst().kind());
        assertEquals("ClienteService", injections.getFirst().targetType());
        assertEquals("clientes", injections.getFirst().memberName());
    }

    @Test
    void readsFieldInjection() {
        SpringSourceParser.ParseResult result = parse("""
                @Service
                public class PedidoService {
                    @Autowired
                    private ClienteService clientes;

                    private String naoInjetado;
                }
                """);

        assertEquals(1, result.injections().size());
        assertEquals(SpringInjection.Kind.FIELD, result.injections().getFirst().kind());
        assertEquals("clientes", result.injections().getFirst().memberName());
    }

    @Test
    void readsJakartaInjectAndResource() {
        assertEquals(1, parse("""
                @Service
                public class A {
                    @Inject
                    private B b;
                }
                """).injections().size());

        assertEquals(1, parse("""
                @Service
                public class A {
                    @Resource
                    private B b;
                }
                """).injections().size());
    }

    @Test
    void readsQualifiersOnInjectionPoints() {
        SpringInjection injection = parse("""
                @Service
                public class PedidoService {
                    public PedidoService(@Qualifier("rapido") EntregaService entrega) { }
                }
                """).injections().getFirst();

        assertEquals("rapido", injection.qualifier());
        assertTrue(injection.hasQualifier());
        assertEquals("EntregaService", injection.targetType());
    }

    @Test
    void keepsGenericTypesIntact() {
        SpringInjection injection = parse("""
                @Service
                public class PedidoService {
                    public PedidoService(List<ClienteService> todos) { }
                }
                """).injections().getFirst();

        assertEquals("List<ClienteService>", injection.targetType());
        assertEquals("List", injection.targetSimpleName());
    }

    @Test
    void ignoresInjectionsInTypesThatAreNotBeans() {
        assertTrue(parse("""
                public class Simples {
                    public Simples(ClienteService clientes) { }
                }
                """).injections().isEmpty());
    }

    @Test
    void readsBeanMethodsFromAConfiguration() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example;

                @Configuration
                public class AppConfig {

                    @Bean
                    public ClienteService clienteService() {
                        return new ClienteServiceImpl();
                    }

                    @Bean("cacheCustomizado")
                    public CacheManager cacheManager() {
                        return new CacheManager();
                    }
                }
                """);

        List<SpringBean> beans = result.beans();
        assertEquals(3, beans.size(), "a configuracao mais os dois beans que ela produz");
        assertTrue(beans.stream().anyMatch(bean -> bean.name().equals("clienteService")
                && bean.stereotype() == SpringStereotype.BEAN_METHOD));
        assertTrue(beans.stream().anyMatch(bean -> bean.name().equals("cacheCustomizado")));
    }

    @Test
    void beanMethodParametersAreInjectionPoints() {
        SpringSourceParser.ParseResult result = parse("""
                @Configuration
                public class AppConfig {
                    @Bean
                    public PedidoService pedidoService(ClienteService clientes) {
                        return new PedidoService(clientes);
                    }
                }
                """);

        assertTrue(result.injections().stream()
                .anyMatch(injection -> injection.targetType().equals("ClienteService")));
    }

    @Test
    void plainMethodsInAConfigurationAreNotBeans() {
        SpringSourceParser.ParseResult result = parse("""
                @Configuration
                public class AppConfig {
                    public String auxiliar() {
                        return "";
                    }
                }
                """);

        assertEquals(1, result.beans().size(), "so a propria classe de configuracao");
    }

    @Test
    void splitsParametersWithGenerics() {
        List<String> parts = SpringSourceParser.splitTopLevel("Map<String, Integer> mapa, String nome");

        assertEquals(2, parts.size());
        assertTrue(parts.getFirst().contains("Map<String, Integer>"));
    }

    @Test
    void findsTheMatchingBrace() {
        String code = "class A { void m() { } }";
        int open = code.indexOf('{');

        assertEquals(code.length() - 1, SpringSourceParser.matchingBrace(code, open));
    }

    @Test
    void readsSetterInjection() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example;

                @Service
                public class PedidoService {

                    private Envio envio;

                    @Autowired
                    public void setEnvio(@Qualifier("rapido") Envio envio) {
                        this.envio = envio;
                    }
                }
                """);

        assertEquals(1, result.injections().size());
        SpringInjection injection = result.injections().getFirst();
        assertEquals(SpringInjection.Kind.SETTER, injection.kind());
        assertEquals("Envio", injection.targetSimpleName());
        assertEquals("rapido", injection.qualifier());
    }

    @Test
    void ignoresSettersWithoutAnInjectionAnnotation() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example;

                @Service
                public class PedidoService {

                    private String nome;

                    public void setNome(String nome) {
                        this.nome = nome;
                    }
                }
                """);

        assertTrue(result.injections().isEmpty());
    }

    @Test
    void readsValuePlaceholdersAsPropertyUsages() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example;

                @Service
                public class PedidoService {

                    @Value("${app.timeout:30}")
                    private int timeout;

                    @Value("${app.destino}")
                    private String destino;
                }
                """);

        assertEquals(2, result.propertyUsages().size());
        assertEquals("app.timeout", result.propertyUsages().getFirst().key());
        assertEquals("30", result.propertyUsages().getFirst().defaultValue());
        assertFalse(result.propertyUsages().getLast().hasDefault());
    }

    @Test
    void readsConfigurationPropertiesPrefixAndItsFields() {
        SpringSourceParser.ParseResult result = parse("""
                package com.example;

                @ConfigurationProperties(prefix = "app.envio")
                public class EnvioProperties {
                    private String url;
                    private int timeout;
                }
                """);

        List<String> keys = result.propertyUsages().stream()
                .map(usage -> usage.key())
                .toList();

        assertTrue(keys.contains("app.envio"));
        assertTrue(keys.contains("app.envio.url"));
        assertTrue(keys.contains("app.envio.timeout"));
    }

    private static SpringStereotype stereotypeOf(String annotation) {
        return parse(annotation + "\npublic class Exemplo { }").beans().getFirst().stereotype();
    }

    @Test
    void requiredArgsConstructorInjectsFinalAndNonNullFieldsOnly() {
        List<SpringInjection> injections = parse("""
                package com.example;

                @Service
                @RequiredArgsConstructor
                public class PedidoService {
                    private static final Logger LOG = Logger.getLogger("x");
                    private final ClienteService clienteService;
                    @Qualifier("rapido")
                    private final Frete frete;
                    @NonNull
                    private Auditoria auditoria;
                    private final List<String> nomes = new ArrayList<>();
                    @Value("${pedido.limite}")
                    private final int limite;
                    private Cache cache;

                    public void executar() {
                        final Pedido pedido = novo();
                        Cache local;
                    }

                    static class Interna {
                        private final Ignorado ignorado;
                    }
                }
                """).injections();

        assertEquals(List.of("clienteService", "frete", "auditoria"),
                injections.stream().map(SpringInjection::memberName).toList());
        assertEquals(SpringInjection.Kind.CONSTRUCTOR, injections.getFirst().kind());
        assertEquals("ClienteService", injections.getFirst().targetType());
        assertEquals(7, injections.getFirst().line());
        assertEquals("rapido", injections.get(1).qualifier());
    }

    @Test
    void allArgsConstructorInjectsEveryInstanceFieldWithoutInitializer() {
        List<SpringInjection> injections = parse("""
                @Component
                @AllArgsConstructor
                public class Relatorio {
                    private static Relatorio instancia;
                    private Gerador gerador;
                    private final Formato formato;
                    private int paginas = 1;
                }
                """).injections();

        assertEquals(List.of("gerador", "formato"),
                injections.stream().map(SpringInjection::memberName).toList());
    }

    @Test
    void dataOnlyInjectsWhenThereIsNoExplicitConstructor() {
        assertEquals(1, parse("""
                @Component
                @Data
                public class Config {
                    private final Fonte fonte;
                }
                """).injections().size());

        List<SpringInjection> explicit = parse("""
                @Component
                @Data
                public class Config {
                    private final Fonte fonte;
                    public Config(Fonte fonte) {
                        this.fonte = fonte;
                    }
                }
                """).injections();
        assertEquals(1, explicit.size());
        assertEquals(5, explicit.getFirst().line(), "vem do construtor explicito, nao do Lombok");
    }

    @Test
    void autowiredFinalFieldIsNotCountedTwice() {
        assertEquals(1, parse("""
                @Service
                @RequiredArgsConstructor
                public class Servico {
                    @Autowired
                    private final Dependencia dependencia;
                }
                """).injections().size());
    }

    @Test
    void lombokInjectionThroughAnInterfaceResolvesToTheImplementation() {
        SpringSourceParser.ParseResult contract = SpringSourceParser.parse(
                Path.of("/p/consulta/src/main/java/cautcar/laudos/service/ConsultaVeicularService.java"), """
                package cautcar.laudos.service;
                public interface ConsultaVeicularService {
                }
                """);
        SpringSourceParser.ParseResult implementation = SpringSourceParser.parse(
                Path.of("/p/consulta/src/main/java/cautcar/laudos/service/implementations/ConsultaVeicularServiceimpl.java"), """
                package cautcar.laudos.service.implementations;
                import cautcar.laudos.service.ConsultaVeicularService;
                import cautcar.laudos.services.common.ContextBaseService;
                @Service
                @RequiredArgsConstructor
                public class ConsultaVeicularServiceimpl extends ContextBaseService implements ConsultaVeicularService{
                    private final ApplicationContext context;
                }
                """);
        SpringSourceParser.ParseResult consumer = SpringSourceParser.parse(
                Path.of("/p/LaudoCautelar/src/main/java/cautcar/laudos/services/consulta/ConsultaCautelarServiceData.java"), """
                package cautcar.laudos.services.consulta;
                import cautcar.laudos.service.ConsultaVeicularService;
                @Service
                @RequiredArgsConstructor
                public class ConsultaCautelarServiceData {
                    private final ConsultaVeicularService consultaVeicularService;
                }
                """);
        List<SpringBean> beans = new java.util.ArrayList<>(implementation.beans());
        beans.addAll(consumer.beans());
        List<SpringInjection> injections = new java.util.ArrayList<>(implementation.injections());
        injections.addAll(consumer.injections());
        List<JavaType> types = new java.util.ArrayList<>(contract.types());
        types.addAll(implementation.types());
        types.addAll(consumer.types());
        SpringIndexSnapshot snapshot = new SpringIndexSnapshot(Path.of("/p"), beans, injections,
                List.of(), List.of(), List.of(), List.of(), types);

        SpringBean impl = snapshot.beans().stream()
                .filter(bean -> bean.simpleName().equals("ConsultaVeicularServiceimpl"))
                .findFirst().orElseThrow();
        List<SpringInjection> usages = snapshot.injectionsOf(impl);

        assertEquals(1, usages.size());
        assertEquals("consultaVeicularService", usages.getFirst().memberName());
    }

    private static SpringSourceParser.ParseResult parse(String source) {
        return SpringSourceParser.parse(FILE, source);
    }
}
