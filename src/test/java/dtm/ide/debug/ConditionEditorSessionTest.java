package dtm.ide.debug;

import dtm.ide.api.project.editor.ConditionStatus;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEditorSessionTest {

    private static final Path FILE = Path.of("src", "demo", "Demo.java").toAbsolutePath().normalize();
    private static final String SOURCE = """
            package demo;
            public class Demo {
                private int total;
                void run(int count) {
                    total += count;
                }
            }
            """;

    @Test
    void completionIsAskedAtTheSyntheticPositionAndImportEditsAreDropped() {
        FakeLanguage language = new FakeLanguage();
        language.completions = List.of(new AutoCompleteItem("total", "total", "int", "", null,
                AutoCompleteItem.Kind.FIELD,
                List.of(TextEdit.replace(Range.of(0, 0, 0, 0), "import java.util.List;\n"))));
        ConditionEditorSession session = session(language);

        List<AutoCompleteItem> items = session.suggestions("this.", 0, 5, "", "this.");

        assertEquals(List.of("total"), items.stream().map(AutoCompleteItem::label).toList());
        assertTrue(items.getFirst().additionalTextEdits().isEmpty());
        Request request = language.requests.getFirst();
        assertEquals(session.syntheticPath(), request.path());
        assertEquals(4, request.line());
        assertEquals(8 + 4 + 5, request.col());
        assertTrue(request.text().contains("        if (this.) { }"), request.text());
        session.close();
    }

    @Test
    void anEmptySemanticAnswerFallsBackToTheStaticScope() {
        FakeLanguage language = new FakeLanguage();
        ConditionEditorSession session = session(language);

        List<String> labels = session.suggestions("co", 0, 2, "co", "co").stream()
                .map(AutoCompleteItem::label).toList();

        assertEquals(List.of("count"), labels);
        session.close();
    }

    @Test
    void diagnosticsOutsideTheConditionAreIgnoredAndTheRestAreMapped() {
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(FILE, SOURCE, 4, "naoExiste > 1");

        List<Diagnostic> mapped = ConditionEditorSession.map(synthetic, "naoExiste > 1", List.of(
                new Diagnostic(1, 13, 1, 17, DiagnosticSeverity.ERROR, "duplicate type"),
                new Diagnostic(4, 12, 4, 21, DiagnosticSeverity.ERROR, "naoExiste cannot be resolved")));

        assertEquals(1, mapped.size());
        Diagnostic diagnostic = mapped.getFirst();
        assertEquals(0, diagnostic.startLine());
        assertEquals(0, diagnostic.startCol());
        assertEquals(9, diagnostic.endCol());
        assertEquals("naoExiste cannot be resolved", diagnostic.message());
    }

    @Test
    void theStatusFollowsTheTextAndTheDiagnostics() {
        FakeLanguage language = new FakeLanguage();
        ConditionEditorSession session = session(language);

        session.textChanged("   ");
        assertEquals(ConditionStatus.NONE, session.status());

        session.textChanged("naoExiste > 1");
        assertEquals(ConditionStatus.CHECKING, session.status());
        language.diagnostics = List.of(new Diagnostic(4, 12, 4, 21, DiagnosticSeverity.ERROR,
                "naoExiste cannot be resolved"));
        session.syncNow();
        assertEquals(ConditionStatus.INVALID, session.status());
        assertEquals("naoExiste cannot be resolved", session.statusMessage());
        assertTrue(language.changes.getLast().contains("if (naoExiste > 1) { }"));

        session.textChanged("count > 1");
        language.diagnostics = List.of();
        session.syncNow();
        assertEquals(ConditionStatus.VALID, session.status());
        session.close();
    }

    @Test
    void closingReleasesTheSyntheticDocumentOnce() {
        FakeLanguage language = new FakeLanguage();
        AtomicInteger closed = new AtomicInteger();
        ConditionEditorSession session = new ConditionEditorSession(FILE, 4, "", () -> SOURCE, language,
                () -> null, closed::incrementAndGet);

        session.close();
        session.close();

        assertEquals(List.of(session.syntheticPath()), language.closed);
        assertEquals(1, closed.get());
        assertTrue(session.suggestions("this.", 0, 5, "", "this.").isEmpty());
    }

    private static ConditionEditorSession session(FakeLanguage language) {
        return new ConditionEditorSession(FILE, 4, "", () -> SOURCE, language, () -> null, () -> {
        });
    }

    private record Request(Path path, String text, int line, int col) {
    }

    private static final class FakeLanguage implements ConditionLanguageService {
        private List<AutoCompleteItem> completions = List.of();
        private Collection<Diagnostic> diagnostics = List.of();
        private final List<Request> requests = new ArrayList<>();
        private final List<String> changes = new ArrayList<>();
        private final List<Path> closed = new ArrayList<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<AutoCompleteItem> complete(Path file, String text, int line, int col) {
            requests.add(new Request(file, text, line, col));
            return completions;
        }

        @Override
        public Collection<Diagnostic> diagnostics(Path file) {
            return diagnostics;
        }

        @Override
        public void changeDocument(Path file, String text) {
            changes.add(text);
        }

        @Override
        public void closeDocument(Path file) {
            closed.add(file);
        }
    }
}
