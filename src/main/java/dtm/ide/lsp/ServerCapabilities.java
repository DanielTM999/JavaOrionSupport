package dtm.ide.lsp;

import java.util.Set;

record ServerCapabilities(
        boolean definition,
        boolean typeDefinition,
        boolean implementation,
        boolean references,
        boolean documentSymbol,
        boolean documentHighlight,
        boolean codeLens,
        boolean rename,
        boolean prepareRename,
        boolean formatting,
        boolean rangeFormatting,
        boolean codeAction,
        boolean signatureHelp,
        boolean inlayHint,
        boolean semanticTokens,
        boolean callHierarchy,
        boolean executeCommand,
        boolean workspaceSymbol,
        boolean typeHierarchy,
        boolean foldingRange,
        boolean completionResolve,
        boolean incrementalSync,
        Set<Character> completionTriggers,
        Set<Character> signatureTriggers) {

    static ServerCapabilities none() {
        return new ServerCapabilities(false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false,
                false, false, false, Set.of(), Set.of());
    }
}
