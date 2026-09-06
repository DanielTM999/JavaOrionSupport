package dtm.ide.ui;

import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.configs.UiTokens;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Locale;
import java.util.function.Function;

public final class UiSupport {

    private static final String FLAT_STYLE = "FlatLaf.style";
    private static final String THIN_SCROLLBAR = "width: 9; thumbArc: 999; thumbInsets: 2,2,2,2";

    private static final String NO_FOCUS_RING = "focusWidth: 0; innerFocusWidth: 0";

    private UiSupport() {
    }

    static <T> TreeNode<T> treeNode(T data, String id) {
        TreeNode<T> node = new TreeNode<>(data);
        node.setId(id);
        return node;
    }

    static void thinScrollbars(JScrollPane pane) {
        pane.getVerticalScrollBar().putClientProperty(FLAT_STYLE, THIN_SCROLLBAR);
        pane.getHorizontalScrollBar().putClientProperty(FLAT_STYLE, THIN_SCROLLBAR);
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getHorizontalScrollBar().setOpaque(false);
    }

    public static void noFocusRing(JComponent... components) {
        for (JComponent component : components) {
            if (component != null) {
                component.putClientProperty(FLAT_STYLE, NO_FOCUS_RING);
            }
        }
    }

    public static JScrollPane plainScroll(JScrollPane pane) {
        thinScrollbars(pane);
        noFocusRing(pane);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setOpaque(false);
        pane.setOpaque(false);
        return pane;
    }

    static void quietFocus(Component root) {
        if (root instanceof JComponent component) {
            component.putClientProperty(FLAT_STYLE, NO_FOCUS_RING);
        }
        if (root instanceof AbstractButton button) {
            button.setFocusPainted(false);
            button.setFocusable(false);
        }
        if (root instanceof JScrollPane scroll) {
            scroll.setFocusable(false);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                quietFocus(child);
            }
        }
    }

    private static final String HOVER_ROW = "dtm.ide.hoverRow";

    public static void modernTable(JTable table) {
        table.setRowHeight(Math.max(UiTokens.scale(32), table.getRowHeight() + UiTokens.scale(10)));
        table.setShowGrid(false);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setOpaque(false);
        table.setBackground(UiTokens.surface());
        table.setForeground(UiTokens.foreground());
        table.setFont(UiTokens.font());
        table.setSelectionBackground(UiTokens.overlay(UiTokens.accent(), 0.22F));
        table.setSelectionForeground(UiTokens.foreground());
        table.setDefaultRenderer(Object.class, new ModernCellRenderer());
        trackHover(table);
        noFocusRing(table);
        styleHeader(table.getTableHeader());
    }

    public static void modernReadOnlyTable(JTable table) {
        modernTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    private static void trackHover(JTable table) {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent event) {
                setHoverRow(table, -1);
            }
        });
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                setHoverRow(table, table.rowAtPoint(event.getPoint()));
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                setHoverRow(table, table.rowAtPoint(event.getPoint()));
            }
        });
    }

    private static void setHoverRow(JTable table, int row) {
        int previous = hoverRow(table);
        if (previous == row) {
            return;
        }
        table.putClientProperty(HOVER_ROW, row);
        repaintRow(table, previous);
        repaintRow(table, row);
    }

    private static int hoverRow(JTable table) {
        return table.getClientProperty(HOVER_ROW) instanceof Integer row ? row : -1;
    }

    private static void repaintRow(JTable table, int row) {
        if (row >= 0 && row < table.getRowCount()) {
            table.repaint(0, row * table.getRowHeight(), table.getWidth(), table.getRowHeight());
        }
    }

    private static void styleHeader(JTableHeader header) {
        if (header == null) {
            return;
        }
        header.setReorderingAllowed(false);
        header.setFont(UiTokens.fontSmall());
        header.setOpaque(false);
        header.setBackground(UiTokens.background());
        header.setForeground(UiTokens.muted());
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()));
        header.setPreferredSize(new Dimension(0, UiTokens.scale(30)));

        TableCellRenderer original = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, selected, focused, row, column) -> {
            Component component =
                    original.getTableCellRendererComponent(table, value, false, false, row, column);
            if (component instanceof JLabel label) {
                label.setBorder(BorderFactory.createEmptyBorder(
                        0, UiTokens.space(2), 0, UiTokens.space(2)));
                label.setFont(UiTokens.fontSmall());
                label.setForeground(UiTokens.muted());
                label.setOpaque(false);
                label.setHorizontalAlignment(SwingConstants.LEADING);
                label.setText(value == null ? "" : value.toString().toUpperCase(Locale.ROOT));
            }
            return component;
        });
    }

    private static void paintRowHighlight(Graphics graphics, JComponent cell, JTable table,
                                          boolean selected, int row, int column) {
        Color highlight = selected
                ? UiTokens.overlay(UiTokens.accent(), 0.28F)
                : row == hoverRow(table) ? UiTokens.overlay(UiTokens.foreground(), 0.08F) : null;
        if (highlight == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(highlight);
            int arc = UiTokens.radius(UiTokens.Radius.SM);
            int left = column == 0 ? 0 : -arc;
            int right = column == table.getColumnCount() - 1
                    ? cell.getWidth()
                    : cell.getWidth() + arc;
            g2.fillRoundRect(left, 1, right - left, cell.getHeight() - 2, arc, arc);
        } finally {
            g2.dispose();
        }
    }

    private static class ModernCellRenderer extends DefaultTableCellRenderer {

        private transient JTable table;
        private boolean selected;
        private int row;
        private int column;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            this.table = table;
            this.selected = selected;
            this.row = row;
            this.column = column;
            setOpaque(false);
            setForeground(UiTokens.foreground());
            setFont(UiTokens.font());
            setBorder(BorderFactory.createEmptyBorder(
                    0, UiTokens.space(2), 0, UiTokens.space(2)));
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (table != null) {
                paintRowHighlight(graphics, this, table, selected, row, column);
            }
            super.paintComponent(graphics);
        }
    }

    static TableCellRenderer accentColumn(Color accent) {
        return new ModernCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean selected, boolean focused,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                setForeground(accent);
                return this;
            }
        };
    }

    static TableCellRenderer monoColumn() {
        return new ModernCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean selected, boolean focused,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                setFont(UiTokens.fontMono());
                setForeground(UiTokens.muted());
                return this;
            }
        };
    }

    interface CellStyle {
        void apply(JLabel cell, Object value, int row, int column);
    }

    static TableCellRenderer styledColumn(CellStyle style) {
        return new ModernCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean selected, boolean focused,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                style.apply(this, value, row, column);
                return this;
            }
        };
    }

    static TableCellRenderer badgeColumn(Function<Object, BadgeLabel.Tone> tone) {
        return new BadgeCellRenderer(value -> value == null ? "" : value.toString(), tone);
    }

    static TableCellRenderer badgeColumn(Function<Object, String> label,
                                         Function<Object, BadgeLabel.Tone> tone) {
        return new BadgeCellRenderer(label, tone);
    }

    private static final class BadgeCellRenderer extends JPanel implements TableCellRenderer {

        private final transient Function<Object, String> label;
        private final transient Function<Object, BadgeLabel.Tone> tone;
        private final BadgeLabel badge = new BadgeLabel("", BadgeLabel.Tone.NEUTRAL);

        private transient JTable table;
        private boolean selected;
        private int row;
        private int column;

        private BadgeCellRenderer(Function<Object, String> label,
                                  Function<Object, BadgeLabel.Tone> tone) {
            super(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), UiTokens.space(1)));
            this.label = label;
            this.tone = tone;
            setOpaque(false);
            badge.setStyle(BadgeLabel.Style.SOFT);
            badge.setSize(BadgeLabel.Size.SM);
            badge.setShowDot(true);
            add(badge);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int row, int column) {
            this.table = table;
            this.selected = selected;
            this.row = row;
            this.column = column;
            String caption = label.apply(value);
            BadgeLabel.Tone resolved =
                    caption == null || caption.isBlank() ? null : tone.apply(value);
            badge.setVisible(resolved != null);
            if (resolved != null) {
                badge.setTone(resolved);
                badge.setText(caption);
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (table != null) {
                paintRowHighlight(graphics, this, table, selected, row, column);
            }
            super.paintComponent(graphics);
        }
    }
}
