package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;
import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

final class JavaDebugValueIcon implements Icon {

    private static final int SIZE = 14;
    private final String glyph;
    private final Color color;

    private JavaDebugValueIcon(String glyph, Color color) {
        this.glyph = glyph;
        this.color = color;
    }

    static Icon scope() {
        return new JavaDebugValueIcon("L", JavaDebugTheme.accent());
    }

    static Icon forValue(JavaDebugSnapshot.Variable value) {
        String actual = value == null || value.value() == null ? "" : value.value().trim();
        String type = value == null || value.type() == null
                ? "" : value.type().toLowerCase(Locale.ROOT);
        if (type.contains("[") || actual.startsWith("[") || type.contains("list")
                || type.contains("map") || type.contains("collection")) {
            return new JavaDebugValueIcon("A", JavaDebugTheme.object());
        }
        if (value != null && value.expandable()) {
            return new JavaDebugValueIcon("O", JavaDebugTheme.object());
        }
        if (actual.equalsIgnoreCase("null")) {
            return new JavaDebugValueIcon("∅", JavaDebugTheme.nil());
        }
        if (actual.equals("true") || actual.equals("false") || type.equals("boolean")) {
            return new JavaDebugValueIcon("●", JavaDebugTheme.bool());
        }
        if (!actual.isEmpty() && (actual.charAt(0) == '"' || type.equals("string")
                || type.equals("char"))) {
            return new JavaDebugValueIcon("S", JavaDebugTheme.string());
        }
        return new JavaDebugValueIcon("#", JavaDebugTheme.number());
    }

    @Override
    public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                x + 0.5f, y + 0.5f, SIZE - 1f, SIZE - 1f, 5f, 5f);
        copy.setColor(UiTokens.overlay(color, 0.22F));
        copy.fill(shape);
        copy.setColor(color);
        copy.setFont(JavaDebugTheme.mono().deriveFont(Font.BOLD, glyph.length() > 1 ? 8f : 10f));
        int width = copy.getFontMetrics().stringWidth(glyph);
        int ascent = copy.getFontMetrics().getAscent();
        int height = copy.getFontMetrics().getHeight();
        copy.drawString(glyph, x + (SIZE - width) / 2f, y + ascent + (SIZE - height) / 2f);
        copy.dispose();
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
