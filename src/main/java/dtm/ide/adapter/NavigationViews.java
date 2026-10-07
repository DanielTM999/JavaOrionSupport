package dtm.ide.adapter;

import dtm.ide.JavaEditorRegistry;
import dtm.ide.api.extension.editor.EmbeddedCodeEditorSettings;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.lsp.UsagesPopup;
import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationContext;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationProvider;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenClassifierProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenColorProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenRenderProvider;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

public final class NavigationViews {

    private static final String NAVIGATION_PROGRESS_ID = "javaNavigation";

    private final AdapterHost host;

    public NavigationViews(AdapterHost host) {
        this.host = host;
    }

    public void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                 IdeEditorContext context, Point screen, Kind kind) {
        long session = host.lifecycleTicket();
        host.background().submit(() -> {
            List<UsagesPopup.Item> items = buildUsageItems(locations, currentFile, currentText);
            String header = host.codeLens().countLabel(kind, items.size());
            SwingUtilities.invokeLater(() -> {
                if (session == host.lifecycleTicket()) openUsagesPopup(context, screen, header, items);
            });
        });
    }

    public void openUsagesPopup(IdeEditorContext context, Point screen, String header,
                                 List<UsagesPopup.Item> items) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> openUsagesPopup(context, screen, header, items));
            return;
        }
        if (context == null) {
            javax.swing.JComponent[] content = new javax.swing.JComponent[1];
            UsagesPopup.Host popupHost = new UsagesPopup.Host() {
                public void close() {
                    java.awt.Window window = SwingUtilities.getWindowAncestor(content[0]);
                    if (window != null) window.dispose();
                }
                public void moveTo(int x, int y) {
                    java.awt.Window window = SwingUtilities.getWindowAncestor(content[0]);
                    if (window != null) window.setLocation(x, y);
                }
            };
            content[0] = UsagesPopup.content(header, items, popupHost);
            host.<Boolean>createModernComponentDialogBuilder()
                    .title(header).component(content[0]).show();
            return;
        }
        Object[] handle = new Object[1];
        UsagesPopup.Host popupHost = new UsagesPopup.Host() {
            @Override
            public void close() {
                context.closeEditorWindow(handle[0]);
            }
            @Override
            public void moveTo(int screenX, int screenY) {
                context.moveEditorWindow(handle[0], new Point(screenX, screenY));
            }
        };
        handle[0] = context.openEditorPopup(
                UsagesPopup.content(header, items, popupHost), screen, true, null);
    }

    public List<UsagesPopup.Item> buildUsageItems(List<Location> locations, Path currentFile,
                                                   String currentText) {
        List<UsagesPopup.Item> items = new ArrayList<>();
        Map<Path, List<String>> linesByFile = new HashMap<>();
        Path root = host.projectRoot();
        for (Location location : JavaNavigation.unique(locations)) {
            Path path = JavaNavigation.path(location);
            if (path == null) {
                ClassFileSupport classFiles = host.classFileUris();
                if (classFiles != null && classFiles.isClassFileUri(location.uri())) {
                    String name = classFiles.classFileSourceName(location.uri());
                    items.add(new UsagesPopup.Item(
                            text("navigation.decompiled", "Fonte de dependencia"),
                            name,
                            () -> navigateToLocation(location, null)));
                }
                continue;
            }
            int line = Math.max(0, location.range().start().line());
            String snippet = sourceLine(path, currentFile, currentText, line, linesByFile);
            Path shown = root != null && path.startsWith(root)
                    ? root.relativize(path) : path.getFileName();
            String label = (shown == null ? path.toString() : shown.toString()) + ":" + (line + 1) + ":" + (location.range().start().col() + 1);
            items.add(new UsagesPopup.Item(snippet, label, () -> navigateToLocation(location, path)));
        }
        return List.copyOf(items);
    }

    String sourceLine(Path path, Path currentFile, String currentText, int line,
                              Map<Path, List<String>> cache) {
        List<String> lines = cache.computeIfAbsent(path, file -> {
            if (currentFile != null && currentText != null
                    && file.equals(currentFile.toAbsolutePath().normalize())) return currentText.lines().toList();
            IdeEditorContext open = host.editorContextFor(file);
            if (open != null) {
                String snapshot = UiThreads.onUi(open::getText);
                if (snapshot != null) return snapshot.lines().toList();
            }
            try { return Files.readAllLines(file); }
            catch (IOException unavailable) { return List.of(); }
        });
        return line >= 0 && line < lines.size() ? lines.get(line).strip() : "";
    }

    public void navigateToLocation(Location location, Path path) {
        if (location == null || location.range() == null) {
            return;
        }
        ClassFileSupport classFiles = host.classFileUris();
        if (path == null && classFiles != null && classFiles.isClassFileUri(location.uri())) {
            navigateToClassFile(location);
            return;
        }
        if (path == null) {
            host.setStatusBarText(text("status.navigation.unsupportedTarget",
                    "Java: nao foi possivel abrir este destino"));
            return;
        }
        openAt(path, location.range().start().line(), location.range().start().col());
    }

    public void openAt(Path path, int line, int col) {
        if (path == null) return;
        host.getEditor(path, true, editor -> {
            int[] target = clampPosition(editor.getText(), line, col);
            editor.setCaretPosition(target[0], target[1]);
        });
    }

    public static int[] clampPosition(String text, int line, int col) {
        if (text == null) return new int[]{Math.max(0, line), Math.max(0, col)};
        String[] lines = text.split("\\R", -1);
        int safeLine = Math.max(0, Math.min(line, lines.length - 1));
        int safeCol = Math.max(0, Math.min(col, lines[safeLine].length()));
        return new int[]{safeLine, safeCol};
    }

    void navigateToClassFile(Location location) {
        JavaLanguageServer lsp = host.languageServer();
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (classFiles == null || !lsp.isInteractive()) return;
        String uri = location.uri();
        int line = location.range().start().line();
        int col = location.range().start().col();
        long ticket = host.navigationTicket().incrementAndGet();

        SwingUtilities.invokeLater(() -> host.showProgress(NAVIGATION_PROGRESS_ID,
                text("progress.decompiling", "Java: abrindo fonte da dependencia...")));
        host.background().submit(() -> {
            String source = classFiles.classFileContents(uri);
            SwingUtilities.invokeLater(() -> {
                if (ticket != host.navigationTicket().get()) return;
                host.hideProgress(NAVIGATION_PROGRESS_ID);
                if (source == null || source.isBlank()) {
                    host.setStatusBarText(text("status.decompileFailed",
                            "Java: nao foi possivel obter a fonte da dependencia"));
                    return;
                }
                openClassFileEditor(uri, source, line, col);
            });
        });
    }

    public CodeEditor openClassFileEditor(String uri, String source, int line, int col) {
        ClassFileSupport classFiles = host.classFileUris();
        String fileName = classFiles.classFileSourceName(uri);
        String tabKey = classFiles.classFileTabKey(uri);
        host.closeCenterTab(tabKey);

        CodeEditor editor = host.requestEmbeddedCodeEditor(fileName, source,
                EmbeddedCodeEditorSettings.highlighted());
        if (editor == null) {
            host.setStatusBarText(text("status.decompileFailed",
                    "Java: nao foi possivel abrir a fonte da dependencia"));
            return null;
        }
        editor.setReadOnly(true);
        editor.setSearchEnabled(true);
        editor.setFoldingEnabled(true);
        applyClassFileEditorProviders(editor, uri, fileName);
        editor.setWordClickModifier(InputEvent.CTRL_DOWN_MASK);
        editor.setWordClickHandler(event -> {
            MouseEvent mouse = event.mouseEvent();
            if (mouse == null || mouse.getButton() != MouseEvent.BUTTON1
                    || (mouse.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                return;
            }
            navigateFromClassFile(uri, event.line(), event.col());
        });
        host.openCenterTab(tabKey, fileName + " [dependency]", editor, true);
        editor.setCaretPosition(Math.max(0, line), Math.max(0, col));
        return editor;
    }

    void applyClassFileEditorProviders(CodeEditor editor, String uri, String fileName) {
        applyClassFileEditorProviders(editor, Path.of(fileName), host.editors(),
                context -> classFileHover(uri, context));
    }

    public static void applyClassFileEditorProviders(CodeEditor editor, Path virtual,
                                              JavaEditorRegistry editors,
                                              HoverDocumentationProvider hover) {
        TokenizerCodeEditorProvider tokenizer = editors.tokenizerFor(virtual);
        if (tokenizer != null) {
            editor.addProvider(tokenizer);
            if (editor.getTokenClassifierProvider() == null) {
                editor.addProvider(new DefaultTokenClassifierProvider());
            }
            if (editor.getTokenColorProvider() == null) {
                editor.addProvider(new DefaultTokenColorProvider());
            }
            if (editor.getTokenRenderProvider() == null) {
                editor.addProvider(new DefaultTokenRenderProvider());
            }
            editor.setSyntaxHighlightEnabled(true);
            editor.applySyntaxHighlight();
        }
        Collection<FoldRule> foldRules = editors.foldRulesFor(virtual);
        if (!foldRules.isEmpty()) {
            editor.setFoldRules(foldRules);
        }
        editor.addProvider(hover);
    }

    HoverInfo classFileHover(String uri, HoverDocumentationContext context) {
        if (context == null || host.isDebugPaused()) {
            return null;
        }
        JavaLanguageServer lsp = host.languageServer();
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        return classFiles == null || !lsp.isInteractive()
                ? null : classFiles.hoverAtUri(uri, context.line(), context.col());
    }

    void navigateFromClassFile(String uri, int line, int col) {
        JavaLanguageServer lsp = host.languageServer();
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (classFiles == null || !lsp.isInteractive()) return;
        host.background().submit(() -> {
            List<Location> targets = classFiles.definitionsAtUri(uri, line, col);
            if (targets == null || targets.isEmpty()) {
                SwingUtilities.invokeLater(() -> host.setStatusBarText(
                        text("status.navigation.empty", "Java: nenhum destino encontrado")));
                return;
            }
            Location target = targets.getFirst();
            SwingUtilities.invokeLater(() -> navigateToLocation(target, JavaNavigation.path(target)));
        });
    }
}
