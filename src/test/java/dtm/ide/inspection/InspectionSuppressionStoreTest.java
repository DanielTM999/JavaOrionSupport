package dtm.ide.inspection;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionSuppressionStoreTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path FILE = ROOT.resolve("src/main/java/com/example/Pedido.java");
    private static final String ID = JavaInspection.JPA_EAGER_RELATION.id();
    private static final String ANCHOR = "private Cliente cliente;";

    @Test
    void hidesAndRestoresAnOccurrence(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);

        assertFalse(store.isSuppressed(ROOT, FILE, ID, ANCHOR));
        assertTrue(store.suppress(ROOT, FILE, ID, ANCHOR));
        assertTrue(store.isSuppressed(ROOT, FILE, ID, ANCHOR));

        String key = store.entriesOf(ROOT).getFirst().key();
        store.restore(key);

        assertFalse(store.isSuppressed(ROOT, FILE, ID, ANCHOR));
    }

    @Test
    void survivesAReload(@TempDir Path directory) {
        new InspectionSuppressionStore(directory).suppress(ROOT, FILE, ID, ANCHOR);

        InspectionSuppressionStore reopened = new InspectionSuppressionStore(directory);

        assertTrue(reopened.isSuppressed(ROOT, FILE, ID, ANCHOR));
    }

    @Test
    void doesNotLeakBetweenInspections(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        store.suppress(ROOT, FILE, ID, ANCHOR);

        assertFalse(store.isSuppressed(ROOT, FILE,
                JavaInspection.SPRING_FIELD_INJECTION.id(), ANCHOR));
    }

    @Test
    void doesNotLeakBetweenOccurrences(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        store.suppress(ROOT, FILE, ID, ANCHOR);

        assertFalse(store.isSuppressed(ROOT, FILE, ID, "private Loja loja;"));
    }

    @Test
    void doesNotLeakBetweenFiles(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        store.suppress(ROOT, FILE, ID, ANCHOR);

        assertFalse(store.isSuppressed(ROOT, ROOT.resolve("src/Outro.java"), ID, ANCHOR));
    }

    @Test
    void doesNotLeakBetweenProjects(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        store.suppress(ROOT, FILE, ID, ANCHOR);
        Path other = Path.of("/outro");

        assertFalse(store.isSuppressed(other, other.resolve("Pedido.java"), ID, ANCHOR));
    }

    @Test
    void restoresEveryOccurrenceOfAProject(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        store.suppress(ROOT, FILE, ID, ANCHOR);
        store.suppress(ROOT, FILE, ID, "private Loja loja;");

        store.restoreAll(ROOT);

        assertTrue(store.entriesOf(ROOT).isEmpty());
    }

    @Test
    void filtersDiagnosticsThroughTheStore(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);
        String source = """
                package com.example;

                @Entity
                public class Pedido {
                    @ManyToOne
                    private Cliente cliente;
                }
                """;
        int line = 5;
        store.suppress(ROOT, FILE, ID, ANCHOR);

        List<Diagnostic> kept = InspectionSuppressions.filter(
                List.of(new Diagnostic(line, 0, line, Integer.MAX_VALUE, DiagnosticSeverity.HINT,
                        "eager", ID, null)),
                source, Set.of(),
                (inspectionId, anchor) -> store.isSuppressed(ROOT, FILE, inspectionId, anchor));

        assertTrue(kept.isEmpty());
    }

    @Test
    void normalisesTheAnchorWhitespace() {
        assertEquals("private Cliente cliente;",
                InspectionSuppressions.anchorOf("    private   Cliente    cliente;   "));
    }

    @Test
    void ignoresABlankAnchor(@TempDir Path directory) {
        InspectionSuppressionStore store = new InspectionSuppressionStore(directory);

        assertFalse(store.suppress(ROOT, FILE, ID, "   "));
        assertEquals(0, store.size());
    }
}
