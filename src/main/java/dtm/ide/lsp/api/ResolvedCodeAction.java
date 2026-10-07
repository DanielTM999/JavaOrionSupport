package dtm.ide.lsp.api;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;

public record ResolvedCodeAction(IdeWorkspaceEdit edit, String commandJson) {
}
