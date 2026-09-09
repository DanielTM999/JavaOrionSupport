package dtm.ide.ui;

import dtm.ide.test.JavaTest;
import dtm.stools.component.panels.editor.code.gutter.CodeEditorGutter;
import dtm.stools.component.panels.editor.code.gutter.layer.BreakpointLayer;
import dtm.stools.component.panels.editor.code.gutter.layer.GutterLayer;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaTestGutterLayer implements GutterLayer {

    @FunctionalInterface
    public interface ClickHandler {
        void onTestClick(MouseEvent event, JavaTest test);
    }

    private static final int ICON_SIZE = 10;
    private static final int LEFT_INSET = 1;
    private static final int CLICK_TOLERANCE = 4;

    private final Map<Integer, JavaTest> tests = new HashMap<>();
    private final ClickHandler handler;
    private Color color = new Color(0x4CAF50);

    public JavaTestGutterLayer(ClickHandler handler) {
        this.handler = handler;
    }

    public void setColor(Color value) {
        if (value != null) {
            color = value;
        }
    }

    public void setTests(List<JavaTest> discovered) {
        tests.clear();
        if (discovered == null) {
            return;
        }
        for (JavaTest test : discovered) {
            if (test == null || test.line() <= 0) {
                continue;
            }
            tests.putIfAbsent(test.line() - 1, test);
        }
    }

    public boolean isEmpty() {
        return tests.isEmpty();
    }

    public JavaTest testAt(int line) {
        return tests.get(line);
    }

    @Override
    public void paint(Graphics g, CodeEditorGutter gutter, int line, int x, int y,
                      int width, int height) {
        if (!tests.containsKey(line) || hasBreakpoint(gutter, line)) {
            return;
        }
        int size = Math.min(ICON_SIZE, Math.max(4, height - 6));
        int top = y + (height - size) / 2;
        Polygon triangle = new Polygon();
        triangle.addPoint(x + LEFT_INSET, top);
        triangle.addPoint(x + LEFT_INSET, top + size);
        triangle.addPoint(x + LEFT_INSET + size, top + size / 2);

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillPolygon(triangle);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public void onMouseClick(MouseEvent event, int line) {
        JavaTest test = tests.get(line);
        if (test == null || handler == null || event == null) {
            return;
        }
        if (event.getX() <= LEFT_INSET + ICON_SIZE + CLICK_TOLERANCE) {
            handler.onTestClick(event, test);
        }
    }

    @Override
    public void onLinesInserted(int atLine, int count) {
        shift(atLine, count, true);
    }

    @Override
    public void onLinesRemoved(int atLine, int count) {
        shift(atLine, count, false);
    }

    private void shift(int atLine, int count, boolean inserted) {
        Map<Integer, JavaTest> updated = new HashMap<>();
        for (Map.Entry<Integer, JavaTest> entry : tests.entrySet()) {
            int line = entry.getKey();
            if (inserted) {
                updated.put(line >= atLine ? line + count : line, entry.getValue());
            } else if (line < atLine) {
                updated.put(line, entry.getValue());
            } else if (line >= atLine + count) {
                updated.put(line - count, entry.getValue());
            }
        }
        tests.clear();
        tests.putAll(updated);
    }

    private static boolean hasBreakpoint(CodeEditorGutter gutter, int line) {
        if (gutter == null || !gutter.isBreakpointEnabled()) {
            return false;
        }
        BreakpointLayer breakpoints = gutter.getLayer(BreakpointLayer.class);
        return breakpoints != null && breakpoints.hasBreakpoint(line);
    }
}
