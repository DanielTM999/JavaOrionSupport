package dtm.ide.ui;

import dtm.stools.configs.UiTokens;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

final class JavaDebugTheme {

    private JavaDebugTheme() {
    }

    static Color panel() {
        return UiTokens.background();
    }

    static Color content() {
        return UiTokens.surface();
    }

    static Color header() {
        return UiTokens.surfaceAlt();
    }

    static Color popup() {
        return UiTokens.surface();
    }

    static Color stripe() {
        return UiTokens.surfaceAlt();
    }

    static Color hover() {
        return UiTokens.hover(UiTokens.surfaceAlt());
    }

    static Color selection() {
        return UiTokens.overlay(UiTokens.accent(), 0.27F);
    }

    static Color border() {
        return UiTokens.border();
    }

    static Color text() {
        return UiTokens.foreground();
    }

    static Color muted() {
        return UiTokens.muted();
    }

    static Color accent() {
        return UiTokens.accent();
    }

    static Color name() {
        return UiTokens.primary();
    }

    static Color number() {
        return dark(content()) ? new Color(0xB5CEA8) : new Color(0x098658);
    }

    static Color string() {
        return dark(content()) ? new Color(0xCE9178) : new Color(0xA31515);
    }

    static Color bool() {
        return dark(content()) ? new Color(0x569CD6) : new Color(0x0000FF);
    }

    static Color nil() {
        return dark(content()) ? new Color(0xD16969) : new Color(0xB22222);
    }

    static Color object() {
        return dark(content()) ? new Color(0x9CDCFE) : new Color(0x267F99);
    }

    static Color value() {
        return dark(content()) ? new Color(0xDCDCAA) : new Color(0x795E26);
    }

    static Color valueFor(String value, String type) {
        String actual = value == null ? "" : value.trim();
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (actual.equalsIgnoreCase("null")) {
            return nil();
        }
        if (actual.equals("true") || actual.equals("false")
                || kind.equals("boolean") || kind.equals("bool")) {
            return bool();
        }
        if (!actual.isEmpty() && (actual.charAt(0) == '"' || actual.charAt(0) == '\''
                || kind.equals("string") || kind.equals("char"))) {
            return string();
        }
        if (numeric(actual)) {
            return number();
        }
        if (actual.startsWith("{") || actual.startsWith("[") || kind.contains("[")
                || kind.contains("list") || kind.contains("map") || kind.contains("collection")) {
            return object();
        }
        return value();
    }

    static Font ui() {
        return UiTokens.font();
    }

    static Font mono() {
        return UiTokens.fontMono();
    }

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static boolean numeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isDigit(current)) {
                digit = true;
            } else if (".-+eExXfFdDlL".indexOf(current) < 0
                    && !(current >= 'a' && current <= 'f')
                    && !(current >= 'A' && current <= 'F')) {
                return false;
            }
        }
        return digit;
    }

    private static boolean dark(Color color) {
        double luminance = (0.299 * color.getRed() + 0.587 * color.getGreen()
                + 0.114 * color.getBlue()) / 255.0;
        return luminance < 0.5;
    }

}
