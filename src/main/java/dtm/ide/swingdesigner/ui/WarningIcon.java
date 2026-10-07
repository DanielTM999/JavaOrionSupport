package dtm.ide.swingdesigner.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

final class WarningIcon implements Icon {

    static final Color AMBER = new Color(0xD29922);
    static final Color BLUE = new Color(0x4C8DFF);

    private final boolean error;
    private final int size;

    WarningIcon(boolean error) {
        this.error = error;
        this.size = UiTokens.scale(16);
    }

    @Override
    public void paintIcon(Component component, Graphics g, int x, int y) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.translate(x, y);
            float unit = size / 16f;
            if (error) {
                Path2D triangle = new Path2D.Float();
                triangle.moveTo(8 * unit, 1.5f * unit);
                triangle.lineTo(15 * unit, 14 * unit);
                triangle.lineTo(1 * unit, 14 * unit);
                triangle.closePath();
                graphics.setColor(AMBER);
                graphics.fill(triangle);
                graphics.setColor(Color.BLACK);
                graphics.setStroke(new BasicStroke(1.6f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.drawLine(Math.round(8 * unit), Math.round(5.5f * unit), Math.round(8 * unit),
                        Math.round(9.5f * unit));
                int dot = Math.max(2, Math.round(1.8f * unit));
                graphics.fillOval(Math.round(8 * unit) - dot / 2, Math.round(11.5f * unit) - dot / 2, dot, dot);
            } else {
                graphics.setColor(BLUE);
                graphics.fillOval(Math.round(unit), Math.round(unit), Math.round(14 * unit), Math.round(14 * unit));
                graphics.setColor(Color.WHITE);
                graphics.setStroke(new BasicStroke(1.6f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.drawLine(Math.round(8 * unit), Math.round(7 * unit), Math.round(8 * unit),
                        Math.round(11.5f * unit));
                int dot = Math.max(2, Math.round(1.8f * unit));
                graphics.fillOval(Math.round(8 * unit) - dot / 2, Math.round(4.5f * unit) - dot / 2, dot, dot);
            }
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
