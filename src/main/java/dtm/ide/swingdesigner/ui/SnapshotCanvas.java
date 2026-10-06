package dtm.ide.swingdesigner.ui;

import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.stools.configs.UiTokens;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Consumer;

final class SnapshotCanvas extends JComponent {

    private static final int MARGIN = 24;
    private static final int HANDLE = 6;

    private BufferedImage image;
    private SnapshotNode root;
    private String windowTitle;
    private double zoom = 1.0;
    private String hoveredId;
    private String selectedId;
    private Consumer<SnapshotNode> selectionListener = node -> { };

    SnapshotCanvas() {
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                String id = nodeAt(event.getX(), event.getY()).map(SnapshotNode::id).orElse(null);
                if (!java.util.Objects.equals(id, hoveredId)) {
                    hoveredId = id;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hoveredId = null;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                nodeAt(event.getX(), event.getY()).ifPresent(node -> {
                    select(node.id());
                    selectionListener.accept(node);
                });
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void onSelection(Consumer<SnapshotNode> listener) {
        selectionListener = listener == null ? node -> { } : listener;
    }

    void show(BufferedImage image, SnapshotNode root, String windowTitle) {
        this.image = image;
        this.root = root;
        this.windowTitle = windowTitle;
        if (selectedId != null && (root == null || root.find(selectedId).isEmpty())) {
            selectedId = null;
        }
        hoveredId = null;
        revalidate();
        repaint();
    }

    void select(String id) {
        selectedId = id;
        repaint();
    }

    void setZoom(double zoom) {
        this.zoom = Math.max(0.1, zoom);
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (image == null) {
            return new Dimension(200, 200);
        }
        return new Dimension((int) Math.ceil(image.getWidth() * zoom) + MARGIN * 2,
                (int) Math.ceil(image.getHeight() * zoom) + MARGIN * 2 + titleHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D graphics = (Graphics2D) g.create();
        try {
            graphics.setColor(UiTokens.background());
            graphics.fillRect(0, 0, getWidth(), getHeight());
            if (image == null) {
                return;
            }
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    zoom == 1.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int x = originX();
            int y = originY();
            int width = (int) Math.round(image.getWidth() * zoom);
            int height = (int) Math.round(image.getHeight() * zoom);
            paintTitleBar(graphics, x, width);
            graphics.setColor(UiTokens.overlay(Color.BLACK, 0.18F));
            graphics.fillRect(x + 3, y + 3, width, height);
            graphics.drawImage(image, x, y, width, height, null);
            graphics.setColor(UiTokens.border());
            graphics.drawRect(x - 1, y - 1, width + 1, height + 1);
            paintOutline(graphics, hoveredId, false);
            paintOutline(graphics, selectedId, true);
        } finally {
            graphics.dispose();
        }
    }

    private void paintTitleBar(Graphics2D graphics, int x, int width) {
        if (windowTitle == null) {
            return;
        }
        int height = titleHeight();
        int top = MARGIN;
        graphics.setColor(UiTokens.surface());
        graphics.fillRect(x - 1, top, width + 2, height);
        graphics.setColor(UiTokens.border());
        graphics.drawRect(x - 1, top, width + 1, height);
        graphics.setColor(UiTokens.foreground());
        Font font = UiTokens.font();
        graphics.setFont(font);
        int baseline = top + (height + graphics.getFontMetrics().getAscent()
                - graphics.getFontMetrics().getDescent()) / 2;
        graphics.drawString(windowTitle, x + UiTokens.space(2), baseline);
        int dot = UiTokens.scale(10);
        int gap = UiTokens.scale(6);
        int cx = x + width - UiTokens.space(2) - dot;
        Color[] colors = {new Color(0xE5534B), new Color(0xD29922), new Color(0x57AB5A)};
        for (Color color : colors) {
            graphics.setColor(color);
            graphics.fillOval(cx, top + (height - dot) / 2, dot, dot);
            cx -= dot + gap;
        }
    }

    private void paintOutline(Graphics2D graphics, String id, boolean selected) {
        if (id == null || root == null) {
            return;
        }
        Optional<SnapshotNode> node = root.find(id);
        if (node.isEmpty()) {
            return;
        }
        Rectangle area = toView(node.get().bounds());
        Color accent = UiTokens.accent();
        if (selected) {
            graphics.setColor(UiTokens.overlay(accent, 0.12F));
            graphics.fillRect(area.x, area.y, area.width, area.height);
            graphics.setColor(accent);
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawRect(area.x, area.y, area.width, area.height);
            paintHandles(graphics, area, accent);
            paintLabel(graphics, node.get(), area, accent);
        } else {
            graphics.setColor(UiTokens.overlay(accent, 0.8F));
            graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 3f}, 0f));
            graphics.drawRect(area.x, area.y, area.width, area.height);
        }
    }

    private void paintHandles(Graphics2D graphics, Rectangle area, Color accent) {
        int size = UiTokens.scale(HANDLE);
        int[][] points = {
                {area.x, area.y}, {area.x + area.width / 2, area.y}, {area.x + area.width, area.y},
                {area.x, area.y + area.height / 2}, {area.x + area.width, area.y + area.height / 2},
                {area.x, area.y + area.height}, {area.x + area.width / 2, area.y + area.height},
                {area.x + area.width, area.y + area.height}
        };
        for (int[] point : points) {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(point[0] - size / 2, point[1] - size / 2, size, size);
            graphics.setColor(accent);
            graphics.setStroke(new BasicStroke(1f));
            graphics.drawRect(point[0] - size / 2, point[1] - size / 2, size, size);
        }
    }

    private void paintLabel(Graphics2D graphics, SnapshotNode node, Rectangle area, Color accent) {
        String text = node.label() + "  " + node.bounds().width + "×" + node.bounds().height;
        graphics.setFont(UiTokens.fontSmall());
        int padding = UiTokens.scale(4);
        int textWidth = graphics.getFontMetrics().stringWidth(text);
        int textHeight = graphics.getFontMetrics().getHeight();
        int labelY = area.y - textHeight - padding;
        if (labelY < 0) {
            labelY = area.y + area.height + padding;
        }
        graphics.setColor(accent);
        graphics.fillRoundRect(area.x, labelY, textWidth + padding * 2, textHeight, 6, 6);
        graphics.setColor(Color.WHITE);
        graphics.drawString(text, area.x + padding, labelY + graphics.getFontMetrics().getAscent());
    }

    private Optional<SnapshotNode> nodeAt(int viewX, int viewY) {
        if (root == null || image == null) {
            return Optional.empty();
        }
        int x = (int) Math.floor((viewX - originX()) / zoom);
        int y = (int) Math.floor((viewY - originY()) / zoom);
        return root.deepestAt(x, y);
    }

    private Rectangle toView(Rectangle bounds) {
        return new Rectangle(originX() + (int) Math.round(bounds.x * zoom),
                originY() + (int) Math.round(bounds.y * zoom),
                (int) Math.round(bounds.width * zoom), (int) Math.round(bounds.height * zoom));
    }

    private int originX() {
        int width = image == null ? 0 : (int) Math.round(image.getWidth() * zoom);
        return Math.max(MARGIN, (getWidth() - width) / 2);
    }

    private int originY() {
        return MARGIN + titleHeight();
    }

    private int titleHeight() {
        return windowTitle == null ? 0 : UiTokens.scale(28);
    }
}
