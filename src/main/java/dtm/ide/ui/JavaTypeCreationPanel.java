package dtm.ide.ui;

import dtm.stools.component.panels.accordion.SectionPanel;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public final class JavaTypeCreationPanel extends JPanel {

    public record TypeCandidate(String qualifiedName, boolean isInterface) {

        public String simpleName() {
            int dot = qualifiedName.lastIndexOf('.');
            return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
        }

        public String packageName() {
            int dot = qualifiedName.lastIndexOf('.');
            return dot < 0 ? "" : qualifiedName.substring(0, dot);
        }
    }

    public record Choice(String name, String superclass, List<String> interfaces) {
    }

    private final boolean allowSuperclass;
    private final Function<String, List<TypeCandidate>> search;
    private final Executor background;
    private final JTextField name = new JTextField();
    private final JLabel error = new JLabel(" ");
    private final JTextField query = new JTextField();
    private final DefaultListModel<TypeCandidate> results = new DefaultListModel<>();
    private final JList<TypeCandidate> resultList = new JList<>(results);
    private final JLabel searchStatus = new JLabel(" ");
    private final JLabel superclassLabel = new JLabel();
    private final DefaultListModel<String> interfaces = new DefaultListModel<>();
    private final JList<String> interfaceList = new JList<>(interfaces);
    private final Timer debounce;
    private final AtomicInteger generation = new AtomicInteger();
    private String superclass = "";

    public JavaTypeCreationPanel(boolean allowSuperclass, String interfacesLabel,
                                 Function<String, List<TypeCandidate>> search, Executor background,
                                 Consumer<Choice> onCreate) {
        super(new BorderLayout());
        this.allowSuperclass = allowSuperclass;
        this.search = search;
        this.background = background;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));

        debounce = new Timer(250, event -> runSearch());
        debounce.setRepeats(false);

        SectionPanel configs = new SectionPanel("Configuracoes", configContent(interfacesLabel));
        configs.setSubtitle(allowSuperclass ? "heranca e interfaces" : "interfaces");
        configs.setAnimated(false);
        configs.setExpanded(false, false);
        configs.setAlignmentX(Component.LEFT_ALIGNMENT);
        configs.addPropertyChangeListener("expanded", event -> {
            SwingUtilities.invokeLater(this::fitWindowHeight);
            if (Boolean.TRUE.equals(event.getNewValue())) {
                query.requestFocusInWindow();
            }
        });

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(row("Nome do tipo", name));
        fields.add(Box.createVerticalStrut(14));
        fields.add(configs);
        fields.add(Box.createVerticalStrut(8));
        error.setForeground(UiTokens.danger());
        error.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(error);
        add(fields, BorderLayout.NORTH);

        JButton cancel = PillButtons.ghost("Cancelar", null);
        cancel.addActionListener(event -> close());
        JButton create = PillButtons.primary("Criar", JavaIcons.create(JavaIcons.SMALL));
        Runnable submit = () -> {
            String typeName = name.getText().trim();
            if (typeName.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
                typeName = typeName.substring(0, typeName.length() - 5);
            }
            if (!typeName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                error.setText("Informe um nome de tipo Java valido.");
                name.requestFocusInWindow();
                return;
            }
            onCreate.accept(new Choice(typeName, superclass, Collections.list(interfaces.elements())));
            close();
        };
        create.addActionListener(event -> submit.run());
        name.addActionListener(event -> submit.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(create);
        add(buttons, BorderLayout.SOUTH);
    }

    public void focusName() {
        name.requestFocusInWindow();
    }

    private JComponent configContent(String interfacesLabel) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                debounce.restart();
            }
        });
        query.addActionListener(event -> pickSelected());
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "focusResults");
        query.getActionMap().put("focusResults", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!results.isEmpty()) {
                    resultList.requestFocusInWindow();
                    resultList.setSelectedIndex(Math.max(0, resultList.getSelectedIndex()));
                }
            }
        });
        content.add(row(allowSuperclass ? "Buscar superclasse ou interface" : "Buscar interface", query));
        content.add(Box.createVerticalStrut(6));

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setVisibleRowCount(6);
        resultList.setCellRenderer((list, value, index, selected, focus) -> candidateLabel(list, value, selected));
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    pickSelected();
                }
            }
        });
        resultList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pick");
        resultList.getActionMap().put("pick", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                pickSelected();
            }
        });
        JScrollPane resultScroll = new JScrollPane(resultList);
        resultScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(resultScroll);

        searchStatus.setFont(UiTokens.fontSmall());
        searchStatus.setForeground(UiTokens.muted());
        searchStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(searchStatus);
        content.add(Box.createVerticalStrut(10));

        if (allowSuperclass) {
            JButton clear = PillButtons.ghost("Limpar", null);
            clear.addActionListener(event -> setSuperclass(""));
            setSuperclass("");
            content.add(selectionRow("Superclasse", superclassLabel, clear));
            content.add(Box.createVerticalStrut(10));
        }

        interfaceList.setVisibleRowCount(3);
        interfaceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        interfaceList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        interfaceList.getActionMap().put("remove", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                removeSelectedInterfaces();
            }
        });
        JButton remove = PillButtons.ghost("Remover", null);
        remove.addActionListener(event -> removeSelectedInterfaces());
        content.add(selectionRow(interfacesLabel, new JScrollPane(interfaceList), remove));
        return content;
    }

    private Component candidateLabel(JList<?> list, TypeCandidate value, boolean selected) {
        JLabel label = new JLabel();
        label.setOpaque(true);
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        if (value != null) {
            label.setIcon(value.isInterface()
                    ? JavaIcons.javaInterface(JavaIcons.SMALL)
                    : JavaIcons.javaClass(JavaIcons.SMALL));
            String packageName = value.packageName();
            label.setText(packageName.isEmpty()
                    ? value.simpleName()
                    : value.simpleName() + "  -  " + packageName);
            label.setToolTipText(value.qualifiedName());
        }
        return label;
    }

    private void runSearch() {
        String term = query.getText().trim();
        int current = generation.incrementAndGet();
        if (term.isEmpty()) {
            results.clear();
            searchStatus.setText(" ");
            return;
        }
        searchStatus.setText("Buscando...");
        background.execute(() -> {
            List<TypeCandidate> found;
            try {
                found = search.apply(term);
            } catch (RuntimeException failure) {
                found = List.of();
            }
            List<TypeCandidate> accepted = new ArrayList<>();
            for (TypeCandidate candidate : found) {
                if (allowSuperclass || candidate.isInterface()) {
                    accepted.add(candidate);
                }
            }
            SwingUtilities.invokeLater(() -> showResults(current, accepted));
        });
    }

    private void showResults(int current, List<TypeCandidate> found) {
        if (generation.get() != current) {
            return;
        }
        results.clear();
        results.addAll(found);
        searchStatus.setText(found.isEmpty() ? "Nenhum tipo encontrado." : " ");
        if (!found.isEmpty()) {
            resultList.setSelectedIndex(0);
        }
    }

    private void pickSelected() {
        TypeCandidate candidate = resultList.getSelectedValue();
        if (candidate == null && !results.isEmpty()) {
            candidate = results.firstElement();
        }
        if (candidate == null) {
            return;
        }
        if (allowSuperclass && !candidate.isInterface()) {
            setSuperclass(candidate.qualifiedName());
        } else if (!interfaces.contains(candidate.qualifiedName())) {
            interfaces.addElement(candidate.qualifiedName());
        }
        query.requestFocusInWindow();
        query.selectAll();
    }

    private void setSuperclass(String type) {
        superclass = type == null ? "" : type;
        superclassLabel.setText(superclass.isEmpty() ? "Nenhuma" : superclass);
        superclassLabel.setForeground(superclass.isEmpty() ? UiTokens.muted() : UiTokens.foreground());
    }

    private void removeSelectedInterfaces() {
        List<String> selected = interfaceList.getSelectedValuesList();
        if (selected.isEmpty() && !interfaces.isEmpty()) {
            interfaces.removeElementAt(interfaces.size() - 1);
            return;
        }
        selected.forEach(interfaces::removeElement);
    }

    private void fitWindowHeight() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null) {
            return;
        }
        int delta = getPreferredSize().height - getHeight();
        if (delta != 0) {
            window.setSize(window.getWidth(), window.getHeight() + delta);
            window.validate();
        }
    }

    private static JPanel selectionRow(String label, Component center, JButton action) {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setForeground(UiTokens.foreground());
        panel.add(caption, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.add(action, BorderLayout.NORTH);
        panel.add(east, BorderLayout.EAST);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel row(String label, Component field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setForeground(UiTokens.foreground());
        panel.add(caption, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private void close() {
        debounce.stop();
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }
}
