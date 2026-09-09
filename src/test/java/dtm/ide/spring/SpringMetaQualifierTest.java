package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringMetaQualifierTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path CONSUMIDOR = ROOT.resolve("PedidoService.java");

    private static final String ANOTACAO = """
            package com.example;

            @Qualifier
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Rapido {
            }
            """;

    private static final String ENVIO = """
            package com.example;

            public interface Envio {
            }
            """;

    private static final String RAPIDO = """
            package com.example;

            @Service
            @Rapido
            public class EnvioRapido implements Envio {
            }
            """;

    private static final String LENTO = """
            package com.example;

            @Service
            public class EnvioLento implements Envio {
            }
            """;

    @Test
    void recognisesAnAnnotationMetaAnnotatedWithQualifier() {
        assertTrue(snapshot("").metaQualifierAnnotations().contains("rapido"));
    }

    @Test
    void doesNotTreatAPlainAnnotationAsQualifier() {
        SpringIndexSnapshot snapshot = snapshot("");

        assertFalse(snapshot.metaQualifierAnnotations().contains("service"));
        assertFalse(snapshot.metaQualifierAnnotations().contains("retention"));
    }

    @Test
    void resolvesAnInjectionAnnotatedWithTheMetaQualifier() {
        SpringIndexSnapshot snapshot = snapshot("""
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    @Rapido
                    private Envio envio;
                }
                """);

        SpringInjection injection = snapshot.injectionsIn(CONSUMIDOR).getFirst();

        assertEquals("rapido", snapshot.effectiveQualifier(injection));
        Optional<SpringBean> resolved = snapshot.resolve(injection);
        assertTrue(resolved.isPresent());
        assertEquals("EnvioRapido", resolved.get().simpleName());
    }

    @Test
    void offersTheMetaQualifierAmongTheBeanNames() {
        assertTrue(snapshot("").beanNames().contains("rapido"));
    }

    @Test
    void readsScopeLazyAndOrder() {
        Path file = ROOT.resolve("Cache.java");
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(file, SpringSourceParser.parse(file, """
                        package com.example;

                        @Service
                        @Scope("prototype")
                        @Lazy
                        @Order(5)
                        public class CacheService {
                        }
                        """));

        SpringBeanTraits traits = snapshot.beans().getFirst().traits();

        assertEquals("prototype", traits.scope());
        assertTrue(traits.isPrototype());
        assertFalse(traits.isSingleton());
        assertTrue(traits.lazy());
        assertEquals(5, traits.orderValue());
    }

    @Test
    void readsDependsOn() {
        Path file = ROOT.resolve("Relatorio.java");
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(file, SpringSourceParser.parse(file, """
                        package com.example;

                        @Service
                        @DependsOn({"bancoDeDados", "cache"})
                        public class RelatorioService {
                        }
                        """));

        List<String> dependsOn = snapshot.beans().getFirst().traits().dependsOn();

        assertEquals(List.of("bancoDeDados", "cache"), dependsOn);
    }

    @Test
    void defaultsToSingletonWithoutScope() {
        Path file = ROOT.resolve("Simples.java");
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(file, SpringSourceParser.parse(file, """
                        package com.example;

                        @Service
                        public class SimplesService {
                        }
                        """));

        SpringBeanTraits traits = snapshot.beans().getFirst().traits();

        assertTrue(traits.isSingleton());
        assertFalse(traits.lazy());
        assertEquals(SpringBeanTraits.DEFAULT_ORDER, traits.orderValue());
    }

    @Test
    void supportsNamedAsAQualifier() {
        Path file = ROOT.resolve("NamedService.java");
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(file, SpringSourceParser.parse(file, """
                        package com.example;

                        @Service
                        @Named("principal")
                        public class NamedService {
                        }
                        """));

        assertEquals("principal", snapshot.beans().getFirst().qualifier());
    }

    private static SpringIndexSnapshot snapshot(String consumerSource) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(ROOT.resolve("Rapido.java"),
                        SpringSourceParser.parse(ROOT.resolve("Rapido.java"), ANOTACAO))
                .replacingFile(ROOT.resolve("Envio.java"),
                        SpringSourceParser.parse(ROOT.resolve("Envio.java"), ENVIO))
                .replacingFile(ROOT.resolve("EnvioRapido.java"),
                        SpringSourceParser.parse(ROOT.resolve("EnvioRapido.java"), RAPIDO))
                .replacingFile(ROOT.resolve("EnvioLento.java"),
                        SpringSourceParser.parse(ROOT.resolve("EnvioLento.java"), LENTO));
        return consumerSource.isBlank() ? snapshot
                : snapshot.replacingFile(CONSUMIDOR,
                        SpringSourceParser.parse(CONSUMIDOR, consumerSource));
    }
}
