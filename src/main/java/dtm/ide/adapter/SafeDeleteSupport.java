package dtm.ide.adapter;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.refactor.JavaSafeDeleteScanner;
import dtm.ide.ui.JavaDeleteDialogPanel;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class SafeDeleteSupport {

    private static final long DELETE_REFERENCES_TIMEOUT_MS = 30_000;
    private static final int DELETE_SEARCH_PARALLELISM = 4;
    private static final Color DELETE_ACCENT = new Color(220, 53, 69);

    private final AdapterHost host;

    public SafeDeleteSupport(AdapterHost host) {
        this.host = host;
    }

    public boolean canDeletePaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return true;
        }

        List<Path> targets = paths.stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();

        boolean safeDeleteAvailable = targets.stream().anyMatch(SafeDeleteSupport::containsJavaSource);
        JavaDeleteDialogPanel panel = UiThreads.onUi(() -> new JavaDeleteDialogPanel(
                targets, host.projectRoot(), host.settings().isSafeDelete(), safeDeleteAvailable));

        if (panel == null) {
            return false;
        }

        Boolean confirmed = UiThreads.onUi(() -> host.<Boolean>createModernComponentDialogBuilder()
                .title(text("delete.title", "Delete"))
                .type(ModernDialog.Type.QUESTION)
                .accentColor(DELETE_ACCENT)
                .showTypeLabel(false)
                .component(panel)
                .option(text("delete.confirm", "Delete"), Boolean.TRUE, DELETE_ACCENT, Color.WHITE)
                .cancelOption(text("delete.cancel", "Cancel"))
                .show());

        if (!Boolean.TRUE.equals(confirmed)) {
            return false;
        }

        boolean safeDelete = safeDeleteAvailable && Boolean.TRUE.equals(UiThreads.onUi(panel::isSafeDeleteSelected));
        if (safeDeleteAvailable) {
            host.settings().setSafeDelete(safeDelete);
            host.settings().save();
        }

        return !safeDelete || confirmUsages(targets);
    }

    private boolean confirmUsages(List<Path> targets) {
        JavaSafeDeleteScanner.ScanResult result = findExternalUsages(targets);
        List<Location> usages = result.locations();
        if (usages.isEmpty() && result.complete()) {
            return true;
        }

        String message = !result.complete()
                ? text("delete.incomplete", "A busca de usos ficou incompleta; nao foi possivel verificar todos os arquivos.")
                : usages.size() == 1
                ? text("delete.usagesOne", "1 usage was found outside the selection.")
                : usages.size() + text("delete.usagesMany", " usages were found outside the selection.");

        Integer choice = UiThreads.onUi(() -> host.createModernDialogBuilder()
                .type(ModernDialog.Type.QUESTION)
                .accentColor(DELETE_ACCENT)
                .title(text("delete.usagesTitle", "Usages detected"))
                .message(message + " " + text("delete.usagesQuestion", "Delete anyway?"))
                .option(text("delete.viewUsages", "View usages"), 2)
                .option(text("delete.deleteAnyway", "Delete anyway"), 0, DELETE_ACCENT, Color.WHITE)
                .option(text("delete.cancel", "Cancel"), 1, new Color(90, 90, 90), Color.WHITE)
                .show());

        if (choice != null && choice == 2) {
            host.showUsagesPopup(usages, null, null, null, null, Kind.REFERENCES);
            return false;
        }

        return choice != null && choice == 0;
    }

    private JavaSafeDeleteScanner.ScanResult findExternalUsages(List<Path> targets) {
        JavaLanguageServer lsp = host.languageServer();
        Set<Path> deleted = new LinkedHashSet<>(targets);
        Map<String, Location> unique = new LinkedHashMap<>();
        boolean semanticComplete = lsp != null && lsp.isReady() && !lsp.isWarmingUp()
                && semanticUsages(lsp, targets, deleted, unique);
        if (semanticComplete) {
            return new JavaSafeDeleteScanner.ScanResult(List.copyOf(unique.values()), true);
        }

        Map<Path, String> buffers = UiThreads.onUi(() -> {
            Map<Path, String> snapshots = new HashMap<>();
            host.javaEditors().forEach((file, editor) -> snapshots.put(file, editor.getText()));
            return snapshots;
        });
        JavaSafeDeleteScanner.ScanResult scan = JavaSafeDeleteScanner.scan(host.projectRoot(), targets,
                buffers == null ? Map.of() : buffers);
        for (Location location : scan.locations()) {
            Path referenced = JavaNavigation.path(location);
            if (referenced != null && !isInside(referenced, deleted)) {
                unique.putIfAbsent(UiThreads.locationKey(location), location);
            }
        }
        return new JavaSafeDeleteScanner.ScanResult(List.copyOf(unique.values()), false);
    }

    private boolean semanticUsages(JavaLanguageServer lsp, List<Path> targets, Set<Path> deleted,
                                   Map<String, Location> unique) {
        record Search(Path source, String content, Range declaration) {
        }
        boolean complete = true;
        List<Path> temporarilyOpened = new ArrayList<>();
        List<Search> searches = new ArrayList<>();
        try {
            for (Path source : collectJavaSources(targets)) {
                IdeEditorContext openEditor = host.editorContextFor(source);
                String content = openEditor == null ? readSource(source) : UiThreads.onUi(openEditor::getText);
                if (content == null) {
                    complete = false;
                    continue;
                }
                if (host.getEditor(source) == null) {
                    temporarilyOpened.add(source);
                }
                lsp.openDocument(source, content);
                List<Range> declarations = typeDeclarationRanges(lsp.documentSymbols(source, content));
                if (declarations.isEmpty()) {
                    complete = false;
                }
                declarations.forEach(range -> searches.add(new Search(source, content, range)));
            }
            if (searches.isEmpty()) {
                return false;
            }
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(DELETE_SEARCH_PARALLELISM, searches.size()));
            try {
                List<Future<Result>> results = new ArrayList<>();
                for (Search search : searches) {
                    results.add(pool.submit(() -> lsp.navigation(Kind.REFERENCES, search.source(),
                            search.content(), search.declaration().start().line(),
                            search.declaration().start().col(), DELETE_REFERENCES_TIMEOUT_MS)));
                }
                for (Future<Result> pending : results) {
                    Result references = pending.get();
                    complete &= references.status() == Status.COMPLETE;
                    for (Location location : references.locations()) {
                        Path referenced = JavaNavigation.path(location);
                        if (referenced != null && !isInside(referenced, deleted)) {
                            unique.putIfAbsent(UiThreads.locationKey(location), location);
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException failure) {
                log.debug("Busca de usos para a exclusao falhou: {}", AdapterFailures.rootMessage(failure));
                return false;
            } finally {
                pool.shutdownNow();
            }
        } finally {
            temporarilyOpened.forEach(lsp::closeDocument);
        }
        return complete;
    }

    public static List<Range> typeDeclarationRanges(List<DocumentSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<Range> ranges = new ArrayList<>();
        List<DocumentSymbol> pendingSymbols = new ArrayList<>(symbols);
        for (int index = 0; index < pendingSymbols.size(); index++) {
            DocumentSymbol symbol = pendingSymbols.get(index);
            if (!isTypeSymbol(symbol.kind())) {
                continue;
            }
            pendingSymbols.addAll(symbol.children());
            Range range = symbol.selectionRange() == null ? symbol.range() : symbol.selectionRange();
            if (range != null && range.start() != null) {
                ranges.add(range);
            }
        }
        return ranges;
    }

    private static boolean isTypeSymbol(SymbolKind kind) {
        return kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.ENUM || kind == SymbolKind.STRUCT;
    }

    private static List<Path> collectJavaSources(List<Path> targets) {
        List<Path> sources = new ArrayList<>();

        for (Path target : targets) {
            if (Files.isRegularFile(target)) {
                if (JavaProjectConventions.isJava(target)) {
                    sources.add(target);
                }
                continue;
            }
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(target)) {
                walk.filter(Files::isRegularFile)
                        .filter(JavaProjectConventions::isJava)
                        .forEach(sources::add);
            } catch (Exception e) {
                log.debug("Falha ao percorrer {} para a exclusao segura: {}", target, e.getMessage());
            }
        }
        return sources;
    }

    private static boolean containsJavaSource(Path target) {
        return !collectJavaSources(List.of(target)).isEmpty();
    }

    private static boolean isInside(Path path, Set<Path> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String readSource(Path source) {
        try {
            return Files.readString(source);
        } catch (Exception e) {
            log.debug("Falha ao ler {} para a exclusao segura: {}", source, e.getMessage());
            return null;
        }
    }
}
