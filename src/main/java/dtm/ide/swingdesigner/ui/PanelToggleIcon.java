package dtm.ide.swingdesigner.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class PanelToggleIcon implements Icon {

    enum Side {
        LEFT,
        RIGHT
    }

    private final Side side;
    private final int size;

    PanelToggleIcon(Side side) {
        this.side = side;
        this.size = UiTokens.scale(16);
    }

    @Override
    public void paintIcon(Component component, Graphics g, int x, int y) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.translate(x, y);
            boolean active = component instanceof AbstractButton button && button.isSelected();
            Color line = component != null && component.isEnabled() ? UiTokens.foreground() : UiTokens.muted();
            float unit = size / 16f;
            int left = Math.round(1.5f * unit);
            int top = Math.round(2.5f * unit);
            int width = Math.round(13 * unit);
            int height = Math.round(11 * unit);
            int pane = Math.round(4.5f * unit);
            int paneX = side == Side.LEFT ? left : left + width - pane;
            graphics.setColor(active ? UiTokens.accent() : UiTokens.overlay(line, 0.35F));
            graphics.fillRect(paneX, top, pane, height);
            graphics.setColor(line);
            graphics.setStroke(new BasicStroke(1.2f * unit));
            int arc = Math.round(2 * unit);
            graphics.drawRoundRect(left, top, width, height, arc, arc);
            int divider = side == Side.LEFT ? left + pane : left + width - pane;
            graphics.drawLine(divider, top, divider, top + height);
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
