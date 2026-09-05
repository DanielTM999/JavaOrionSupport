package dtm.ide.ui;

import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.popup.ModernComponentDialog;
import dtm.stools.component.popup.ModernDialog;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class JavaSourceActionDialogs {

    public enum Kind {
        ACTION("+", new Color(89, 168, 105)),
        CONSTRUCTOR("C", new Color(197, 142, 54)),
        FIELD("F", new Color(132, 113, 201)),
        ACCESSOR("G", new Color(75, 151, 202)),
        METHOD("M", new Color(82, 156, 204)),
        EQUALS_HASH("=", new Color(184, 113, 193)),
        TO_STRING("T", new Color(66, 166, 143)),
        OVERRIDE("O", new Color(224, 139, 61)),
        IMPLEMENT("I", new Color(70, 164, 118)),
        DELEGATE("D", new Color(102, 135, 206));

        private final String glyph;
        private final Color color;

        Kind(String glyph, Color color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    public record Choice<T>(T value, String label, String detail, Kind kind, String shortcut) {
        public Choice(T value, String label, String detail) {
            this(value, label, detail, Kind.ACTION, "");
        }

        public Choice(T value, String label, String detail, Kind kind) {
            this(value, label, detail, kind, "");
        }

        public Choice {
            label = label == null ? "" : label;
            detail = detail == null ? "" : detail;
            kind = kind == null ? Kind.ACTION : kind;
            shortcut = shortcut == null ? "" : shortcut;
        }

        @Override public String toString() {
            return label;
        }
    }

    private JavaSourceActionDialogs() {
    }

    public static <T> T chooseOne(
            ModernComponentDialog.ModernComponentDialogBuilder<Choice<T>> dialog,
            String title, String message, List<Choice<T>> choices) {
        if (choices == null || choices.isEmpty()) return null;
        if (choices.size() == 1) return choices.getFirst().value();

        DefaultListModel<Choice<T>> model = new DefaultListModel<>();
        choices.forEach(model::addElement);
        JList<Choice<T>> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ChoiceRenderer<>(null));
        list.setFixedCellHeight(48);
        list.setSelectedIndex(0);
        styleList(list);
        JPanel content = searchableList(choices, model, list);

        Color accent = accentColor();
        Choice<T> selected = dialog
                .title(title).typeLabel("JAVA").message(message).showIcon(false)
                .accentColor(accent)
                .requestFocusOnLoad(true)
                .component(content).confirmText("OK").cancelText("Cancelar")
                .result(context -> list.getSelectedValue()).show();
        return selected == null ? null : selected.value();
    }

    public static <T> List<T> chooseMany(
            ModernComponentDialog.ModernComponentDialogBuilder<List<T>> dialog,
            String title, String message, List<Choice<T>> choices,
            Predicate<T> initiallySelected) {
        if (choices == null || choices.isEmpty()) return List.of();
        Set<T> selected = new LinkedHashSet<>();
        for (Choice<T> choice : choices) {
            if (initiallySelected != null && initiallySelected.test(choice.value())) {
                selected.add(choice.value());
            }
        }

        DefaultListModel<Choice<T>> model = new DefaultListModel<>();
        choices.forEach(model::addElement);
        JList<Choice<T>> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ChoiceRenderer<>(selected));
        list.setFixedCellHeight(52);
        styleList(list);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index < 0) return;
                toggle(selected, model.get(index).value());
                list.setSelectedIndex(index);
                list.repaint();
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() != KeyEvent.VK_SPACE) return;
                Choice<T> choice = list.getSelectedValue();
                if (choice == null) return;
                toggle(selected, choice.value());
                list.repaint();
                event.consume();
            }
        });
        JPanel content = searchableList(choices, model, list);

        Color accent = accentColor();
        @SuppressWarnings("unchecked")
        List<T> answer = (List<T>) dialog
                .title(title).typeLabel("JAVA").message(message).showIcon(false)
                .accentColor(accent)
                .requestFocusOnLoad(true)
                .component(content).confirmText("Gerar").cancelText("Cancelar")
                .result(context -> new ArrayList<>(selected)).show();
        return answer == null ? null : List.copyOf(answer);
    }

    public static boolean confirm(ModernComponentDialog.ModernComponentDialogBuilder<Boolean> dialog,
                                  String title, String message, String confirmText) {
        Boolean answer = dialog
                .title(title).message(message).type(ModernDialog.Type.QUESTION)
                .accentColor(accentColor())
                .showCancelButton(false)
                .option(confirmText, Boolean.TRUE)
                .cancelOption("Cancelar")
                .show();
        return Boolean.TRUE.equals(answer);
    }

    private static <T> void toggle(Set<T> selected, T value) {
        if (!selected.remove(value)) selected.add(value);
    }

    private static <T> JPanel searchableList(List<Choice<T>> all,
                                              DefaultListModel<Choice<T>> model,
                                              JList<Choice<T>> list) {
        MaskedTextField search = new MaskedTextField();
        search.setPlaceholder("Pesquisar...");
        styleSearchField(search);
        installSearchNavigation(search, list);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }

            private void filter() {
                String query = search.getText().strip().toLowerCase(Locale.ROOT);
                model.clear();
                for (Choice<T> choice : all) {
                    String haystack = (choice.label() + " " + choice.detail()).toLowerCase(Locale.ROOT);
                    if (query.isEmpty() || haystack.contains(query)) model.addElement(choice);
                }
                if (!model.isEmpty()) list.setSelectedIndex(0);
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(borderColor()));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(search, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        int rowsHeight = Math.max(176, Math.min(390, all.size() * 52));
        panel.setPreferredSize(new Dimension(610, rowsHeight + 42));
        return panel;
    }

    private static void installSearchNavigation(MaskedTextField search, JList<?> list) {
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                int size = list.getModel().getSize();
                if (size == 0) return;
                int current = Math.max(0, list.getSelectedIndex());
                int target = switch (event.getKeyCode()) {
                    case KeyEvent.VK_UP -> Math.max(0, current - 1);
                    case KeyEvent.VK_DOWN -> Math.min(size - 1, current + 1);
                    case KeyEvent.VK_HOME -> 0;
                    case KeyEvent.VK_END -> size - 1;
                    case KeyEvent.VK_PAGE_UP -> Math.max(0, current - list.getVisibleRowCount());
                    case KeyEvent.VK_PAGE_DOWN -> Math.min(
                            size - 1, current + list.getVisibleRowCount());
                    default -> -1;
                };
                if (target < 0) return;
                list.setSelectedIndex(target);
                list.ensureIndexIsVisible(target);
                event.consume();
            }
        });
    }

    private static void styleSearchField(MaskedTextField search) {
        Color accent = accentColor();
        Color normal = borderColor();
        Border normalBorder = fieldBorder(normal);
        Border focusBorder = fieldBorder(accent);

        search.setBorder(normalBorder);
        search.setCaretColor(accent);
        search.setSelectionColor(blend(search.getBackground(), accent, 0.55f));
        search.setSelectedTextColor(readableForeground(search.getSelectionColor()));
        search.putClientProperty("JTextField.showClearButton", true);
        search.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                search.setBorder(focusBorder);
            }

            @Override public void focusLost(FocusEvent event) {
                search.setBorder(normalBorder);
            }
        });
    }

    private static void styleList(JList<?> list) {
        Color accent = accentColor();
        list.setSelectionBackground(accent);
        list.setSelectionForeground(readableForeground(accent));
    }

    private static Border fieldBorder(Color color) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 1, true),
                BorderFactory.createEmptyBorder(5, 9, 5, 9));
    }

    private static final class ChoiceRenderer<T> extends JPanel implements ListCellRenderer<Choice<T>> {
        private final JLabel check = new JLabel("", SwingConstants.CENTER);
        private final JLabel icon = new JLabel();
        private final JLabel label = new JLabel();
        private final JLabel detail = new JLabel();
        private final JLabel shortcut = new JLabel();
        private final Set<T> checked;

        private ChoiceRenderer(Set<T> checked) {
            super(new BorderLayout(12, 2));
            this.checked = checked;
            setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 12));
            check.setPreferredSize(new Dimension(20, 20));
            icon.setPreferredSize(new Dimension(24, 24));

            JPanel leading = new JPanel(new BorderLayout(8, 0));
            leading.setOpaque(false);
            if (checked != null) leading.add(check, BorderLayout.WEST);
            leading.add(icon, BorderLayout.CENTER);

            label.setFont(label.getFont().deriveFont(Font.BOLD));
            detail.setFont(detail.getFont().deriveFont(Math.max(10f, detail.getFont().getSize2D() - 1f)));
            shortcut.setFont(shortcut.getFont().deriveFont(Math.max(10f, shortcut.getFont().getSize2D() - 1f)));
            shortcut.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            text.add(label, BorderLayout.NORTH);
            text.add(detail, BorderLayout.SOUTH);
            add(leading, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(shortcut, BorderLayout.EAST);
        }

        @Override public Component getListCellRendererComponent(JList<? extends Choice<T>> list,
                                                                 Choice<T> value, int index,
                                                                 boolean selected, boolean focused) {
            Color bg = selected ? list.getSelectionBackground() : list.getBackground();
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(bg);
            setOpaque(true);
            label.setText(value == null ? "" : value.label());
            detail.setText(value == null ? "" : value.detail());
            detail.setVisible(value != null && !value.detail().isBlank());
            icon.setIcon(value == null ? null : new KindIcon(value.kind()));
            shortcut.setText(value == null ? "" : value.shortcut());
            shortcut.setVisible(value != null && !value.shortcut().isBlank());
            label.setForeground(fg);
            detail.setForeground(secondary(fg));
            shortcut.setForeground(secondary(fg));
            shortcut.setBackground(blend(bg, fg, selected ? 0.10f : 0.07f));
            shortcut.setOpaque(shortcut.isVisible());
            check.setIcon(checked == null || value == null ? null
                    : new SelectionIcon(checked.contains(value.value()), fg));
            return this;
        }
    }

    private static final class KindIcon implements Icon {
        private final Kind kind;

        private KindIcon(Kind kind) {
            this.kind = kind;
        }

        @Override public int getIconWidth() { return 22; }
        @Override public int getIconHeight() { return 22; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(kind.color);
                g.fillRoundRect(x + 1, y + 1, 20, 20, 6, 6);
                g.setColor(Color.WHITE);
                g.setFont(component.getFont().deriveFont(Font.BOLD, 12f));
                int textWidth = g.getFontMetrics().stringWidth(kind.glyph);
                int baseline = y + 1 + (20 - g.getFontMetrics().getHeight()) / 2
                        + g.getFontMetrics().getAscent();
                g.drawString(kind.glyph, x + 1 + (20 - textWidth) / 2, baseline);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class SelectionIcon implements Icon {
        private final boolean selected;
        private final Color foreground;

        private SelectionIcon(boolean selected, Color foreground) {
            this.selected = selected;
            this.foreground = foreground;
        }

        @Override public int getIconWidth() { return 17; }
        @Override public int getIconHeight() { return 17; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = UIManager.getColor("Component.accentColor");
                if (accent == null) accent = new Color(88, 157, 246);
                g.setColor(selected ? accent : secondary(foreground));
                if (selected) {
                    g.fillRoundRect(x + 1, y + 1, 15, 15, 5, 5);
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(x + 4, y + 9, x + 7, y + 12);
                    g.drawLine(x + 7, y + 12, x + 13, y + 5);
                } else {
                    g.setStroke(new BasicStroke(1.2f));
                    g.drawRoundRect(x + 1, y + 1, 14, 14, 5, 5);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static Color borderColor() {
        Color color = UIManager.getColor("Separator.foreground");
        return color == null ? new Color(128, 128, 128, 80) : color;
    }

    private static Color accentColor() {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) accent = UIManager.getColor("Button.default.background");
        if (accent == null) accent = UIManager.getColor("ProgressBar.foreground");
        return accent == null ? new Color(88, 157, 246) : accent;
    }

    private static Color readableForeground(Color background) {
        double luminance = 0.2126 * background.getRed()
                + 0.7152 * background.getGreen()
                + 0.0722 * background.getBlue();
        return luminance < 145 ? Color.WHITE : new Color(24, 24, 27);
    }

    private static Color secondary(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 150);
    }

    private static Color blend(Color base, Color overlay, float ratio) {
        float inverse = 1f - ratio;
        return new Color(
                Math.round(base.getRed() * inverse + overlay.getRed() * ratio),
                Math.round(base.getGreen() * inverse + overlay.getGreen() * ratio),
                Math.round(base.getBlue() * inverse + overlay.getBlue() * ratio));
    }
}
