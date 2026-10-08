package dtm.ide.adapter;

import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.api.project.editor.IdeCallHierarchyContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeDocumentSymbolContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.search.GlobalSearchMatch;
import dtm.ide.api.search.GlobalSearchQuery;
import dtm.ide.api.search.GlobalSearchResult;
import dtm.ide.deps.PomProperties;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.lsp.UsagesPopup;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.navigation.JavaNavigation.Extent;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.spring.SpringNavigation;
import dtm.ide.spring.SpringPropertyUsage;
import dtm.ide.spring.SpringSearchContributor;
import dtm.ide.spring.config.SpringConfigDocument;
import dtm.ide.spring.config.SpringConfigIndex;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import lombok.extern.slf4j.Slf4j;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class NavigationSupport {

    private static final int NAVIGATION_RETRIES = 2;
    private static final long NAVIGATION_RETRY_DELAY_MS = 80;

    private final AdapterHost host;

    public NavigationSupport(AdapterHost host) {
        this.host = host;
    }

    public CompletableFuture<SignatureHelp> provideSignatureHelpAsync(IdeSignatureHelpContext context) {
        if (host.debugActive()) {
            return CompletableFuture.completedFuture(null);
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context == null ? null : context.filePath());
        return lsp == null ? CompletableFuture.completedFuture(null)
                : lsp.signatureHelpAsync(context.filePath(), context.text(),
                context.caretLine(), context.caretCol());
    }

    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        if (host.debugActive()) {
            return null;
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context == null ? null : context.filePath());
        return lsp == null ? null : lsp.signatureHelp(context.filePath(), context.text(),
                context.caretLine(), context.caretCol());
    }

    public Set<Character> getSignatureTriggerCharacters() {
        return Set.of('(', ',');
    }

    public Set<Character> getSignatureRetriggerCharacters() {
        return Set.of(',');
    }

    public GlobalSearchResult search(GlobalSearchQuery query, GlobalSearchResult defaultResult) {
        JavaProjectDescriptor current = host.descriptor();
        if (query == null || current == null || !current.spring()
                || !host.settings().isSpringSupport()) {
            return defaultResult;
        }
        List<GlobalSearchMatch> matches =
                SpringSearchContributor.search(host.spring().index().snapshot(), query.term());
        if (matches.isEmpty()) {
            return defaultResult;
        }
        GlobalSearchResult spring = GlobalSearchResult.of(matches);
        return defaultResult == null ? spring : spring.merge(defaultResult);
    }

    public List<Location> findDefinitions(IdeDefinitionContext context) {
        if (context == null) {
            return null;
        }
        if (JavaProjectConventions.isMavenPom(context.filePath())) {
            return pomTarget(context.filePath(), context.text(), context.offset())
                    .map(target -> List.of(target.location())).orElseGet(List::of);
        }
        List<Location> configTargets = configKeyUsages(context);
        if (configTargets != null) {
            return configTargets;
        }
        return resolveDefinitions(context.filePath(), context.text(),
                context.line(), context.col());
    }

    public List<Location> findReferences(IdeDefinitionContext context) {
        if (context == null) {
            return null;
        }
        List<Location> configTargets = configKeyUsages(context);
        if (configTargets != null) {
            return configTargets;
        }
        return resolveReferences(context.filePath(), context.text(),
                context.line(), context.col());
    }

    public List<Location> configKeyUsages(IdeDefinitionContext context) {
        Path filePath = context.filePath();
        if (!SpringConfigSupport.isConfigFile(filePath) || !isSpringConfigNavigationEnabled()) {
            return null;
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(filePath.getFileName().toString());
        String key = SpringConfigDocument.keyAt(context.text(), context.line(), format);
        if (key == null || key.isBlank()) {
            return List.of();
        }
        List<SpringNavigation.Anchor> anchors = new ArrayList<>();
        for (SpringPropertyUsage usage : host.spring().index().snapshot().usagesOfProperty(key)) {
            anchors.add(new SpringNavigation.Anchor(usage.file(), usage.line(), usage.key()));
        }
        return CodeLensSupport.springLocations(new SpringNavigation.Target(
                SpringNavigation.Kind.CONFIG_KEY, key, anchors));
    }

    public boolean isSpringConfigNavigationEnabled() {
        JavaProjectDescriptor current = host.descriptor();
        return current != null && current.spring() && host.settings().isSpringSupport()
                && host.settings().isSpringConfigNavigation();
    }

    public List<Location> configKeyDefinitions(String key) {
        if (key == null || key.isBlank() || !isSpringConfigNavigationEnabled()) {
            return List.of();
        }
        List<SpringNavigation.Anchor> anchors = new ArrayList<>();
        for (SpringConfigIndex.Entry entry : host.spring().configIndex().definitionsOf(key)) {
            anchors.add(new SpringNavigation.Anchor(entry.file(), entry.line(), entry.key()));
        }
        return CodeLensSupport.springLocations(new SpringNavigation.Target(
                SpringNavigation.Kind.CONFIG_KEY, key, anchors));
    }

    public List<DocumentSymbol> getDocumentSymbols(IdeDocumentSymbolContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JavaLanguageServer lsp = host.interactiveServerFor(filePath);
        List<DocumentSymbol> precise = lsp == null ? List.of()
                : lsp.isReady()
                        ? lsp.documentSymbols(filePath, context.text())
                        : lsp.documentSymbolsInteractive(filePath, context.text());
        if (precise != null && !precise.isEmpty()) {
            return precise;
        }
        List<DocumentSymbol> outline = host.lexicalIndex().outline(context.text());
        return outline.isEmpty() ? precise : outline;
    }

    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JavaLanguageServer lsp = host.interactiveServerFor(filePath);
        List<DocumentHighlight> precise = lsp == null ? List.of()
                : lsp.isReady()
                        ? lsp.documentHighlights(filePath, context.text(),
                                context.line(), context.col())
                        : lsp.documentHighlightsInteractive(filePath, context.text(),
                                context.line(), context.col());
        if (precise != null && !precise.isEmpty()) {
            return precise;
        }
        if (lsp != null && lsp.isReady()) {
            return precise;
        }
        List<DocumentHighlight> approximate = bufferHighlights(context.text(),
                context.line(), context.col());
        return approximate.isEmpty() ? precise : approximate;
    }

    private static List<DocumentHighlight> bufferHighlights(String text, int line, int col) {
        JavaLocalScope.Scope scope = JavaLocalScope.at(text, line, col);
        if (scope == null) return List.of();
        List<DocumentHighlight> result = new ArrayList<>();
        result.add(new DocumentHighlight(scope.declaration(), DocumentHighlight.Kind.TEXT));
        scope.usages().forEach(range -> result.add(new DocumentHighlight(range, DocumentHighlight.Kind.TEXT)));
        return List.copyOf(result);
    }

    public boolean isGoToDeclarationEnabled() {
        return true;
    }

    public boolean isGoToImplementationEnabled() {
        return true;
    }

    public boolean isFindUsagesEnabled() {
        return true;
    }

    public void onWordClick(IdeWordClickContext context) {
        if (isCtrlPomClick(context)) {
            navigatePom(context.filePath(), context.text(), context.startOffset());
            return;
        }
        if (!isCtrlDefinitionClick(context)) return;
        long ticket = beginNavigation();
        long session = host.lifecycleTicket();
        long clickStart = System.nanoTime();
        host.background().submit(() -> {
            IdeEditorContext editor = context.editorContext();
            Supplier<NavigationRequest> live = () -> NavigationRequest.of(editor);
            NavigationRequest request = new NavigationRequest(context.text(), context.line(), context.col());
            Result result = resolveCurrent(live, request, context.filePath(), Kind.DEFINITION, false).result();
            Kind kind = Kind.DEFINITION;
            if (isResolved(result) && isOwnDeclaration(result.locations(), context)) {
                kind = Kind.REFERENCES;
                result = JavaNavigation.restrict(resolveCurrent(live, request, context.filePath(), kind, false)
                        .result(), Extent.DOCUMENT, context.filePath());
            }
            publishNavigationResult(ticket, session, context.editorContext(), context.filePath(),
                    context.text(), kind, result);
            if (log.isDebugEnabled()) {
                log.debug("ctrl+click resolvido em {}ms ({} destinos)",
                        elapsedMs(clickStart), result.locations().size());
            }
        });
    }

    public record NavigationRequest(String text, int line, int col) {
        public static NavigationRequest of(IdeEditorContext editor) {
            return editor == null ? null : UiThreads.onUi(() -> new NavigationRequest(
                    editor.getText(), editor.getCaretLine(), editor.getCaretCol()));
        }
    }

    public record ResolvedNavigation(NavigationRequest request, Result result) { }

    public ResolvedNavigation resolveCurrent(Supplier<NavigationRequest> live, NavigationRequest request,
                                      Path file, Kind kind, boolean followEdits) {
        Result result = resolveNavigation(file, request.text(), request.line(), request.col(), kind);
        for (int attempt = 0; attempt < NAVIGATION_RETRIES && shouldRetryNavigation(result); attempt++) {
            NavigationRequest current = live == null ? null : live.get();
            if (current == null || current.text() == null) break;
            if (!current.text().equals(request.text())) {
                if (!followEdits) break;
                request = current;
            }
            try {
                Thread.sleep(NAVIGATION_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            result = resolveNavigation(file, request.text(), request.line(), request.col(), kind);
        }
        return new ResolvedNavigation(request, result);
    }

    public static boolean shouldRetryNavigation(Result result) {
        return result != null && (result.status() == Status.STALE
                || result.status() == Status.FAILED && result.locations().isEmpty());
    }

    public long beginNavigation() {
        host.setStatusBarText(text("status.navigation.loading", "Java: buscando destinos..."));
        return host.navigationRequestTicket().incrementAndGet();
    }

    public void publishNavigationResult(long ticket, long session, IdeEditorContext editor,
                                         Path file, String requestedText, Kind kind, Result result) {
        if (ticket != host.navigationRequestTicket().get() || session != host.lifecycleTicket()) return;
        List<UsagesPopup.Item> items = needsUsagesPopup(kind, result.locations())
                ? host.navigationViews().buildUsageItems(result.locations(), file, requestedText) : List.of();
        SwingUtilities.invokeLater(() -> {
            if (ticket != host.navigationRequestTicket().get() || session != host.lifecycleTicket()) return;
            if (editor == null || host.liveEditorFor(file) == null) {
                host.setStatusBarText(text("status.navigation.unavailable",
                        "Java: navegacao semantica indisponivel"));
                return;
            }
            String current = editor.getText();
            if (!Objects.equals(requestedText, current)) {
                JavaLanguageServer lsp = host.languageServer();
                if (lsp != null && current != null) host.background().submit(() -> lsp.changeDocument(file, current));
                host.setStatusBarText(text("status.navigation.stale",
                        "Java: o codigo mudou; tente novamente"));
                return;
            }
            showNavigationResult(editor, kind, result, items);
        });
    }

    private static boolean needsUsagesPopup(Kind kind, List<Location> targets) {
        return targets != null && !targets.isEmpty()
                && (targets.size() > 1 || kind == Kind.REFERENCES);
    }

    private static List<Location> localDeclaration(Path filePath, JavaLocalScope.Scope scope) {
        Path file = JavaProjectConventions.normalize(filePath);
        return file == null ? List.of()
                : List.of(Location.of(file.toUri().toString(), scope.declaration()));
    }

    public static boolean isResolved(Result result) {
        return result != null && (result.status() == Status.COMPLETE || result.status() == Status.LOCAL);
    }

    public static boolean isOwnDeclaration(List<Location> definitions, IdeWordClickContext context) {
        return context != null && definitions != null && definitions.size() == 1
                && JavaNavigation.contains(definitions.getFirst(), context.filePath(), context.line(), context.col());
    }

    public static boolean isCtrlDefinitionClick(IdeWordClickContext context) {
        return isCtrlClick(context) && JavaProjectConventions.isJava(context.filePath());
    }

    public static boolean isCtrlPomClick(IdeWordClickContext context) {
        return isCtrlClick(context) && JavaProjectConventions.isMavenPom(context.filePath());
    }

    private static boolean isCtrlClick(IdeWordClickContext context) {
        return context != null
                && context.filePath() != null
                && context.editorContext() != null
                && context.mouseButton() == MouseEvent.BUTTON1
                && (context.modifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
    }

    public record PomTarget(Path file, int line, int col) {
        Location location() {
            Position position = new Position(line, col);
            return new Location(file.toUri().toString(), new Range(position, position));
        }
    }

    public Optional<PomTarget> pomTarget(Path file, String text, int offset) {
        if (file == null || text == null) {
            return Optional.empty();
        }
        Optional<PomProperties.Placeholder> placeholder = PomProperties.placeholderAt(text, offset);
        if (placeholder.isPresent()) {
            return host.pomProperties().find(file, text, placeholder.get().name())
                    .filter(PomProperties.Declaration::navigable)
                    .map(declaration -> new PomTarget(declaration.file(), declaration.line(),
                            declaration.col()));
        }
        return host.pomProperties().parentAt(file, text, offset)
                .map(parent -> new PomTarget(parent.file(), parent.line(), parent.col()));
    }

    public void navigatePom(Path file, String text, int offset) {
        long session = host.lifecycleTicket();
        host.background().submit(() -> {
            Optional<PomTarget> target;
            try {
                target = pomTarget(file, text, offset);
            } catch (Exception e) {
                log.debug("Falha ao resolver destino no pom: {}", e.getMessage());
                target = Optional.empty();
            }
            Optional<PomTarget> resolved = target;
            SwingUtilities.invokeLater(() -> {
                if (session != host.lifecycleTicket()) return;
                if (resolved.isEmpty()) {
                    host.setStatusBarText(text("status.pomTargetMissing",
                            "Maven: declaracao nao encontrada"));
                    return;
                }
                host.openAt(resolved.get().file(), resolved.get().line(), resolved.get().col());
            });
        });
    }

    public void onGoToDeclaration(IdeEditorContext context) {
        navigateFromEditor(context, "definition");
    }

    public void onGoToImplementation(IdeEditorContext context) {
        navigateFromEditor(context, "implementation");
    }

    public void onFindUsages(IdeEditorContext context) {
        navigateFromEditor(context, "usages");
    }

    public boolean isCallHierarchyEnabled() {
        JavaLanguageServer lsp = host.languageServer();
        return lsp != null && lsp.isInteractive() && lsp.supportsCallHierarchy();
    }

    public boolean isTypeHierarchyEnabled() {
        JavaLanguageServer lsp = host.languageServer();
        return lsp != null && lsp.isInteractive() && lsp.supportsTypeHierarchy();
    }

    public List<TypeHierarchyItem> prepareTypeHierarchy(IdeCallHierarchyContext context) {
        JavaLanguageServer lsp = context == null ? null : host.interactiveServerFor(context.filePath());
        return lsp == null ? List.of()
                : lsp.prepareTypeHierarchy(context.filePath(), context.text(), context.line(), context.col());
    }

    public List<TypeHierarchyItem> getSupertypes(TypeHierarchyItem item) {
        JavaLanguageServer lsp = host.languageServer();
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.supertypes(item);
    }

    public List<TypeHierarchyItem> getSubtypes(TypeHierarchyItem item) {
        JavaLanguageServer lsp = host.languageServer();
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.subtypes(item);
    }

    public List<CallHierarchyItem> prepareCallHierarchy(IdeCallHierarchyContext context) {
        if (context == null) {
            return List.of();
        }
        JavaLanguageServer lsp = host.interactiveServerFor(context.filePath());
        if (lsp == null) {
            return List.of();
        }
        return lsp.prepareCallHierarchy(context.filePath(), context.text(),
                context.line(), context.col());
    }

    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        JavaLanguageServer lsp = host.languageServer();
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.incomingCalls(item);
    }

    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        JavaLanguageServer lsp = host.languageServer();
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.outgoingCalls(item);
    }

    public void navigateFromEditor(IdeEditorContext context, String action) {
        if (context != null && JavaProjectConventions.isMavenPom(context.filePath())) {
            if ("definition".equals(action)) {
                navigatePom(context.filePath(), context.getText(), context.getCaretOffset());
            }
            return;
        }
        if (context == null || !isNavigationAvailable(context.filePath())) return;
        Path file = context.filePath();
        NavigationRequest request = new NavigationRequest(context.getText(),
                context.getCaretLine(), context.getCaretCol());
        long ticket = beginNavigation(), session = host.lifecycleTicket();
        Kind kind = Kind.forAction(action);
        host.background().submit(() -> {
            ResolvedNavigation resolved = resolveCurrent(() -> NavigationRequest.of(context),
                    request, file, kind, true);
            publishNavigationResult(ticket, session, context, file, resolved.request().text(),
                    kind, resolved.result());
        });
    }

    public boolean isNavigationAvailable(Path filePath) {
        return JavaProjectConventions.isJava(filePath);
    }

    public void showNavigationResult(IdeEditorContext context, Kind kind, Result result,
                                      List<UsagesPopup.Item> items) {
        String status = switch (result.status()) {
            case COMPLETE -> text("status.navigation.empty", "Java: nenhum destino encontrado");
            case LOCAL -> text("status.navigation.local", "Java: resultado local");
            case INDEXING -> text("status.navigation.indexing", "Java: indexacao em andamento; tente novamente");
            case UNAVAILABLE -> text("status.navigation.unavailable", "Java: navegacao semantica indisponivel");
            case FAILED -> text("status.navigation.failed", "Java: a busca falhou; tente novamente");
            case STALE -> text("status.navigation.stale", "Java: o codigo mudou; tente novamente");
        };
        List<Location> targets = result.locations();
        host.setStatusBarText(targets.isEmpty() && result.status() == Status.LOCAL
                ? text("status.navigation.empty", "Java: nenhum destino encontrado") : status);
        if (targets.isEmpty()) return;
        if (result.status() == Status.COMPLETE) host.setStatusBarText(text("status.navigation.done", "Java: busca concluida"));
        if (targets.size() == 1 && kind != Kind.REFERENCES) {
            Location target = targets.getFirst();
            host.navigateToLocation(target, JavaNavigation.path(target));
            return;
        }
        host.navigationViews().openUsagesPopup(context, null, host.codeLens().countLabel(kind, items.size()), items);
    }

    public List<Location> resolveDefinitions(Path filePath, String text, int line, int col) {
        return resolveNavigation(filePath, text, line, col, Kind.DEFINITION).locations();
    }

    public Result resolveNavigation(Path filePath, String source, int line, int col, Kind kind) {
        if (!JavaProjectConventions.isJava(filePath)) return Result.of(Status.UNAVAILABLE);
        long springStart = System.nanoTime();
        SpringNavigation.Target spring = springTargetAt(filePath, source, line, col);
        long springMs = elapsedMs(springStart);
        if (kind == Kind.DEFINITION && spring != null && spring.kind() == SpringNavigation.Kind.CONFIG_KEY) {
            List<Location> keys = configKeyDefinitions(spring.token());
            if (!keys.isEmpty()) return new Result(Status.LOCAL, keys);
        }
        JavaLanguageServer lsp = host.interactiveServerFor(filePath);
        long lspStart = System.nanoTime();
        Result semantic = lsp == null ? Result.of(host.isIndexing(filePath) ? Status.INDEXING : Status.UNAVAILABLE)
                : lsp.navigation(kind, filePath, source, line, col);
        long lspMs = elapsedMs(lspStart);
        if (semantic.status() == Status.COMPLETE || semantic.status() == Status.STALE
                || !semantic.locations().isEmpty()) {
            logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, 0);
            return semantic;
        }
        if (kind != Kind.IMPLEMENTATION) {
            long scopeStart = System.nanoTime();
            JavaLocalScope.Scope scope = JavaLocalScope.at(source, line, col);
            long scopeMs = elapsedMs(scopeStart);
            logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, scopeMs);
            if (scope != null) return new Result(Status.LOCAL, kind == Kind.DEFINITION
                    ? localDeclaration(filePath, scope)
                    : scope.usages().stream().map(range -> Location.of(
                            filePath.toAbsolutePath().normalize().toUri().toString(), range)).toList());
            return semantic;
        }
        logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, 0);
        return semantic;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void logNavigationTiming(Kind kind, Path filePath, Status status,
                                            long springMs, long lspMs, long scopeMs) {
        if (!log.isDebugEnabled()) return;
        log.debug("navegacao {} em {}: status={} spring={}ms lsp={}ms localScope={}ms",
                kind, filePath == null ? "?" : filePath.getFileName(), status, springMs, lspMs, scopeMs);
    }

    public SpringNavigation.Target springTargetAt(Path filePath, String text, int line, int col) {
        if (!host.isSpringNavigationEnabled()) {
            return null;
        }
        return SpringNavigation.definitions(host.spring().index().snapshot(), filePath, text, line, col)
                .orElse(null);
    }

    public List<Location> resolveReferences(Path filePath, String text, int line, int col) {
        return resolveNavigation(filePath, text, line, col, Kind.REFERENCES).locations();
    }

    public static String identifierAt(String text, int line, int col) {
        if (text == null || text.isEmpty() || line < 0 || col < 0) {
            return null;
        }
        int offset = 0;
        for (int current = 0; current < line; current++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                return null;
            }
            offset = next + 1;
        }
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        int caret = Math.min(offset + col, lineEnd);
        int start = caret;
        while (start > offset && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < lineEnd && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (start >= end) {
            return null;
        }
        String word = text.substring(start, end);
        return Character.isJavaIdentifierStart(word.charAt(0)) ? word : null;
    }
}
