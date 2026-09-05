package dtm.ide.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class CoordinateAvatar {

    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private CoordinateAvatar() {
    }

    static Icon of(String key, String name, int size) {
        String letter = initial(name);
        String cacheKey = key + "|" + size + "|" + (UiTokens.isDarkTheme() ? "dark" : "light");
        return CACHE.computeIfAbsent(cacheKey, ignored -> paint(letter, hue(key), size));
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static float hue(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return (((hash % 360) + 360) % 360) / 360F;
    }

    private static Icon paint(String letter, float hue, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color fill = UiTokens.isDarkTheme()
                    ? Color.getHSBColor(hue, 0.42F, 0.58F)
                    : Color.getHSBColor(hue, 0.50F, 0.72F);
            g2.setColor(fill);
            int arc = UiTokens.radius(UiTokens.Radius.SM);
            g2.fillRoundRect(0, 0, size, size, arc, arc);

            g2.setColor(UiTokens.onColor(fill));
            g2.setFont(UiTokens.fontBold().deriveFont(Font.BOLD, size * 0.5F));
            FontMetrics metrics = g2.getFontMetrics();
            int x = (size - metrics.stringWidth(letter)) / 2;
            int baseline = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(letter, x, baseline);
        } finally {
            g2.dispose();
        }
        return new ImageIcon(image);
    }
}
