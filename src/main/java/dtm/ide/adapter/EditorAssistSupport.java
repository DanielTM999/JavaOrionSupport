package dtm.ide.adapter;

import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeInlayHintContext;
import dtm.ide.api.project.editor.IdeSelectionRangeContext;
import dtm.ide.api.project.editor.IdeSemanticTokensContext;
import dtm.ide.api.project.editor.SelectionRangeContext;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.editor.TextOffsets;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class EditorAssistSupport {

    private static final long SELECTION_RANGE_TIMEOUT_MS = 1_000;

    private final AdapterHost host;

    public EditorAssistSupport(AdapterHost host) {
        this.host = host;
    }

    public List<Range> getSelectionRanges(IdeSelectionRangeContext context) {
        if (context == null) {
            return null;
        }
        try {
            List<Range> chain = selectionChain(context.filePath(), context.text(), context.offset())
                    .get(SELECTION_RANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return chain.isEmpty() ? null : chain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            log.debug("Extend selection indisponivel: {}", failure.getMessage());
            return null;
        }
    }

    public CompletableFuture<List<int[]>> getSelectionRanges(SelectionRangeContext context) {
        if (context == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return selectionChain(context.filePath(), context.text(), context.offset())
                .thenApply(chain -> TextOffsets.offsets(context.text(), chain));
    }

    private CompletableFuture<List<Range>> selectionChain(Path filePath, String text, int offset) {
        JavaLanguageServer lsp = host.interactiveServerFor(filePath);
        if (lsp == null || !JavaProjectConventions.isJava(filePath)) {
            return CompletableFuture.completedFuture(List.of());
        }
        Position position = TextOffsets.position(text, offset);
        return lsp.selectionRangesAsync(filePath, text, position.line(), position.col());
    }

    public CompletableFuture<HoverInfo> getHoverAsync(IdeHoverContext context) {
        if (context == null || host.debug().isDebugPaused() || SpringConfigSupport.isConfigFile(context.filePath())) {
            return CompletableFuture.completedFuture(getHover(context));
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context.filePath());
        if (lsp == null) {
            return CompletableFuture.completedFuture(null);
        }
        HoverInfo diagnostic = lsp.diagnosticHover(context.filePath(), context.line(), context.col());
        return diagnostic != null ? CompletableFuture.completedFuture(diagnostic)
                : lsp.hoverAsync(context.filePath(), context.text(), context.line(), context.col());
    }

    public HoverInfo getHover(IdeHoverContext context) {
        if (context == null) {
            return null;
        }
        if (host.debug().isDebugPaused()) {
            return null;
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            return SpringConfigSupport.hover(host.spring().metadata(), context.filePath(),
                    context.text(), context.line());
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context.filePath());
        if (lsp == null) {
            return null;
        }
        HoverInfo diagnostic = lsp.diagnosticHover(
                context.filePath(), context.line(), context.col());
        return diagnostic != null ? diagnostic
                : lsp.hover(context.filePath(), context.text(), context.line(), context.col());
    }

    public CompletableFuture<List<InlayHint>> getInlayHintsAsync(IdeInlayHintContext context) {
        JavaLanguageServer lsp = host.runningServerFor(context == null ? null : context.filePath());
        return lsp == null ? CompletableFuture.completedFuture(null)
                : lsp.inlayHintsAsync(context.filePath(), context.text(), context.firstLine(), context.lastLine());
    }

    public List<InlayHint> getInlayHints(IdeInlayHintContext context) {
        JavaLanguageServer lsp = host.runningServerFor(context == null ? null : context.filePath());
        return lsp == null ? null : lsp.inlayHints(context.filePath(), context.text(),
                context.firstLine(), context.lastLine());
    }

    public CompletableFuture<List<SemanticToken>> getSemanticTokensAsync(IdeSemanticTokensContext context) {
        JavaLanguageServer lsp = host.languageServer();
        if (context == null || lsp == null || !lsp.isReady()
                || !JavaProjectConventions.isJava(context.filePath())
                || !host.settings().getLanguageServerMode().startsServer()) {
            return CompletableFuture.completedFuture(getSemanticTokens(context));
        }
        return lsp.semanticTokensAsync(context.filePath(), context.text());
    }

    public List<SemanticToken> getSemanticTokens(IdeSemanticTokensContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)
                || !host.settings().getLanguageServerMode().startsServer()) {
            return null;
        }
        JavaLanguageServer lsp = host.languageServer();
        LanguageServerState state = lsp == null
                ? LanguageServerState.NOT_STARTED : lsp.getState();
        if (state == LanguageServerState.ERROR || state == LanguageServerState.STOPPED) {
            return null;
        }
        if (lsp == null || !lsp.isReady()) {
            return List.of();
        }
        return lsp.semanticTokens(filePath, context.text());
    }

    public String formatCode(FormatCodeContext context) {
        Path file = context == null ? null : context.file();
        JavaLanguageServer lsp = host.runningServerFor(file);
        if (lsp == null) {
            if (host.isIndexing(file)) {
                host.setStatusBarText(text("status.formatDuringIndexing",
                        "Java: formatacao disponivel apos a indexacao"));
            }
            return null;
        }
        return lsp.format(context.file(), context.fullText(),
                context.tabSize(), context.useSpacesForTab());
    }
}
