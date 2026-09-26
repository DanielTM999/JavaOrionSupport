package dtm.ide.debug;

import dtm.ide.lsp.JdtLsService;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public interface ConditionLanguageService {

    boolean available();

    List<AutoCompleteItem> complete(Path file, String text, int line, int col);

    Collection<Diagnostic> diagnostics(Path file);

    void changeDocument(Path file, String text);

    void closeDocument(Path file);

    static ConditionLanguageService of(Supplier<JdtLsService> lsp) {
        return new ConditionLanguageService() {
            @Override
            public boolean available() {
                JdtLsService current = lsp.get();
                return current != null && current.isInteractive();
            }

            @Override
            public List<AutoCompleteItem> complete(Path file, String text, int line, int col) {
                JdtLsService current = lsp.get();
                return current == null || !current.isInteractive()
                        ? List.of() : current.complete(file, text, line, col);
            }

            @Override
            public Collection<Diagnostic> diagnostics(Path file) {
                JdtLsService current = lsp.get();
                return current == null ? List.of() : current.diagnostics(file);
            }

            @Override
            public void changeDocument(Path file, String text) {
                JdtLsService current = lsp.get();
                if (current != null && current.isInteractive()) {
                    current.changeDocument(file, text);
                }
            }

            @Override
            public void closeDocument(Path file) {
                JdtLsService current = lsp.get();
                if (current != null) {
                    current.closeDocument(file);
                }
            }
        };
    }
}
