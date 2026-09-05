package dtm.ide.ui;

import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeNodeRenderer;
import dtm.stools.configs.UiTokens;

import javax.swing.JTree;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class FlatTreeRenderer extends TreeNodeRenderer {

    private boolean paintHighlight;
    private Color highlight;

    FlatTreeRenderer() {
        setOpaque(false);
    }

    @Override
    protected void applyColors(JTree tree, TreeNode<?> node, boolean selected) {
        super.applyColors(tree, node, selected);
        setOpaque(false);
        paintHighlight = selected || hovered;
        highlight = selected
                ? UiTokens.overlay(UiTokens.accent(), 0.28F)
                : UiTokens.overlay(UiTokens.foreground(), 0.08F);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Swing paints tree renderers through a CellRendererPane, where nested
        // renderer components are not always validated before the first frame.
        doLayout();
        if (paintHighlight && highlight != null) {
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(highlight);
                int arc = UiTokens.radius(UiTokens.Radius.SM);
                graphics.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                graphics.dispose();
            }
        }
        super.paintComponent(g);
    }
}
