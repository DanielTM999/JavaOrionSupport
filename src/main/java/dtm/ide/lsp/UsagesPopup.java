package dtm.ide.lsp;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

public final class UsagesPopup {

    public record Item(String snippet, String location, Runnable onActivate) {
    }

    private static final Color BG = new Color(0x1E1F22);
    private static final Color HEADER_BG = new Color(0x2B2D31);
    private static final Color STRIPE_BG = new Color(0x26282C);
    private static final Color SELECTION_BG = new Color(0x2F4D78);
    private static final Color TEXT = new Color(0xD7D7D7);
    private static final Color MUTED = new Color(0x8A8A8A);
    private static final Color BORDER = new Color(0x3A3D41);
    private static final Font UI_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font MONO_FONT = new Font("JetBrains Mono", Font.PLAIN, 12);

    private static JDialog current;

    private UsagesPopup() {
    }

    public static void show(Window owner, Point screen, String headerText, List<Item> items) {
        List<Item> effective = items == null ? List.of() : items;
        SwingUtilities.invokeLater(() -> doShow(owner, screen, headerText, effective));
    }

    private static void doShow(Window owner, Point screen, String headerText, List<Item> items) {
        if (current != null) {
            current.dispose();
        }
        JDialog dialog = new JDialog(owner);
        current = dialog;
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(BORDER));
        JLabel header = new JLabel("  " + headerText);
        header.setOpaque(true);
        header.setBackground(HEADER_BG);
        header.setForeground(MUTED);
        header.setFont(UI_FONT.deriveFont(Font.BOLD, 11f));
        header.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        installDrag(dialog, header);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);

        DefaultListModel<Item> model = new DefaultListModel<>();
        items.forEach(model::addElement);
        boolean empty = items.isEmpty();
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Pesquisar...");
        search.setBackground(BG);
        search.setForeground(TEXT);
        search.setCaretColor(TEXT);
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }

            private void filter() {
                String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
                model.clear();
                for (Item item : items) {
                    String value = (item.snippet() + " " + item.location())
                            .toLowerCase(java.util.Locale.ROOT);
                    if (query.isEmpty() || value.contains(query)) model.addElement(item);
                }
            }
        });
        if (!empty) {
            top.add(search, BorderLayout.SOUTH);
        }
        root.add(top, BorderLayout.NORTH);
        JList<Item> list = new JList<>(model);
        list.setBackground(BG);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        list.setCellRenderer((ignored, value, index, selected, focused) ->
                renderRow(value, index, selected));
        list.setSelectedIndex(0);

        Runnable activate = () -> {
            Item item = list.getSelectedValue();
            dialog.dispose();
            if (item != null && item.onActivate() != null) {
                item.onActivate().run();
            }
        };
        bind(search, KeyEvent.VK_ENTER, "activate", activate);
        bind(search, KeyEvent.VK_ESCAPE, "close", dialog::dispose);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { selectFirst(); }
            @Override public void removeUpdate(DocumentEvent event) { selectFirst(); }
            @Override public void changedUpdate(DocumentEvent event) { selectFirst(); }
            private void selectFirst() {
                SwingUtilities.invokeLater(() -> list.setSelectedIndex(model.isEmpty() ? -1 : 0));
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
                if (bounds != null && bounds.contains(event.getPoint())) {
                    list.setSelectedIndex(index);
                    activate.run();
                }
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
                if (bounds != null && bounds.contains(event.getPoint())) {
                    list.setSelectedIndex(index);
                }
            }
        });
        bind(list, KeyEvent.VK_ENTER, "activate", activate);
        bind(list, KeyEvent.VK_ESCAPE, "close", dialog::dispose);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        applyThinScrollBar(scroll.getVerticalScrollBar());
        applyThinScrollBar(scroll.getHorizontalScrollBar());
        root.add(scroll, BorderLayout.CENTER);

        dialog.setContentPane(root);
        int rows = Math.min(Math.max(items.size(), 1), 12);
        dialog.setSize(new Dimension(560, 68 + rows * 24));
        position(dialog, owner, screen);
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                dialog.dispose();
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (current == dialog) {
                    current = null;
                }
            }
        });
        dialog.setVisible(true);
        search.requestFocusInWindow();
    }

    private static void bind(JComponent component, int key, String name, Runnable action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static void position(JDialog dialog, Window owner, Point requested) {
        if (requested == null) {
            dialog.setLocationRelativeTo(owner);
            return;
        }
        GraphicsConfiguration configuration = owner == null
                ? dialog.getGraphicsConfiguration() : owner.getGraphicsConfiguration();
        Rectangle screen = configuration == null ? null : configuration.getBounds();
        if (screen == null) {
            dialog.setLocation(requested);
            return;
        }
        int x = Math.max(screen.x, Math.min(requested.x, screen.x + screen.width - dialog.getWidth()));
        int y = Math.max(screen.y, Math.min(requested.y, screen.y + screen.height - dialog.getHeight()));
        dialog.setLocation(x, y);
    }

    private static Component renderRow(Item item, int index, boolean selected) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        row.setBackground(selected ? SELECTION_BG : (index % 2 == 0 ? BG : STRIPE_BG));
        JLabel snippet = new JLabel(item == null ? "" : item.snippet());
        snippet.setFont(MONO_FONT);
        snippet.setForeground(TEXT);
        row.add(snippet, BorderLayout.CENTER);
        JLabel location = new JLabel(item == null ? "" : item.location());
        location.setFont(UI_FONT);
        location.setForeground(MUTED);
        row.add(location, BorderLayout.EAST);
        return row;
    }

    private static void applyThinScrollBar(JScrollBar bar) {
        bar.setUI(new ThinScrollBarUI());
        bar.setPreferredSize(bar.getOrientation() == JScrollBar.HORIZONTAL
                ? new Dimension(0, 8) : new Dimension(8, 0));
        bar.setUnitIncrement(16);
        bar.setOpaque(false);
    }

    private static final class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0x5A5D62);
            trackColor = BG;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private static JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension());
            button.setFocusable(false);
            return button;
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(thumbColor);
            copy.fillRoundRect(bounds.x + 2, bounds.y + 2,
                    Math.max(4, bounds.width - 4), Math.max(4, bounds.height - 4), 6, 6);
            copy.dispose();
        }
    }

    private static void installDrag(JDialog dialog, JLabel handle) {
        Point[] origin = {null};
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                origin[0] = event.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                origin[0] = null;
            }
        });
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (origin[0] != null) {
                    Point location = dialog.getLocation();
                    dialog.setLocation(location.x + event.getX() - origin[0].x,
                            location.y + event.getY() - origin[0].y);
                }
            }
        });
    }
}
