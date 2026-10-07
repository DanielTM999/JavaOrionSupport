package dtm.ide.swingdesigner.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public final class SwingModeIcon implements Icon {

    public enum Kind {
        DESIGN,
        SPLIT
    }

    private final Kind kind;
    private final int size;

    public SwingModeIcon(Kind kind) {
        this.kind = kind;
        this.size = UiTokens.scale(16);
    }

    @Override
    public void paintIcon(Component component, Graphics g, int x, int y) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = component != null && component.isEnabled() ? UiTokens.foreground() : UiTokens.muted();
            graphics.setColor(color);
            float unit = size / 16f;
            graphics.setStroke(new BasicStroke(1.2f * unit));
            graphics.translate(x, y);
            int left = Math.round(1.5f * unit);
            int top = Math.round(2.5f * unit);
            int width = Math.round(13 * unit);
            int height = Math.round(11 * unit);
            int bar = Math.round(3 * unit);
            if (kind == Kind.SPLIT) {
                int half = width / 2;
                for (int line = 0; line < 3; line++) {
                    int lineY = top + Math.round((2 + line * 3) * unit);
                    graphics.drawLine(left, lineY, left + half - Math.round(2 * unit), lineY);
                }
                int frameLeft = left + half;
                paintWindow(graphics, frameLeft, top, width - half, height, bar, unit);
            } else {
                paintWindow(graphics, left, top, width, height, bar, unit);
                graphics.fillRect(left + Math.round(2 * unit), top + bar + Math.round(2 * unit),
                        Math.round(5 * unit), Math.round(2 * unit));
                graphics.drawRect(left + Math.round(2 * unit), top + bar + Math.round(5 * unit),
                        width - Math.round(4 * unit), Math.round(2 * unit));
            }
        } finally {
            graphics.dispose();
        }
    }

    private static void paintWindow(Graphics2D graphics, int x, int y, int width, int height, int bar, float unit) {
        int arc = Math.round(2 * unit);
        graphics.drawRoundRect(x, y, width, height, arc, arc);
        graphics.drawLine(x, y + bar, x + width, y + bar);
        int dot = Math.max(1, Math.round(unit));
        graphics.fillOval(x + Math.round(1.5f * unit), y + Math.round(unit), dot + 1, dot + 1);
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
