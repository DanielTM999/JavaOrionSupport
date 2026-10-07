package dtm.ide.swingdesigner;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.view.IdeEditorView;
import dtm.ide.api.project.editor.view.IdeEditorViewContext;
import dtm.ide.api.project.editor.view.IdeEditorViewMode;
import dtm.ide.api.project.editor.view.IdeEditorViewModesBuilder;
import dtm.ide.api.project.editor.view.IdeEditorViewPlacement;
import dtm.ide.swingdesigner.ui.SwingEditorView;
import dtm.ide.swingdesigner.ui.SwingModeIcon;
import dtm.ide.swingdesigner.ui.SwingViewerPanel;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

@Slf4j
public final class SwingDesignerSupport implements AutoCloseable {

    public static final String SPLIT_MODE_ID = "java.swing.split";
    public static final String DESIGN_MODE_ID = "java.swing.design";
    public static final String VIEW_KEY = "java.swing.viewer";

    private final SwingDesignerWorkspace workspace;
    private final Set<SwingViewerPanel> panels = ConcurrentHashMap.newKeySet();
    private final Set<Path> warming = ConcurrentHashMap.newKeySet();
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "swing-designer-catalog");
        thread.setDaemon(true);
        return thread;
    });

    public SwingDesignerSupport(SwingDesignerEnvironment environment) {
        this.workspace = new SwingDesignerWorkspace(environment);
    }

    public SwingDesignerWorkspace workspace() {
        return workspace;
    }

    public static boolean isJavaSource(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    public void contributeViewModes(IdeEditorViewModesBuilder modes, IdeEditorContext editor) {
        Path file = editor == null ? null : editor.filePath();
        if (!isJavaSource(file)) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        Optional<ModuleSession> session = workspace.sessionFor(normalized);
        if (session.isEmpty()) {
            return;
        }
        Optional<String> className = session.get().classNameOf(normalized);
        if (className.isEmpty()) {
            return;
        }
        warmUp(session.get());
        if (!SwingSourceDetector.isDrawableSource(className.get(), editor.getText(),
                session.get().catalogIfReady())) {
            return;
        }
        ModuleSession owner = session.get();
        String fqn = className.get();
        Function<IdeEditorViewContext, IdeEditorView> factory = context -> {
            SwingViewerPanel panel = new SwingViewerPanel(workspace, owner, normalized, fqn);
            panels.add(panel);
            return new SwingEditorView(panel, panels::remove);
        };
        modes.add(IdeEditorViewMode.builder(SPLIT_MODE_ID)
                        .icon(new SwingModeIcon(SwingModeIcon.Kind.SPLIT))
                        .tooltip("Codigo e Swing")
                        .placement(IdeEditorViewPlacement.SPLIT_RIGHT)
                        .viewKey(VIEW_KEY)
                        .order(-200)
                        .view(factory)
                        .build())
                .add(IdeEditorViewMode.builder(DESIGN_MODE_ID)
                        .icon(new SwingModeIcon(SwingModeIcon.Kind.DESIGN))
                        .tooltip("Swing")
                        .placement(IdeEditorViewPlacement.REPLACE)
                        .viewKey(VIEW_KEY)
                        .order(-100)
                        .view(factory)
                        .build());
    }

    public void onJavaFileSaved(Path file) {
        if (!isJavaSource(file) || panels.isEmpty()) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        List<ModuleSession> owners = workspace.sessionsOwning(normalized);
        for (SwingViewerPanel panel : panels) {
            if (owners.contains(panel.session()) || panel.file().equals(normalized)) {
                panel.onSourceSaved();
            }
        }
    }

    @Override
    public void close() {
        background.shutdownNow();
        for (SwingViewerPanel panel : panels) {
            panel.dispose();
        }
        panels.clear();
        workspace.close();
    }

    private void warmUp(ModuleSession session) {
        if (session.catalogIfReady().isPresent() || !warming.add(session.module().root())) {
            return;
        }
        background.submit(() -> {
            try {
                session.catalog();
            } catch (RuntimeException e) {
                log.debug("Falha ao preparar o catalogo Swing de {}: {}", session.module().name(), e.toString());
            } finally {
                warming.remove(session.module().root());
            }
        });
    }
}
