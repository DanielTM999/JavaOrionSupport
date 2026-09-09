package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionSuppressionsTest {

    private static final String ENTITY = """
            package com.example;

            @Entity
            public class Pedido {

                @ManyToOne
                private Cliente cliente;

                @SuppressWarnings("jpa.eagerRelation")
                @ManyToOne
                private Loja loja;

                @ManyToOne // orion:ignore jpa.eagerRelation
                private Entregador entregador;
            }
            """;

    private static Diagnostic hint(int line, JavaInspection inspection) {
        return new Diagnostic(line, 0, line, Integer.MAX_VALUE, DiagnosticSeverity.HINT,
                "mensagem", inspection.id(), null);
    }

    private static Diagnostic error(int line) {
        return new Diagnostic(line, 0, line, Integer.MAX_VALUE, DiagnosticSeverity.ERROR,
                "erro grave", JavaInspection.JPA_EAGER_RELATION.id(), null);
    }

    @Test
    void treatsNonErrorDiagnosticsOfKnownInspectionsAsSuppressible() {
        assertTrue(InspectionSuppressions.suppressible(hint(1, JavaInspection.JPA_EAGER_RELATION)));
        assertFalse(InspectionSuppressions.suppressible(error(1)));
    }

    @Test
    void ignoresDiagnosticsThatAreNotOurs() {
        Diagnostic foreign = new Diagnostic(1, 0, 1, 9, DiagnosticSeverity.WARNING,
                "vinda do servidor", "jdtls", null);

        assertFalse(InspectionSuppressions.suppressible(foreign));
    }

    @Test
    void suppressesWithSuppressWarningsOnTheMember() {
        String[] lines = ENTITY.split("\n", -1);
        int lojaLine = lineOf(ENTITY, "private Loja loja;");

        assertTrue(InspectionSuppressions.suppressedAt(lines, lojaLine,
                JavaInspection.JPA_EAGER_RELATION.id()));
    }

    @Test
    void suppressesWithAnInlineIgnoreComment() {
        String[] lines = ENTITY.split("\n", -1);
        int entregadorLine = lineOf(ENTITY, "private Entregador entregador;");

        assertTrue(InspectionSuppressions.suppressedAt(lines, entregadorLine,
                JavaInspection.JPA_EAGER_RELATION.id()));
    }

    @Test
    void doesNotSuppressAnUnmarkedMember() {
        String[] lines = ENTITY.split("\n", -1);
        int clienteLine = lineOf(ENTITY, "private Cliente cliente;");

        assertFalse(InspectionSuppressions.suppressedAt(lines, clienteLine,
                JavaInspection.JPA_EAGER_RELATION.id()));
    }

    @Test
    void doesNotLetSuppressionLeakToAnotherInspection() {
        String[] lines = ENTITY.split("\n", -1);
        int lojaLine = lineOf(ENTITY, "private Loja loja;");

        assertFalse(InspectionSuppressions.suppressedAt(lines, lojaLine,
                JavaInspection.SPRING_FIELD_INJECTION.id()));
    }

    @Test
    void honoursSuppressWarningsAll() {
        assertTrue(InspectionSuppressions.mentions("    @SuppressWarnings(\"all\")",
                JavaInspection.JPA_EAGER_RELATION.id()));
    }

    @Test
    void filterDropsGloballyDisabledInspections() {
        List<Diagnostic> diagnostics = List.of(
                hint(5, JavaInspection.JPA_EAGER_RELATION),
                hint(6, JavaInspection.SPRING_FIELD_INJECTION));

        List<Diagnostic> kept = InspectionSuppressions.filter(diagnostics, ENTITY,
                Set.of(JavaInspection.JPA_EAGER_RELATION.id()));

        assertEquals(1, kept.size());
        assertEquals(JavaInspection.SPRING_FIELD_INJECTION.id(), kept.getFirst().source());
    }

    @Test
    void disablingOneInspectionKeepsEveryOtherWarningVisible() {
        List<Diagnostic> diagnostics = List.of(
                hint(5, JavaInspection.JPA_EAGER_RELATION),
                hint(9, JavaInspection.JPA_EAGER_RELATION),
                hint(12, JavaInspection.SPRING_FIELD_INJECTION),
                hint(14, JavaInspection.JPA_NO_ARG_CONSTRUCTOR));

        List<Diagnostic> kept = InspectionSuppressions.filter(diagnostics, "",
                Set.of(JavaInspection.JPA_EAGER_RELATION.id()));

        assertEquals(2, kept.size());
        assertTrue(kept.stream().noneMatch(
                d -> JavaInspection.JPA_EAGER_RELATION.id().equals(d.source())));
        assertTrue(kept.stream().anyMatch(
                d -> JavaInspection.SPRING_FIELD_INJECTION.id().equals(d.source())));
        assertTrue(kept.stream().anyMatch(
                d -> JavaInspection.JPA_NO_ARG_CONSTRUCTOR.id().equals(d.source())));
    }

    @Test
    void hidingOneOccurrenceKeepsTheOthersOfTheSameType() {
        String source = """
                @ManyToOne
                private Cliente cliente;
                @ManyToOne
                private Loja loja;
                """;
        List<Diagnostic> diagnostics = List.of(
                hint(1, JavaInspection.JPA_EAGER_RELATION),
                hint(3, JavaInspection.JPA_EAGER_RELATION));

        List<Diagnostic> kept = InspectionSuppressions.filter(diagnostics, source, Set.of(),
                (id, anchor) -> "private Cliente cliente;".equals(anchor));

        assertEquals(1, kept.size());
        assertEquals(3, kept.getFirst().startLine());
    }

    @Test
    void filterKeepsErrorsEvenWhenTheInspectionIsDisabled() {
        List<Diagnostic> kept = InspectionSuppressions.filter(List.of(error(5)), ENTITY,
                Set.of(JavaInspection.JPA_EAGER_RELATION.id()));

        assertEquals(1, kept.size());
    }

    @Test
    void filterDropsInlineSuppressedDiagnostics() {
        int lojaLine = lineOf(ENTITY, "private Loja loja;");
        List<Diagnostic> kept = InspectionSuppressions.filter(
                List.of(hint(lojaLine, JavaInspection.JPA_EAGER_RELATION)), ENTITY, Set.of());

        assertTrue(kept.isEmpty());
    }

    @Test
    void buildsTheSuppressionKeepingIndentation() {
        String insertion = InspectionSuppressions.suppressionFor("        private Loja loja;",
                JavaInspection.JPA_EAGER_RELATION.id());

        assertEquals("        @SuppressWarnings(\"jpa.eagerRelation\")\n", insertion);
    }

    @Test
    void anchorsAboveTheExistingAnnotations() {
        String[] lines = ENTITY.split("\n", -1);
        int clienteLine = lineOf(ENTITY, "private Cliente cliente;");

        int anchor = InspectionSuppressions.anchorLineFor(lines, clienteLine);

        assertEquals(lineOf(ENTITY, "@ManyToOne"), anchor);
    }

    @Test
    void resolvesInspectionsById() {
        assertTrue(JavaInspection.byId("jpa.eagerRelation").isPresent());
        assertTrue(JavaInspection.byId("nao.existe").isEmpty());
        assertFalse(JavaInspection.all().isEmpty());
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
