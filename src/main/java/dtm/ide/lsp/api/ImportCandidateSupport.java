package dtm.ide.lsp.api;

import dtm.stools.component.panels.editor.code.api.Range;

import java.nio.file.Path;
import java.util.Set;

public interface ImportCandidateSupport {

    ImportLookup importCandidates(Path filePath, String text, Range pasted, Set<String> handled);
}
