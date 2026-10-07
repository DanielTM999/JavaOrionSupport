package dtm.ide.swingdesigner.ui;

import dtm.ide.swingdesigner.catalog.EventDescriptor;
import dtm.ide.ui.UiSupport;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class EventsPanel extends JPanel {

    interface Actions {
        Optional<String> handlerOf(EventDescriptor event, EventDescriptor.EventMethod method);

        String suggest(EventDescriptor.EventMethod method);

        void add(EventDescriptor event, EventDescriptor.EventMethod method, String handler);

        void remove(EventDescriptor event, EventDescriptor.EventMethod method);

        void open(String handler);
    }

    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        for (String name : List.of("ActionListener", "ItemListener", "ChangeListener", "ListSelectionListener",
                "CaretListener", "DocumentListener", "TreeSelectionListener", "TableModelListener")) {
            CATEGORIES.put(name, "Acao");
        }
        for (String name : List.of("MouseListener", "MouseMotionListener", "MouseWheelListener")) {
            CATEGORIES.put(name, "Mouse");
        }
        CATEGORIES.put("KeyListener", "Teclado");
        CATEGORIES.put("FocusListener", "Foco");
        for (String name : List.of("WindowListener", "WindowFocusListener", "WindowStateListener")) {
            CATEGORIES.put(name, "Janela");
        }
        for (String name : List.of("ComponentListener", "ContainerListener", "HierarchyListener",
                "HierarchyBoundsListener", "AncestorListener")) {
            CATEGORIES.put(name, "Componente");
        }
        for (String name : List.of("PropertyChangeListener", "VetoableChangeListener")) {
            CATEGORIES.put(name, "Propriedade");
        }
    }

    private static final List<String> ORDER = List.of("Acao", "Mouse", "Teclado", "Foco", "Janela", "Componente",
            "Propriedade", "Outros");

    private final JPanel rows = new JPanel();
    private final JLabel note = new JLabel(" ");

    EventsPanel() {
        super(new BorderLayout());
        setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(rows, BorderLayout.NORTH);
        note.setFont(UiTokens.fontSmall());
        note.setForeground(UiTokens.muted());
        note.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2), UiTokens.space(1),
                UiTokens.space(2)));
        add(note, BorderLayout.NORTH);
        JScrollPane scroll = UiSupport.plainScroll(new JScrollPane(holder));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    void clear(String message) {
        rows.removeAll();
        note.setText(message == null ? " " : message);
        rows.revalidate();
        rows.repaint();
    }

    void show(List<EventDescriptor> events, boolean editable, String readOnlyReason, Actions actions) {
        rows.removeAll();
        note.setText(editable ? "Duplo clique ou Enter cria o handler; clique no nome para abrir"
                : "Somente leitura: " + (readOnlyReason == null ? "codigo indisponivel" : readOnlyReason));
        Map<String, List<EventDescriptor>> groups = new LinkedHashMap<>();
        groups.put("Especificos", new ArrayList<>());
        for (String category : ORDER) {
            groups.put(category, new ArrayList<>());
        }
        for (EventDescriptor event : events) {
            if (event.isHidden() || event.methods().isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(categoryOf(event), key -> new ArrayList<>()).add(event);
        }
        groups.forEach((category, list) -> {
            if (list.isEmpty()) {
                return;
            }
            rows.add(header(category.equals("Especificos") ? "Especificos do componente" : category));
            for (EventDescriptor event : list) {
                rows.add(listenerLabel(event));
                for (EventDescriptor.EventMethod method : event.methods()) {
                    rows.add(row(event, method, editable, actions));
                }
            }
        });
        if (rows.getComponentCount() == 0) {
            note.setText("Este componente nao declara eventos");
        }
        rows.revalidate();
        rows.repaint();
    }

    private static String categoryOf(EventDescriptor event) {
        if (event.category() != null) {
            return event.category();
        }
        String type = event.listenerType();
        if (!type.startsWith("java.") && !type.startsWith("javax.")) {
            return "Especificos";
        }
        return CATEGORIES.getOrDefault(event.simpleListenerName(), "Outros");
    }

    private JPanel header(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.fontBold());
        label.setForeground(UiTokens.muted());
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(UiTokens.overlay(UiTokens.foreground(), 0.04F));
        panel.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(4), UiTokens.space(2), UiTokens.scale(4),
                UiTokens.space(2)));
        panel.add(label, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private JPanel listenerLabel(EventDescriptor event) {
        JLabel label = new JLabel(event.simpleListenerName());
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        label.setToolTipText(event.listenerType() + " via " + event.addMethod() + "(...)");
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(4), UiTokens.space(2), 0, UiTokens.space(2)));
        panel.add(label, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private JPanel row(EventDescriptor event, EventDescriptor.EventMethod method, boolean editable,
                       Actions actions) {
        JPanel row = new JPanel(new BorderLayout(UiTokens.space(1), 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(2), UiTokens.space(3), UiTokens.scale(2),
                UiTokens.space(2)));
        JLabel name = new JLabel(method.name());
        name.setFont(UiTokens.font());
        name.setToolTipText(signature(method));
        name.setPreferredSize(new Dimension(UiTokens.scale(150), name.getPreferredSize().height));
        row.add(name, BorderLayout.WEST);
        Optional<String> handler = actions.handlerOf(event, method);
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.scale(2), 0));
        actionsPanel.setOpaque(false);
        if (handler.isPresent()) {
            JButton link = link(handler.get());
            link.setToolTipText("Abrir " + handler.get());
            link.addActionListener(click -> actions.open(handler.get()));
            row.add(link, BorderLayout.CENTER);
            if (editable) {
                JButton remove = small("x", "Remover a ligacao (o metodo continua no codigo)");
                remove.addActionListener(click -> actions.remove(event, method));
                actionsPanel.add(remove);
            }
        } else {
            JTextField field = new JTextField();
            field.setFont(UiTokens.font());
            field.setEnabled(editable);
            String suggestion = editable ? actions.suggest(method) : "";
            field.putClientProperty("JTextField.placeholderText", suggestion);
            Runnable create = () -> {
                String typed = field.getText().trim();
                actions.add(event, method, typed.isEmpty() ? suggestion : typed);
            };
            field.addActionListener(click -> create.run());
            field.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent click) {
                    if (click.getClickCount() == 2 && editable) {
                        create.run();
                    }
                }
            });
            row.add(field, BorderLayout.CENTER);
            if (editable) {
                JButton add = small("+", "Criar o handler e ligar o evento");
                add.addActionListener(click -> create.run());
                actionsPanel.add(add);
            }
        }
        row.add(actionsPanel, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static String signature(EventDescriptor.EventMethod method) {
        List<String> parameters = new ArrayList<>();
        for (String type : method.parameterTypes()) {
            String clean = type.replace('$', '.');
            parameters.add(clean.substring(clean.lastIndexOf('.') + 1));
        }
        return method.returnType() + " " + method.name() + "(" + String.join(", ", parameters) + ")";
    }

    private static JButton small(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setMargin(new java.awt.Insets(0, UiTokens.scale(4), 0, UiTokens.scale(4)));
        return button;
    }

    private static JButton link(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setForeground(linkColor());
        button.setFont(UiTokens.font());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static Color linkColor() {
        Color accent = UiTokens.accent();
        if (!UiTokens.isDarkTheme()) {
            return accent;
        }
        float mix = 0.45f;
        return new Color(Math.round(accent.getRed() + (255 - accent.getRed()) * mix),
                Math.round(accent.getGreen() + (255 - accent.getGreen()) * mix),
                Math.round(accent.getBlue() + (255 - accent.getBlue()) * mix));
    }
}
