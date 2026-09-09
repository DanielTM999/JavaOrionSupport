package dtm.ide.spring.infra;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringInfraTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path FILE = ROOT.resolve("Tarefas.java");

    @Test
    void acceptsAValidCronExpression() {
        assertTrue(CronExpression.validate("0 0 3 * * *").isEmpty());
        assertTrue(CronExpression.validate("0 */15 * * * MON-FRI").isEmpty());
        assertTrue(CronExpression.validate("@daily").isEmpty());
        assertTrue(CronExpression.validate("${app.cron}").isEmpty());
    }

    @Test
    void rejectsACronWithTheWrongNumberOfFields() {
        assertTrue(CronExpression.validate("0 0 3 * *").orElseThrow().contains("6 campos"));
    }

    @Test
    void rejectsAValueOutOfRange() {
        assertTrue(CronExpression.validate("0 0 99 * * *").orElseThrow().contains("hora"));
        assertTrue(CronExpression.validate("0 0 0 * 13 *").orElseThrow().contains("mes"));
    }

    @Test
    void rejectsAnUnknownMacro() {
        assertTrue(CronExpression.validate("@sometimes").orElseThrow().contains("macro"));
    }

    @Test
    void readsAScheduledTask() {
        SpringInfraModel model = infra("""
                package com.example;

                @Service
                public class Tarefas {

                    @Scheduled(cron = "0 0 3 * * *")
                    public void limpar() {
                    }
                }
                """);

        assertEquals(1, model.scheduledTasks().size());
        ScheduledTask task = model.scheduledTasks().getFirst();
        assertEquals("limpar", task.methodName());
        assertEquals("0 0 3 * * *", task.cron());
        assertTrue(task.hasCron());
        assertFalse(task.conflicting());
    }

    @Test
    void reportsAnInvalidCron() {
        List<Diagnostic> diagnostics = diagnose("""
                package com.example;

                @EnableScheduling
                @Service
                public class Tarefas {

                    @Scheduled(cron = "0 0 99 * * *")
                    public void limpar() {
                    }
                }
                """);

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("cron invalida")));
    }

    @Test
    void reportsScheduledWithoutAnySchedule() {
        List<Diagnostic> diagnostics = diagnose("""
                package com.example;

                @EnableScheduling
                @Service
                public class Tarefas {

                    @Scheduled
                    public void limpar() {
                    }
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("exige cron")));
    }

    @Test
    void warnsWhenSchedulingIsNotEnabled() {
        List<Diagnostic> diagnostics = diagnose("""
                package com.example;

                @Service
                public class Tarefas {

                    @Scheduled(fixedDelay = 1000)
                    public void limpar() {
                    }
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("@EnableScheduling")));
    }

    @Test
    void readsAnEventListenerAndItsPublisher() {
        SpringInfraModel model = infra("""
                package com.example;

                @Service
                public class Tarefas {

                    @EventListener
                    public void aoCriarPedido(PedidoCriado evento) {
                    }

                    public void criar() {
                        publisher.publishEvent(new PedidoCriado(1L));
                    }
                }
                """);

        assertEquals(1, model.eventHandlers().size());
        assertEquals("PedidoCriado", model.eventHandlers().getFirst().eventType());
        assertEquals(1, model.eventPublications().size());
        assertEquals(1, model.handlersOf("PedidoCriado").size());
        assertEquals(1, model.publicationsOf("PedidoCriado").size());
    }

    @Test
    void readsCacheNames() {
        SpringInfraModel model = infra("""
                package com.example;

                @EnableCaching
                @Service
                public class Tarefas {

                    @Cacheable("clientes")
                    public String buscar() {
                        return null;
                    }

                    @CacheEvict(cacheNames = {"clientes", "pedidos"})
                    public void limpar() {
                    }
                }
                """);

        assertEquals(2, model.cacheUsages().size());
        assertEquals(List.of("clientes", "pedidos"), model.cacheNames());
    }

    @Test
    void reportsAnEmptySecurityExpression() {
        List<Diagnostic> diagnostics = diagnose("""
                package com.example;

                @EnableMethodSecurity
                @Service
                public class Tarefas {

                    @PreAuthorize("")
                    public void apagar() {
                    }
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("vazia")));
    }

    @Test
    void reportsAnUnbalancedSecurityExpression() {
        assertFalse(SpringInfraDiagnostics.balanced("hasRole('ADMIN'"));
        assertFalse(SpringInfraDiagnostics.balanced("hasRole('ADMIN)"));
        assertTrue(SpringInfraDiagnostics.balanced("hasRole('ADMIN')"));
    }

    private static SpringInfraModel infra(String source) {
        return SpringIndexSnapshot.empty(ROOT)
                .replacingFile(FILE, SpringSourceParser.parse(FILE, source))
                .infra();
    }

    private static List<Diagnostic> diagnose(String source) {
        return SpringInfraDiagnostics.analyze(infra(source), FILE);
    }
}
