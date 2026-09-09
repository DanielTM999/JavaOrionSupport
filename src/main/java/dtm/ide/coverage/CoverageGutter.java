package dtm.ide.coverage;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.stools.configs.UiTokens;

import java.awt.Color;
import java.util.Map;

public final class CoverageGutter {

    public static final int STRIPE_WIDTH = 3;
    private static final int STRIPE_ALPHA = 190;

    private CoverageGutter() {
    }

    public static CoverageGutterLayer newLayer() {
        return new CoverageGutterLayer();
    }

    public static void apply(CoverageGutterLayer layer, FileCoverage coverage) {
        if (layer == null) {
            return;
        }
        layer.clearLineColors();
        if (coverage == null || coverage.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, LineStatus> entry : coverage.lines().entrySet()) {
            Color color = colorOf(entry.getValue());
            if (color != null) {
                layer.setLineColor(entry.getKey(), color);
            }
        }
    }

    public static void clear(CoverageGutterLayer layer) {
        if (layer != null) {
            layer.clearLineColors();
        }
    }

    public static CoverageGutterLayer attach(IdeEditorContext context) {
        if (context == null) {
            return null;
        }
        CoverageGutterLayer existing = context.getGutterLayer(CoverageGutterLayer.class);
        if (existing != null) {
            return existing;
        }
        CoverageGutterLayer layer = newLayer();
        return context.addGutterLayer(layer) ? layer : null;
    }

    public static boolean detach(IdeEditorContext context) {
        if (context == null) {
            return false;
        }
        CoverageGutterLayer layer = context.getGutterLayer(CoverageGutterLayer.class);
        if (layer == null) {
            return false;
        }
        clear(layer);
        return context.removeGutterLayer(layer);
    }

    static Color colorOf(LineStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case COVERED -> translucent(UiTokens.success());
            case PARTIAL -> translucent(UiTokens.warning());
            case UNCOVERED -> translucent(UiTokens.danger());
            case IRRELEVANT -> null;
        };
    }

    private static Color translucent(Color color) {
        if (color == null) {
            return null;
        }
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), STRIPE_ALPHA);
    }
}
