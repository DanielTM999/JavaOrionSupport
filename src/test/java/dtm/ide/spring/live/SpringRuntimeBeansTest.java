package dtm.ide.spring.live;

import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringDiagnostics;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringRuntimeBeansTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path CONSUMIDOR = ROOT.resolve("PedidoService.java");

    private static SpringActuatorClient.LiveBean live(String name, String type) {
        return new SpringActuatorClient.LiveBean(name, type, "singleton", List.of());
    }

    @Test
    void convertsLiveBeansIntoIndexedBeans() {
        List<SpringBean> beans = SpringRuntimeBeans.from(
                List.of(live("dataSource", "com.zaxxer.hikari.HikariDataSource")),
                SpringIndexSnapshot.empty(ROOT));

        assertEquals(1, beans.size());
        SpringBean bean = beans.getFirst();
        assertEquals("dataSource", bean.name());
        assertEquals("HikariDataSource", bean.simpleName());
        assertTrue(bean.fromRuntime());
        assertFalse(bean.navigable());
        assertEquals("singleton", bean.traits().scope());
    }

    @Test
    void skipsBeansThatAlreadyExistInTheSource() {
        Path file = ROOT.resolve("EnvioRapido.java");
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(file, SpringSourceParser.parse(file, """
                        package com.example;

                        @Service
                        public class EnvioRapido {
                        }
                        """));

        List<SpringBean> beans = SpringRuntimeBeans.from(
                List.of(live("envioRapido", "com.example.EnvioRapido")), snapshot);

        assertTrue(beans.isEmpty());
    }

    @Test
    void offersRuntimeBeanNamesToTheQualifierCompletion() {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .withRuntimeBeans(SpringRuntimeBeans.from(
                        List.of(live("objectMapper", "com.fasterxml.jackson.databind.ObjectMapper")),
                        SpringIndexSnapshot.empty(ROOT)));

        assertTrue(snapshot.hasRuntimeBeans());
        assertTrue(snapshot.beanNames().contains("objectMapper"));
    }

    @Test
    void reportsAnUnsatisfiedInjectionOfAProjectType() {
        SpringIndexSnapshot snapshot = snapshotWithConsumer("""
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    private Envio envio;
                }
                """);

        List<Diagnostic> diagnostics = SpringDiagnostics.analyze(snapshot, CONSUMIDOR, "");

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("Nenhum bean satisfaz")));
    }

    @Test
    void staysSilentForATypeThatIsNotInTheProject() {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, """
                        package com.example;

                        @Service
                        public class PedidoService {

                            @Autowired
                            private DataSource dataSource;
                        }
                        """));

        List<Diagnostic> diagnostics = SpringDiagnostics.analyze(snapshot, CONSUMIDOR, "");

        assertTrue(diagnostics.stream()
                .noneMatch(d -> d.message().contains("Nenhum bean satisfaz")));
    }

    @Test
    void staysSilentForAnOptionalOrCollectionInjection() {
        SpringIndexSnapshot snapshot = snapshotWithConsumer("""
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    private Optional<Envio> talvez;

                    @Autowired
                    private List<Envio> todos;
                }
                """);

        List<Diagnostic> diagnostics = SpringDiagnostics.analyze(snapshot, CONSUMIDOR, "");

        assertTrue(diagnostics.stream()
                .noneMatch(d -> d.message().contains("Nenhum bean satisfaz")));
    }

    private static SpringIndexSnapshot snapshotWithConsumer(String consumerSource) {
        Path envio = ROOT.resolve("Envio.java");
        return SpringIndexSnapshot.empty(ROOT)
                .replacingFile(envio, SpringSourceParser.parse(envio, """
                        package com.example;

                        public interface Envio {
                        }
                        """))
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, consumerSource));
    }
}
