package dtm.ide.lsp.api;

import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.settings.InlayHintsMode;
import dtm.ide.settings.JdtBuildMode;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public interface JavaLanguageServer {

    int ANY_VERSION = -1;

    String APPLY_CODE_ACTION_COMMAND = "java/applyCodeAction";

    <T> T extension(Class<T> type);

    LanguageServerState getState();

    boolean isWorkspaceSettled();

    boolean isInteractive();

    boolean isReady();

    String getLastError();

    Path getProjectRoot();

    Set<Character> completionTriggers();

    boolean isWarmingUp();

    boolean awaitReady(long timeoutMs);

    void setMaxHeap(String value);

    void setInlayHintsMode(InlayHintsMode value);

    void setBuildMode(JdtBuildMode value);

    void setSpringSupport(boolean enabled);

    void setStatusListener(StatusListener listener);

    void setWorkListener(WorkListener listener);

    void setCodeLensRefreshListener(Consumer<Path> listener);

    void setDocumentUpgradeListener(Consumer<Path> listener);

    void setLateCompletionListener(LateCompletionListener listener);

    void setWarmUpCompleteListener(Runnable listener);

    CompletableFuture<Void> start(Path root, JdkInstallation jdk, DownloadProgressListener progress);

    void resetCrashHistory();

    void stop();

    CompletableFuture<Void> stopAsync();

    void stopAsyncIfBoundTo(Path root);

    void resetProjectState();

    void shutdown();

    void openDocument(Path filePath, String text);

    void changeDocument(Path filePath, String text);

    void closeDocument(Path filePath);

    void saveDocument(Path filePath, String text);

    void pathCreated(Path createdPath);

    void pathChanged(Path changedPath);

    void pathDeleted(Path deletedPath);

    void requestExternalResync();

    void resynchronizeWithDisk();

    String documentContent(Path filePath);

    int documentVersion(Path filePath);

    <T> T withDocument(Path filePath, String diskText, Function<String, T> query);

    String prepareSave(Path file, String source, boolean organize, boolean format, int tabSize, boolean spaces);

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col);

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col, CompletionTrigger trigger,
                                    Character triggerCharacter, int expectedVersion);

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int col, CompletionTrigger trigger,
                                    Character triggerCharacter, int expectedVersion, boolean announceLateResult);

    CompletableFuture<List<AutoCompleteItem>> completeAsync(Path filePath, String text, int line, int col,
                                                            CompletionTrigger trigger, Character triggerCharacter,
                                                            int expectedVersion);

    CompletableFuture<AutoCompleteItem> resolveCompletionAsync(AutoCompleteItem item);

    List<AutoCompleteItem> cachedCompletions(Path filePath, String text, int line, int col);

    List<AutoCompleteItem> reusableCompletions(Path filePath, String text, int line, int col);

    void warmCompletion(Path filePath, String text, int line, int col);

    HoverInfo hover(Path filePath, String text, int line, int col);

    CompletableFuture<HoverInfo> hoverAsync(Path filePath, String text, int line, int col);

    SignatureHelp signatureHelp(Path filePath, String text, int line, int col);

    CompletableFuture<SignatureHelp> signatureHelpAsync(Path filePath, String text, int line, int col);

    CompletableFuture<List<Range>> selectionRangesAsync(Path filePath, String text, int line, int col);

    List<Location> definitions(Path filePath, String text, int line, int col);

    List<Location> definitionsInteractive(Path filePath, String text, int line, int col);

    Result navigation(Kind kind, Path file, String text, int line, int col);

    Result navigation(Kind kind, Path file, String text, int line, int col, long timeoutMs);

    boolean supportsTypeHierarchy();

    boolean supportsFoldingRanges();

    boolean supportsCallHierarchy();

    CompletableFuture<List<FoldRange>> foldingRangesAsync(Path filePath, String text);

    List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int col);

    List<CallHierarchyCall> incomingCalls(CallHierarchyItem item);

    List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item);

    List<TypeHierarchyItem> prepareTypeHierarchy(Path filePath, String text, int line, int col);

    List<TypeHierarchyItem> supertypes(TypeHierarchyItem item);

    List<TypeHierarchyItem> subtypes(TypeHierarchyItem item);

    List<DocumentSymbol> documentSymbols(Path filePath, String text);

    List<DocumentSymbol> documentSymbolsInteractive(Path filePath, String text);

    List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col);

    List<DocumentHighlight> documentHighlightsInteractive(Path filePath, String text, int line, int col);

    List<TextEdit> rename(Path filePath, String text, int line, int col, String newName);

    IdeWorkspaceEdit renameWorkspace(Path filePath, String text, int line, int col, String newName);

    String lastRenameProblem();

    PrepareRenameResult prepareRename(Path filePath, String text, int line, int col);

    IdeWorkspaceEdit willRenameFilesWorkspace(Map<Path, Path> renames);

    List<CodeAction> codeActions(Path filePath, String text, Range range, List<Diagnostic> diagnostics);

    ResolvedCodeAction resolveCodeAction(String rawJson);

    void executeCodeAction(String rawJson);

    String applyTextEdits(String text, List<TextEdit> edits);

    List<TypeSymbol> workspaceTypes(String query);

    List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine);

    CompletableFuture<List<InlayHint>> inlayHintsAsync(Path filePath, String text, int firstLine, int lastLine);

    List<JavaCodeLens> codeLenses(Path filePath, String text);

    List<SemanticToken> semanticTokens(Path filePath, String text);

    CompletableFuture<List<SemanticToken>> semanticTokensAsync(Path filePath, String text);

    String format(Path filePath, String text, int tabSize, boolean insertSpaces);

    Collection<Diagnostic> diagnostics(Path filePath);

    void clearDiagnostics();

    Diagnostic diagnosticAt(Path filePath, int line, int col);

    HoverInfo diagnosticHover(Path filePath, int line, int col);
}
