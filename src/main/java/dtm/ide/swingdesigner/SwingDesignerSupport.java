package dtm.ide.swingdesigner;

import dtm.ide.api.extension.screen.ManagedCenterTabHandle;
import dtm.ide.api.extension.screen.ManagedCenterTabListener;
import dtm.ide.api.extension.screen.ManagedCenterTabRequest;
import dtm.ide.swingdesigner.ui.SwingViewerPanel;
import dtm.ide.ui.JavaIcons;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class SwingDesignerSupport implements AutoCloseable {

    public static final String VIEWER_TAB_PREFIX = "swing-viewer:";

    private final SwingDesignerWorkspace workspace;
    private final Function<ManagedCenterTabRequest, ManagedCenterTabHandle> tabs;
    private final Consumer<String> notifier;
    private final Map<Path, OpenViewer> viewers = new ConcurrentHashMap<>();

    public SwingDesignerSupport(SwingDesignerEnvironment environment,
                                Function<ManagedCenterTabRequest, ManagedCenterTabHandle> tabs,
                                Consumer<String> notifier) {
        this.workspace = new SwingDesignerWorkspace(environment);
        this.tabs = tabs;
        this.notifier = notifier == null ? message -> { } : notifier;
    }

    public SwingDesignerWorkspace workspace() {
        return workspace;
    }

    public static boolean isJavaSource(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    public void openViewer(Path file) {
        if (!isJavaSource(file)) {
            notifier.accept("Selecione um arquivo .java para visualizar.");
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        OpenViewer existing = viewers.get(normalized);
        if (existing != null && existing.handle() != null && existing.handle().isOpen()) {
            existing.handle().select();
            existing.panel().refresh(true);
            return;
        }
        Optional<ModuleSession> session = workspace.sessionFor(normalized);
        if (session.isEmpty()) {
            notifier.accept("O arquivo nao pertence a nenhum modulo Java do projeto.");
            return;
        }
        Optional<String> className = session.get().classNameOf(normalized);
        if (className.isEmpty()) {
            notifier.accept("O arquivo nao esta dentro de uma pasta de codigo-fonte do modulo.");
            return;
        }
        Runnable open = () -> {
            SwingViewerPanel panel = new SwingViewerPanel(workspace, session.get(), normalized, className.get());
            ManagedCenterTabListener listener = new ManagedCenterTabListener() {
                @Override
                public void onClosed() {
                    OpenViewer removed = viewers.remove(normalized);
                    if (removed != null) {
                        removed.panel().dispose();
                    }
                }
            };
            ManagedCenterTabHandle handle = tabs.apply(new ManagedCenterTabRequest(
                    VIEWER_TAB_PREFIX + normalized, panelTitle(className.get()), panel, true,
                    JavaIcons.javaClass(JavaIcons.SMALL), listener));
            viewers.put(normalized, new OpenViewer(panel, handle));
            panel.start();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            open.run();
        } else {
            SwingUtilities.invokeLater(open);
        }
    }

    public void onJavaFileSaved(Path file) {
        if (!isJavaSource(file) || viewers.isEmpty()) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        List<ModuleSession> owners = workspace.sessionsOwning(normalized);
        for (OpenViewer viewer : viewers.values()) {
            if (owners.contains(viewer.panel().session()) || viewer.panel().file().equals(normalized)) {
                viewer.panel().onSourceSaved();
            }
        }
    }

    @Override
    public void close() {
        for (OpenViewer viewer : viewers.values()) {
            viewer.panel().dispose();
        }
        viewers.clear();
        workspace.close();
    }

    private static String panelTitle(String className) {
        int dot = className.lastIndexOf('.');
        return (dot < 0 ? className : className.substring(dot + 1)) + " [Viewer]";
    }

    private record OpenViewer(SwingViewerPanel panel, ManagedCenterTabHandle handle) {
    }
}
