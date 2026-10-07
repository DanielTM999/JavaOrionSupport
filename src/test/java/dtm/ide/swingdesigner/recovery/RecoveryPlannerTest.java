package dtm.ide.swingdesigner.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPlannerTest {

    private static final String SOURCE = """
            package demo;

            import java.awt.Color;
            import javax.swing.*;

            public class Tela extends JFrame {
                private Object controller;
                private JLabel titulo;

                protected void onDrawing(int largura) {
                    Color fundo = Color.WHITE;
                    JMenuBar barra = (JMenuBar) controller.toString();
                    titulo = new JLabel("Tela " + largura, SwingConstants.LEFT);
                    this.titulo.setForeground(fundo);
                    Runnable tarefa = () -> montarTopo();
                    barra.add(new JMenu("Arquivo"));
                    if (fundo != null) {
                        montarTopo();
                    }
                    setExtendedState(javax.swing.JFrame.MAXIMIZED_BOTH);
                    montarCorpo(-2, 1.5, 'x', null);
                }

                private void montarTopo() { }
                private void montarCorpo(int a, double b, char c, Object d) { }

                public static class Interna extends JPanel {
                    void montar() {
                        quebra();
                        depois();
                    }
                    void quebra() { }
                    void depois() { }
                }
            }
            """;

    @Test
    void theFailingDeclarationBecomesASyntheticLocal() {
        RecoveryPlanner.Plan plan = plan(12);

        ObjectNode first = plan.statements().getFirst();
        assertEquals("local", first.path("s").asText());
        assertEquals("barra", first.path("name").asText());
        assertTrue(first.path("synthetic").asBoolean());
        assertTrue(texts(first.path("type")).contains("javax.swing.JMenuBar"), first::toString);
    }

    @Test
    void previousLocalsAndParametersAreDeclaredAsPrior() {
        RecoveryPlanner.Plan plan = plan(12);

        assertEquals(List.of("largura", "fundo"),
                plan.priorLocals().stream().map(local -> local.path("name").asText()).toList());
        assertEquals("int", plan.priorLocals().getFirst().path("type").get(0).asText());
        assertTrue(texts(plan.priorLocals().get(1).path("type")).contains("java.awt.Color"));
    }

    @Test
    void statementsAreTranslatedIntoInterpretableNodes() {
        List<ObjectNode> statements = plan(12).statements();

        ObjectNode assign = statements.get(1);
        assertEquals("assign", assign.path("s").asText());
        assertEquals("titulo", assign.path("target").path("field").asText());
        assertEquals("new", assign.path("value").path("e").asText());
        assertEquals("binary", assign.path("value").path("args").get(0).path("e").asText());
        JsonNode constant = assign.path("value").path("args").get(1);
        assertEquals("select", constant.path("e").asText());
        assertTrue(texts(constant.path("target").path("classes")).contains("javax.swing.SwingConstants"));

        ObjectNode selfField = statements.get(2);
        assertEquals("expr", selfField.path("s").asText());
        assertEquals("this", selfField.path("expr").path("target").path("target").path("e").asText());

        ObjectNode chained = statements.get(4);
        assertEquals("barra", chained.path("expr").path("target").path("name").asText());
        assertFalse(chained.path("expr").path("target").has("classes"));

        ObjectNode qualified = statements.get(6);
        assertEquals("javax.swing.JFrame",
                qualified.path("expr").path("args").get(0).path("target").path("name").asText());

        ObjectNode literals = statements.get(7);
        JsonNode arguments = literals.path("expr").path("args");
        assertEquals(-2, arguments.get(0).path("v").asInt());
        assertEquals("double", arguments.get(1).path("t").asText());
        assertEquals("char", arguments.get(2).path("t").asText());
        assertTrue(arguments.get(3).path("v").isNull());
    }

    @Test
    void lambdasAndControlFlowAreMarkedButNeverExecuted() {
        RecoveryPlanner.Plan plan = plan(12);
        List<ObjectNode> statements = plan.statements();

        ObjectNode lambda = statements.get(3);
        assertEquals("local", lambda.path("s").asText());
        assertTrue(lambda.path("synthetic").asBoolean());
        assertEquals("unsupported", statements.get(5).path("s").asText());
        assertEquals(1, plan.unsupported());
        assertEquals(17, statements.get(5).path("line").asInt());
    }

    @Test
    void nestedClassesAreFoundByBinaryName() {
        RecoveryPlanner.Plan plan = RecoveryPlanner.plan(SOURCE, "demo.Tela$Interna", "montar", 29)
                .orElseThrow();

        assertEquals(1, plan.statements().size());
        assertEquals("depois", plan.statements().getFirst().path("expr").path("name").asText());
    }

    @Test
    void unknownMethodsOrLinesProduceNoPlan() {
        assertTrue(RecoveryPlanner.plan(SOURCE, "demo.Tela", "naoExiste", 12).isEmpty());
        assertTrue(RecoveryPlanner.plan(SOURCE, "demo.Outra", "onDrawing", 12).isEmpty());
        assertTrue(RecoveryPlanner.plan(SOURCE, "demo.Tela", "onDrawing", 2).isEmpty());
    }

    @Test
    void lambdaMethodNamesMapToTheirEnclosingMethod() {
        assertEquals("onDrawing", new dtm.ide.swingdesigner.runtime.ViewWarning.Frame(
                "demo.Tela", "lambda$onDrawing$0", "Tela.java", 8).sourceMethod());
        assertEquals(List.of("Tela", "Interna"), RecoveryPlanner.simpleNames("demo.Tela$Interna"));
        assertEquals(List.of("Tela"), RecoveryPlanner.simpleNames("demo.Tela$1"));
    }

    private static RecoveryPlanner.Plan plan(int line) {
        return RecoveryPlanner.plan(SOURCE, "demo.Tela", "onDrawing", line).orElseThrow();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
