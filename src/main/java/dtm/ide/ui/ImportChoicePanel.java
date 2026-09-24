package dtm.ide.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.function.Consumer;

public final class ImportChoicePanel extends JPanel {

    private final JList<String> list;
    private final Consumer<String> onChosen;
    private boolean answered;

    public ImportChoicePanel(String simpleName, List<String> candidates, Consumer<String> onChosen) {
        super(new BorderLayout(0, 8));
        this.onChosen = onChosen;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));

        JLabel header = new JLabel("Escolha a classe para '" + simpleName + "'");
        header.setFont(header.getFont().deriveFont(Font.BOLD));

        list = new JList<>(candidates.toArray(String[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFixedCellHeight(44);
        list.setCellRenderer(new CandidateRenderer());
        Color accent = accentColor();
        list.setSelectionBackground(accent);
        list.setSelectionForeground(readableForeground(accent));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && list.locationToIndex(event.getPoint()) >= 0) {
                    answer(list.getSelectedValue());
                }
            }
        });
        bind(KeyEvent.VK_ENTER, "importChoice.accept", () -> answer(list.getSelectedValue()));
        bind(KeyEvent.VK_ESCAPE, "importChoice.skip", () -> answer(null));

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(borderColor()));

        JLabel hint = new JLabel("Enter importa  ·  Esc ignora");
        hint.setForeground(secondary(hint.getForeground()));

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);
    }

    public void focusList() {
        list.requestFocusInWindow();
    }

    public void closed() {
        if (!answered) {
            answered = true;
            onChosen.accept(null);
        }
    }

    private void answer(String qualifiedName) {
        if (answered) {
            return;
        }
        answered = true;
        onChosen.accept(qualifiedName);
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void bind(int key, String name, Runnable action) {
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(key, 0), name);
        AbstractAction wrapped = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        };
        list.getActionMap().put(name, wrapped);
        getActionMap().put(name, wrapped);
    }

    private static final class CandidateRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel name = new JLabel();
        private final JLabel owner = new JLabel();

        private CandidateRenderer() {
            super(new BorderLayout(0, 1));
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            owner.setFont(owner.getFont().deriveFont(Math.max(10f, owner.getFont().getSize2D() - 1f)));
            add(name, BorderLayout.NORTH);
            add(owner, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                                 int index, boolean selected, boolean focused) {
            String qualified = value == null ? "" : value;
            int dot = qualified.lastIndexOf('.');
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(true);
            name.setText(dot < 0 ? qualified : qualified.substring(dot + 1));
            owner.setText(dot < 0 ? "" : qualified.substring(0, dot));
            name.setForeground(foreground);
            owner.setForeground(secondary(foreground));
            return this;
        }
    }

    private static Color accentColor() {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) accent = UIManager.getColor("Button.default.background");
        return accent == null ? new Color(88, 157, 246) : accent;
    }

    private static Color borderColor() {
        Color color = UIManager.getColor("Separator.foreground");
        return color == null ? new Color(128, 128, 128, 80) : color;
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
}
