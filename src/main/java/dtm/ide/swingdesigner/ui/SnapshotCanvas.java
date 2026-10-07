package dtm.ide.swingdesigner.ui;

import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.stools.configs.UiTokens;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.TransferHandler;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

final class SnapshotCanvas extends JComponent implements Scrollable {

    interface Hooks {
        boolean editing();

        Optional<DropPolicies.Preview> preview(Point imagePoint, Set<String> excluded, Dimension size);

        boolean accepts(DropPolicies.Preview preview);

        void dropNew(String className, DropPolicies.Preview preview);

        void move(String nodeId, DropPolicies.Preview preview);

        void bounds(String nodeId, Rectangle relative);

        void resize(String nodeId, Dimension size);

        void delete(String nodeId);

        void nudge(String nodeId, int dx, int dy);

        void activate(String nodeId);

        void contextMenu(String nodeId, Component invoker, int x, int y);
    }

    private static final int MARGIN = 24;
    private static final int HANDLE = 6;
    private static final int DRAG_THRESHOLD = 4;
    private static final Hooks NO_HOOKS = new Hooks() {
        @Override
        public boolean editing() {
            return false;
        }

        @Override
        public Optional<DropPolicies.Preview> preview(Point imagePoint, Set<String> excluded, Dimension size) {
            return Optional.empty();
        }

        @Override
        public boolean accepts(DropPolicies.Preview preview) {
            return false;
        }

        @Override
        public void dropNew(String className, DropPolicies.Preview preview) {
        }

        @Override
        public void move(String nodeId, DropPolicies.Preview preview) {
        }

        @Override
        public void bounds(String nodeId, Rectangle relative) {
        }

        @Override
        public void resize(String nodeId, Dimension size) {
        }

        @Override
        public void delete(String nodeId) {
        }

        @Override
        public void nudge(String nodeId, int dx, int dy) {
        }

        @Override
        public void activate(String nodeId) {
        }

        @Override
        public void contextMenu(String nodeId, Component invoker, int x, int y) {
        }
    };

    private BufferedImage image;
    private SnapshotNode root;
    private String windowTitle;
    private double zoom = 1.0;
    private boolean fit = true;
    private String hoveredId;
    private String selectedId;
    private Consumer<SnapshotNode> selectionListener = node -> { };
    private Hooks hooks = NO_HOOKS;

    private DropPolicies.Preview preview;
    private boolean previewAccepted;
    private Rectangle ghost;
    private Point pressPoint;
    private SnapshotNode pressNode;
    private int resizeHandle = -1;
    private boolean moving;

    SnapshotCanvas() {
        setOpaque(true);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                String id = nodeAt(event.getX(), event.getY()).map(SnapshotNode::id).orElse(null);
                if (!java.util.Objects.equals(id, hoveredId)) {
                    hoveredId = id;
                    repaint();
                }
                setCursor(Cursor.getPredefinedCursor(handleAt(event.getPoint()) >= 0
                        ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hoveredId = null;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                if (event.isPopupTrigger()) {
                    popup(event);
                    return;
                }
                if (event.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                int handle = handleAt(event.getPoint());
                if (handle >= 0 && hooks.editing()) {
                    resizeHandle = handle;
                    pressPoint = event.getPoint();
                    pressNode = root == null ? null : root.find(selectedId).orElse(null);
                    return;
                }
                nodeAt(event.getX(), event.getY()).ifPresent(node -> {
                    select(node.id());
                    selectionListener.accept(node);
                    pressPoint = event.getPoint();
                    pressNode = node;
                });
                if (event.getClickCount() == 2 && selectedId != null) {
                    hooks.activate(selectedId);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (pressNode == null || pressPoint == null || !hooks.editing()) {
                    return;
                }
                double scale = scale();
                int dx = (int) Math.round((event.getX() - pressPoint.x) / scale);
                int dy = (int) Math.round((event.getY() - pressPoint.y) / scale);
                if (resizeHandle >= 0) {
                    ghost = resized(pressNode.bounds(), resizeHandle, dx, dy);
                    repaint();
                    return;
                }
                if (pressNode == root) {
                    return;
                }
                if (!moving && Math.abs(event.getX() - pressPoint.x) + Math.abs(event.getY() - pressPoint.y)
                        < DRAG_THRESHOLD) {
                    return;
                }
                moving = true;
                Rectangle bounds = pressNode.boundsCopy();
                bounds.translate(dx, dy);
                ghost = bounds;
                updatePreview(imagePoint(event.getPoint()), subtree(pressNode),
                        new Dimension(bounds.width, bounds.height));
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    popup(event);
                }
                try {
                    if (pressNode == null) {
                        return;
                    }
                    if (resizeHandle >= 0 && ghost != null) {
                        finishResize();
                    } else if (moving) {
                        finishMove();
                    }
                } finally {
                    pressNode = null;
                    pressPoint = null;
                    resizeHandle = -1;
                    moving = false;
                    ghost = null;
                    clearPreview();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setTransferHandler(new PaletteDrop());
        try {
            if (getDropTarget() != null) {
                getDropTarget().addDropTargetListener(new DropTargetAdapter() {
                    @Override
                    public void drop(DropTargetDropEvent event) {
                    }

                    @Override
                    public void dragExit(DropTargetEvent event) {
                        clearPreview();
                    }
                });
            }
        } catch (java.util.TooManyListenersException ignored) {
        }
        bindKey(KeyEvent.VK_DELETE, 0, "delete", () -> {
            if (selectedId != null && hooks.editing()) {
                hooks.delete(selectedId);
            }
        });
        bindKey(KeyEvent.VK_LEFT, 0, "left", () -> nudge(-1, 0));
        bindKey(KeyEvent.VK_RIGHT, 0, "right", () -> nudge(1, 0));
        bindKey(KeyEvent.VK_UP, 0, "up", () -> nudge(0, -1));
        bindKey(KeyEvent.VK_DOWN, 0, "down", () -> nudge(0, 1));
        bindKey(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK, "left10", () -> nudge(-10, 0));
        bindKey(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK, "right10", () -> nudge(10, 0));
        bindKey(KeyEvent.VK_UP, KeyEvent.SHIFT_DOWN_MASK, "up10", () -> nudge(0, -10));
        bindKey(KeyEvent.VK_DOWN, KeyEvent.SHIFT_DOWN_MASK, "down10", () -> nudge(0, 10));
    }

    void onSelection(Consumer<SnapshotNode> listener) {
        selectionListener = listener == null ? node -> { } : listener;
    }

    void setHooks(Hooks hooks) {
        this.hooks = hooks == null ? NO_HOOKS : hooks;
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
        this.fit = false;
        revalidate();
        repaint();
    }

    void setFit() {
        this.fit = true;
        revalidate();
        repaint();
    }

    double scale() {
        if (!fit || image == null) {
            return zoom;
        }
        double availableWidth = Math.max(1, getWidth() - MARGIN * 2);
        double availableHeight = Math.max(1, getHeight() - MARGIN * 2 - titleHeight());
        double fitted = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
        return Math.max(0.1, Math.min(1.0, fitted));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, orientation == javax.swing.SwingConstants.VERTICAL ? visible.height : visible.width);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return fit || getParent() != null && getParent().getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return fit || getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        if (image == null || fit) {
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
                    scale() == 1.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int x = originX();
            int y = originY();
            int width = (int) Math.round(image.getWidth() * scale());
            int height = (int) Math.round(image.getHeight() * scale());
            paintTitleBar(graphics, x, width);
            graphics.setColor(UiTokens.overlay(Color.BLACK, 0.18F));
            graphics.fillRect(x + 3, y + 3, width, height);
            graphics.drawImage(image, x, y, width, height, null);
            graphics.setColor(UiTokens.border());
            graphics.drawRect(x - 1, y - 1, width + 1, height + 1);
            if (preview == null) {
                paintOutline(graphics, hoveredId, false);
            }
            paintOutline(graphics, selectedId, true);
            paintPreview(graphics);
            paintGhost(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private void paintPreview(Graphics2D graphics) {
        if (preview == null || root == null) {
            return;
        }
        Color tint = previewAccepted ? UiTokens.accent() : new Color(0xE5534B);
        root.find(preview.parentNodeId()).ifPresent(container -> {
            Rectangle area = toView(container.bounds());
            graphics.setColor(UiTokens.overlay(tint, 0.9F));
            graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{6f, 4f}, 0f));
            graphics.drawRect(area.x, area.y, area.width, area.height);
        });
        Rectangle indicator = toView(preview.indicator());
        if (preview.line()) {
            graphics.setColor(tint);
            graphics.fillRect(indicator.x - 1, indicator.y - 1, Math.max(3, indicator.width + 2),
                    Math.max(3, indicator.height + 2));
        } else {
            graphics.setColor(UiTokens.overlay(tint, 0.22F));
            graphics.fillRect(indicator.x, indicator.y, indicator.width, indicator.height);
            graphics.setColor(tint);
            graphics.setStroke(new BasicStroke(1.5f));
            graphics.drawRect(indicator.x, indicator.y, indicator.width, indicator.height);
        }
        String hint = previewAccepted ? preview.hint() : "nao editavel";
        if (hint != null) {
            graphics.setFont(UiTokens.fontSmall());
            int padding = UiTokens.scale(4);
            int textWidth = graphics.getFontMetrics().stringWidth(hint);
            int textHeight = graphics.getFontMetrics().getHeight();
            int labelX = indicator.x;
            int labelY = Math.max(0, indicator.y - textHeight - padding);
            graphics.setColor(tint);
            graphics.fillRoundRect(labelX, labelY, textWidth + padding * 2, textHeight, 6, 6);
            graphics.setColor(Color.WHITE);
            graphics.drawString(hint, labelX + padding, labelY + graphics.getFontMetrics().getAscent());
        }
    }

    private void paintGhost(Graphics2D graphics) {
        if (ghost == null) {
            return;
        }
        Rectangle area = toView(ghost);
        graphics.setColor(UiTokens.overlay(UiTokens.accent(), 0.15F));
        graphics.fillRect(area.x, area.y, area.width, area.height);
        graphics.setColor(UiTokens.accent());
        graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{3f, 3f}, 0f));
        graphics.drawRect(area.x, area.y, area.width, area.height);
        if (resizeHandle >= 0) {
            String text = ghost.width + " x " + ghost.height;
            graphics.setFont(UiTokens.fontSmall());
            graphics.drawString(text, area.x + area.width + UiTokens.scale(4), area.y + area.height);
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
            if (windowTitle == null || !node.get().id().equals(root.id())) {
                paintLabel(graphics, node.get(), area, accent);
            }
        } else {
            graphics.setColor(UiTokens.overlay(accent, 0.8F));
            graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 3f}, 0f));
            graphics.drawRect(area.x, area.y, area.width, area.height);
        }
    }

    private void paintHandles(Graphics2D graphics, Rectangle area, Color accent) {
        int size = UiTokens.scale(HANDLE);
        for (Point point : handlePoints(area)) {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(point.x - size / 2, point.y - size / 2, size, size);
            graphics.setColor(accent);
            graphics.setStroke(new BasicStroke(1f));
            graphics.drawRect(point.x - size / 2, point.y - size / 2, size, size);
        }
    }

    private static Point[] handlePoints(Rectangle area) {
        return new Point[]{
                new Point(area.x, area.y), new Point(area.x + area.width / 2, area.y),
                new Point(area.x + area.width, area.y), new Point(area.x, area.y + area.height / 2),
                new Point(area.x + area.width, area.y + area.height / 2), new Point(area.x, area.y + area.height),
                new Point(area.x + area.width / 2, area.y + area.height),
                new Point(area.x + area.width, area.y + area.height)
        };
    }

    private int handleAt(Point point) {
        if (selectedId == null || root == null || !hooks.editing()) {
            return -1;
        }
        Optional<SnapshotNode> node = root.find(selectedId);
        if (node.isEmpty() || node.get() == root) {
            return -1;
        }
        Point[] points = handlePoints(toView(node.get().bounds()));
        int reach = UiTokens.scale(HANDLE);
        for (int i = 0; i < points.length; i++) {
            if (Math.abs(points[i].x - point.x) <= reach && Math.abs(points[i].y - point.y) <= reach) {
                return i;
            }
        }
        return -1;
    }

    private static Rectangle resized(Rectangle start, int handle, int dx, int dy) {
        int left = start.x;
        int top = start.y;
        int right = start.x + start.width;
        int bottom = start.y + start.height;
        if (handle == 0 || handle == 3 || handle == 5) {
            left += dx;
        }
        if (handle == 2 || handle == 4 || handle == 7) {
            right += dx;
        }
        if (handle == 0 || handle == 1 || handle == 2) {
            top += dy;
        }
        if (handle == 5 || handle == 6 || handle == 7) {
            bottom += dy;
        }
        int width = Math.max(4, right - left);
        int height = Math.max(4, bottom - top);
        return new Rectangle(Math.min(left, right - 4), Math.min(top, bottom - 4), width, height);
    }

    private void finishResize() {
        SnapshotNode node = pressNode;
        Optional<SnapshotNode> parent = root.parentOf(node.id());
        if (parent.isPresent() && "null".equals(parent.get().layoutClass())) {
            Rectangle base = parent.get().bounds();
            hooks.bounds(node.id(), new Rectangle(ghost.x - base.x, ghost.y - base.y, ghost.width, ghost.height));
        } else {
            hooks.resize(node.id(), new Dimension(ghost.width, ghost.height));
        }
    }

    private void finishMove() {
        SnapshotNode node = pressNode;
        DropPolicies.Preview target = preview;
        if (target == null || !previewAccepted) {
            return;
        }
        Optional<SnapshotNode> parent = root.parentOf(node.id());
        if (DropPolicies.ABSOLUTE.equals(target.policy()) && parent.isPresent()
                && parent.get().id().equals(target.parentNodeId())) {
            Rectangle base = parent.get().bounds();
            hooks.bounds(node.id(), new Rectangle(ghost.x - base.x, ghost.y - base.y, ghost.width, ghost.height));
            return;
        }
        hooks.move(node.id(), target);
    }

    private void nudge(int dx, int dy) {
        if (selectedId == null || root == null || !hooks.editing()) {
            return;
        }
        Optional<SnapshotNode> parent = root.parentOf(selectedId);
        if (parent.isPresent() && "null".equals(parent.get().layoutClass())) {
            hooks.nudge(selectedId, dx, dy);
        }
    }

    private void popup(MouseEvent event) {
        Optional<SnapshotNode> node = nodeAt(event.getX(), event.getY());
        node.ifPresent(found -> {
            select(found.id());
            selectionListener.accept(found);
            hooks.contextMenu(found.id(), this, event.getX(), event.getY());
        });
    }

    private void updatePreview(Point imagePoint, Set<String> excluded, Dimension size) {
        preview = hooks.preview(imagePoint, excluded, size).orElse(null);
        previewAccepted = preview != null && hooks.accepts(preview);
        repaint();
    }

    private void clearPreview() {
        if (preview != null || ghost != null) {
            preview = null;
            previewAccepted = false;
            repaint();
        }
    }

    private static Set<String> subtree(SnapshotNode node) {
        Set<String> ids = new HashSet<>();
        node.forEach(child -> ids.add(child.id()));
        return ids;
    }

    private void bindKey(int key, int modifiers, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, modifiers), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private void paintLabel(Graphics2D graphics, SnapshotNode node, Rectangle area, Color accent) {
        String text = node.label() + "  " + node.bounds().width + "x" + node.bounds().height;
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
        Point point = imagePoint(new Point(viewX, viewY));
        return root.deepestAt(point.x, point.y);
    }

    private Point imagePoint(Point view) {
        return new Point((int) Math.floor((view.x - originX()) / scale()),
                (int) Math.floor((view.y - originY()) / scale()));
    }

    private Rectangle toView(Rectangle bounds) {
        return new Rectangle(originX() + (int) Math.round(bounds.x * scale()),
                originY() + (int) Math.round(bounds.y * scale()),
                (int) Math.round(bounds.width * scale()), (int) Math.round(bounds.height * scale()));
    }

    private int originX() {
        int width = image == null ? 0 : (int) Math.round(image.getWidth() * scale());
        return Math.max(MARGIN, (getWidth() - width) / 2);
    }

    private int originY() {
        return MARGIN + titleHeight();
    }

    private int titleHeight() {
        return windowTitle == null ? 0 : UiTokens.scale(28);
    }

    private final class PaletteDrop extends TransferHandler {

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor) || !hooks.editing()
                    || PalettePanel.dragging() == null || image == null) {
                return false;
            }
            Point point = support.getDropLocation().getDropPoint();
            updatePreview(imagePoint(point), Set.of(), new Dimension(100, 24));
            support.setDropAction(COPY);
            return previewAccepted;
        }

        @Override
        public boolean importData(TransferSupport support) {
            DropPolicies.Preview target = preview;
            clearPreview();
            if (target == null || !previewAccepted && !hooks.accepts(target)) {
                return false;
            }
            try {
                String transferred = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                String className = PalettePanel.classOf(transferred);
                if (className == null) {
                    return false;
                }
                hooks.dropNew(className, target);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
