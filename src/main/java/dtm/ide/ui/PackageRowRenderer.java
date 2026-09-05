package dtm.ide.ui;

import dtm.ide.deps.DependencyCoordinate;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

final class PackageRowRenderer extends JPanel implements ListCellRenderer<PackageRowRenderer.Row> {

    private static final String HOVER_ROW = "dtm.ide.hoverRow";

    record Row(DependencyCoordinate coordinate, String title, String meta,
               String badge, BadgeLabel.Tone tone) {
    }

    private final JLabel avatar = new JLabel();
    private final JLabel title = new JLabel();
    private final JLabel meta = new JLabel();
    private final BadgeLabel badge = new BadgeLabel("", BadgeLabel.Tone.NEUTRAL);

    private boolean selected;
    private boolean hovered;

    private PackageRowRenderer() {
        super(new BorderLayout(UiTokens.space(3), 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), UiTokens.space(2),
                UiTokens.space(2), UiTokens.space(2)));

        avatar.setVerticalAlignment(SwingConstants.CENTER);
        avatar.setPreferredSize(new Dimension(UiTokens.scale(32), UiTokens.scale(32)));

        title.setFont(UiTokens.fontBold());
        meta.setFont(UiTokens.fontSmall());

        badge.setStyle(BadgeLabel.Style.SOFT);
        badge.setSize(BadgeLabel.Size.SM);
        badge.setShowDot(true);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(leftAligned(title));
        text.add(Box.createVerticalStrut(Math.max(2, UiTokens.space(1) / 2)));
        text.add(leftAligned(meta));

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(text, BorderLayout.CENTER);

        JPanel trailing = new JPanel(new GridBagLayout());
        trailing.setOpaque(false);
        trailing.add(badge);

        add(avatar, BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
        add(trailing, BorderLayout.EAST);
    }

    static void install(JList<Row> list) {
        list.setCellRenderer(new PackageRowRenderer());
        list.setFixedCellHeight(UiTokens.scale(58));
        list.setOpaque(false);
        list.setBorder(BorderFactory.createEmptyBorder());
        trackHover(list);
    }

    private static void trackHover(JList<Row> list) {
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent event) {
                setHoverRow(list, -1);
            }
        });
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                setHoverRow(list, rowAt(list, event.getPoint()));
            }
        });
    }

    private static int rowAt(JList<Row> list, java.awt.Point point) {
        int index = list.locationToIndex(point);
        if (index < 0) {
            return -1;
        }
        java.awt.Rectangle bounds = list.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }

    private static void setHoverRow(JList<Row> list, int index) {
        int previous = hoverRow(list);
        if (previous == index) {
            return;
        }
        list.putClientProperty(HOVER_ROW, index);
        repaintRow(list, previous);
        repaintRow(list, index);
    }

    private static int hoverRow(JList<Row> list) {
        return list.getClientProperty(HOVER_ROW) instanceof Integer row ? row : -1;
    }

    private static void repaintRow(JList<Row> list, int index) {
        if (index >= 0 && index < list.getModel().getSize()) {
            java.awt.Rectangle bounds = list.getCellBounds(index, index);
            if (bounds != null) {
                list.repaint(bounds);
            }
        }
    }

    private static JLabel leftAligned(JLabel label) {
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Row> list, Row value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        this.selected = isSelected;
        this.hovered = list.getClientProperty(HOVER_ROW) instanceof Integer row && row == index;

        avatar.setIcon(value == null ? null : CoordinateAvatar.of(
                value.coordinate().key(), value.coordinate().artifactId(), UiTokens.scale(32)));
        title.setText(value == null ? "" : value.title());
        title.setForeground(UiTokens.foreground());
        meta.setText(value == null ? "" : value.meta());
        meta.setForeground(UiTokens.muted());

        String label = value == null ? "" : value.badge();
        badge.setVisible(label != null && !label.isBlank());
        if (badge.isVisible()) {
            badge.setText(label);
            badge.setTone(value.tone() == null ? BadgeLabel.Tone.NEUTRAL : value.tone());
        }
        return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Color highlight = selected
                ? UiTokens.overlay(UiTokens.accent(), 0.28F)
                : hovered ? UiTokens.overlay(UiTokens.foreground(), 0.08F) : null;
        if (highlight != null) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(highlight);
                int arc = UiTokens.radius(UiTokens.Radius.MD);
                g2.fillRoundRect(0, 1, getWidth(), getHeight() - 2, arc, arc);
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(graphics);
    }
}
