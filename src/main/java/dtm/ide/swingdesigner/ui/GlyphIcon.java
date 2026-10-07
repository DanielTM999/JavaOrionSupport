package dtm.ide.swingdesigner.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

final class GlyphIcon implements Icon {

    enum Kind {
        UNDO, REDO
    }

    private final Kind kind;
    private final int size = UiTokens.scale(16);

    GlyphIcon(Kind kind) {
        this.kind = kind;
    }

    @Override
    public void paintIcon(Component component, Graphics g, int x, int y) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(component != null && !component.isEnabled() ? UiTokens.muted() : UiTokens.foreground());
            graphics.translate(x, y);
            if (kind == Kind.REDO) {
                graphics.translate(size, 0);
                graphics.scale(-1, 1);
            }
            float unit = size / 16f;
            graphics.setStroke(new BasicStroke(1.6f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.draw(new Arc2D.Float(4 * unit, 4 * unit, 9 * unit, 8 * unit, 90, -200, Arc2D.OPEN));
            Path2D.Float head = new Path2D.Float();
            head.moveTo(7.5f * unit, 1.5f * unit);
            head.lineTo(4.5f * unit, 4f * unit);
            head.lineTo(7.5f * unit, 6.5f * unit);
            graphics.draw(head);
        } finally {
            graphics.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
