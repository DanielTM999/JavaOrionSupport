package dtm.ide.swingdesigner.ui;

import dtm.ide.api.project.editor.view.IdeEditorView;
import dtm.ide.api.project.editor.view.IdeEditorViewPlacement;

import javax.swing.JComponent;
import java.util.function.Consumer;

public final class SwingEditorView implements IdeEditorView {

    private final SwingViewerPanel panel;
    private final Consumer<SwingViewerPanel> onDispose;
    private boolean started;

    public SwingEditorView(SwingViewerPanel panel, Consumer<SwingViewerPanel> onDispose) {
        this.panel = panel;
        this.onDispose = onDispose == null ? ignored -> { } : onDispose;
    }

    public SwingViewerPanel panel() {
        return panel;
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void onActivated(IdeEditorViewPlacement placement) {
        panel.setCompact(placement != null && placement.isSplit());
        if (!started) {
            started = true;
            panel.start();
        }
    }

    @Override
    public void dispose() {
        panel.dispose();
        onDispose.accept(panel);
    }
}
