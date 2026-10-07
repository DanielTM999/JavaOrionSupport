package dtm.ide.lsp.api;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;

import java.nio.file.Path;
import java.util.List;

public interface TypeMoveSupport {

    IdeWorkspaceEdit moveTypesWorkspace(List<Path> sources, Path targetDirectory);

    String lastMoveProblem();
}
