package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public final class JavaDebugValuePopup {

    private static final int WIDTH = 540;
    private static final int WATCH_INTERVAL_MS = 400;
    private static final int GRACE_MS = 450;
    private static final int ANCHOR_SLACK_X = 90;
    private static final int ANCHOR_SLACK_Y = 22;
    private final JavaDebugValueTree tree;
    private final Executor executor;
    private final Timer watchTimer = new Timer(WATCH_INTERVAL_MS, event -> hideIfPointerAway(true));
    private final Timer graceTimer = new Timer(GRACE_MS, event -> hideIfPointerAway(false));
    private final AtomicLong ticket = new AtomicLong();
    private volatile JavaDebugValueTree.ChildrenProvider childrenProvider = reference -> List.of();
    private volatile JWindow window;
    private volatile JLabel header;
    private volatile AWTEventListener outsideClickListener;
    private volatile Point anchor;
    private volatile String shownKey;

    public JavaDebugValuePopup() {
        this(command -> Thread.startVirtualThread(command));
    }

    public JavaDebugValuePopup(Executor executor) {
        this.executor = executor == null
                ? command -> Thread.startVirtualThread(command) : executor;
        this.tree = new JavaDebugValueTree(this.executor);
        graceTimer.setRepeats(false);
    }

    public void bindChildrenProvider(JavaDebugValueTree.ChildrenProvider provider) {
        childrenProvider = provider == null ? reference -> List.of() : provider;
        tree.bindChildrenProvider(childrenProvider);
    }

    public boolean isShowing(String key) {
        JWindow current = window;
        return key != null && key.equals(shownKey) && current != null && current.isVisible();
    }

    public void requestHide() {
        SwingUtilities.invokeLater(() -> {
            if (window != null && window.isVisible() && !pointerInsideWindow()) {
                graceTimer.restart();
            }
        });
    }

    public void show(JavaDebugSnapshot.Variable value, Point location) {
        show(value, location, value == null ? null : value.name());
    }

    public void show(JavaDebugSnapshot.Variable value, Point location, String key) {
        if (value == null) {
            hide();
            return;
        }
        long currentTicket = ticket.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            if (currentTicket == ticket.get()) {
                shownKey = key;
                anchor = location;
            }
            showInitial(value, location, currentTicket);
        });
        if (!value.expandable()) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return childrenProvider.load(value.variablesReference());
            } catch (Exception error) {
                return List.<JavaDebugSnapshot.Variable>of();
            }
        }, executor).thenAccept(values -> SwingUtilities.invokeLater(() ->
                showChildren(value, location, currentTicket, values)));
    }

    public void hide() {
        ticket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideNow);
    }

    private void hideNow() {
        watchTimer.stop();
        graceTimer.stop();
        shownKey = null;
        anchor = null;
        if (window != null) {
            window.setVisible(false);
        }
        uninstallOutsideClickListener();
    }

    private void hideIfPointerAway(boolean anchorKeepsOpen) {
        if (window == null || !window.isVisible()) {
            watchTimer.stop();
            return;
        }
        Point pointer = pointer();
        if (pointer == null || pointerInsideWindow(pointer)
                || (anchorKeepsOpen && nearAnchor(pointer))) {
            return;
        }
        ticket.incrementAndGet();
        hideNow();
    }

    private boolean pointerInsideWindow() {
        return pointerInsideWindow(pointer());
    }

    private boolean pointerInsideWindow(Point pointer) {
        JWindow current = window;
        return pointer != null && current != null && current.isVisible()
                && current.getBounds().contains(pointer);
    }

    private boolean nearAnchor(Point pointer) {
        Point origin = anchor;
        return origin != null && Math.abs(pointer.x - origin.x) <= ANCHOR_SLACK_X
                && Math.abs(pointer.y - origin.y) <= ANCHOR_SLACK_Y;
    }

    private static Point pointer() {
        try {
            java.awt.PointerInfo info = java.awt.MouseInfo.getPointerInfo();
            return info == null ? null : info.getLocation();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void showInitial(JavaDebugSnapshot.Variable value, Point location, long currentTicket) {
        if (currentTicket != ticket.get()) {
            return;
        }
        ensureWindow();
        header.setText(headerHtml(value));
        tree.setVisible(value.expandable());
        if (value.expandable()) {
            tree.setLoading();
        }
        resizeAndPlace(location, value.expandable() ? 104 : 52);
        window.setVisible(true);
        installOutsideClickListener();
        graceTimer.stop();
        watchTimer.restart();
    }

    private void showChildren(JavaDebugSnapshot.Variable value, Point location, long currentTicket,
                              List<JavaDebugSnapshot.Variable> values) {
        if (currentTicket != ticket.get() || window == null || !window.isVisible()) {
            return;
        }
        List<JavaDebugSnapshot.Variable> visible = values == null ? List.of() : values.stream()
                .filter(child -> child != null && !isNoFieldsSentinel(child)).toList();
        tree.setVisible(true);
        tree.setVariables(visible, "This object has no fields");
        int rows = Math.max(1, Math.min(visible.size(), 12));
        resizeAndPlace(location, visible.isEmpty() ? 104 : Math.min(420, 68 + rows * 24));
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        JWindow created = new JWindow();
        created.setAlwaysOnTop(true);
        created.setFocusableWindowState(false);
        try {
            created.setBackground(new Color(0, 0, 0, 0));
        } catch (RuntimeException ignored) {
        }
        RoundedCard card = new RoundedCard();
        card.setLayout(new BorderLayout());
        header = new JLabel();
        header.setFont(JavaDebugTheme.mono().deriveFont(12f));
        header.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, JavaDebugTheme.border()));
        heading.add(header, BorderLayout.CENTER);
        tree.setBorder(BorderFactory.createEmptyBorder(1, 1, 5, 1));
        card.add(heading, BorderLayout.NORTH);
        card.add(tree, BorderLayout.CENTER);
        created.setContentPane(card);
        window = created;
    }

    private void resizeAndPlace(Point location, int height) {
        Point target = location == null ? new Point(120, 120)
                : new Point(location.x + 14, location.y + 20);
        Rectangle screen = screenBounds(target);
        int x = Math.min(target.x, screen.x + screen.width - WIDTH - 10);
        int y = Math.min(target.y, screen.y + screen.height - height - 10);
        window.setBounds(Math.max(screen.x + 10, x), Math.max(screen.y + 10, y), WIDTH, height);
        window.validate();
    }

    private void installOutsideClickListener() {
        if (outsideClickListener != null) {
            return;
        }
        outsideClickListener = event -> {
            if (!(event instanceof MouseEvent mouse)
                    || mouse.getID() != MouseEvent.MOUSE_PRESSED
                    || window == null || !window.isVisible()) {
                return;
            }
            if (mouse.getSource() instanceof java.awt.Component component
                    && SwingUtilities.isDescendingFrom(component, window)) {
                return;
            }
            hide();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(
                outsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    private void uninstallOutsideClickListener() {
        if (outsideClickListener == null) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
        outsideClickListener = null;
    }

    private static Rectangle screenBounds(Point point) {
        try {
            for (java.awt.GraphicsDevice device : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration configuration = device.getDefaultConfiguration();
                if (configuration.getBounds().contains(point)) {
                    return configuration.getBounds();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new Rectangle(0, 0, 1920, 1080);
    }

    private static boolean isNoFieldsSentinel(JavaDebugSnapshot.Variable value) {
        String name = value.name() == null ? "" : value.name().trim().toLowerCase(java.util.Locale.ROOT);
        String content = value.value() == null ? "" : value.value().trim().toLowerCase(java.util.Locale.ROOT);
        return name.equals("class has no fields") || content.equals("class has no fields");
    }

    private static String headerHtml(JavaDebugSnapshot.Variable value) {
        StringBuilder result = new StringBuilder("<html><body style='white-space:nowrap'>");
        result.append("<b style='color:").append(JavaDebugTheme.hex(JavaDebugTheme.name()))
                .append(";'>").append(escape(value.name())).append("</b>");
        if (value.value() != null && !value.value().isBlank()) {
            result.append("<span style='color:").append(JavaDebugTheme.hex(JavaDebugTheme.muted()))
                    .append(";'> = </span><span style='color:")
                    .append(JavaDebugTheme.hex(JavaDebugTheme.valueFor(value.value(), value.type())))
                    .append(";'>").append(escape(value.value())).append("</span>");
        }
        if (value.type() != null && !value.type().isBlank()) {
            result.append("<span style='color:").append(JavaDebugTheme.hex(JavaDebugTheme.muted()))
                    .append(";'>&nbsp;&nbsp;").append(escape(value.type())).append("</span>");
        }
        return result.append("</body></html>").toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class RoundedCard extends JPanel {
        RoundedCard() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, 12, 12);
            copy.setColor(JavaDebugTheme.popup());
            copy.fill(shape);
            copy.setColor(JavaDebugTheme.border());
            copy.setStroke(new BasicStroke(1f));
            copy.draw(shape);
            copy.dispose();
            super.paintComponent(graphics);
        }
    }
}
