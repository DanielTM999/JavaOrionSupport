package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.swingdesigner.catalog.LayoutDescriptor;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.swingdesigner.form.LayoutCode;
import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.ide.ui.PillButtons;
import dtm.ide.ui.UiSupport;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class LayoutPanel extends JPanel {

    interface Actions {
        void setLayout(String layoutClass, Map<String, String> values);

        void setConstraints(String expression);

        void setBounds(Rectangle bounds);

        void setPreferredSize(Dimension size);

        void reorder(int delta);
    }

    private static final String ABSOLUTE_LABEL = "Absoluto (null)";
    private static final Map<String, String> BORDER_REGIONS = Map.of("North", "NORTH", "South", "SOUTH",
            "East", "EAST", "West", "WEST", "Center", "CENTER", "First", "PAGE_START", "Last", "PAGE_END",
            "Before", "LINE_START", "After", "LINE_END");
    private static final Map<Integer, String> ANCHORS = new LinkedHashMap<>();
    private static final Map<Integer, String> FILLS = new LinkedHashMap<>();

    static {
        ANCHORS.put(GridBagConstraints.CENTER, "CENTER");
        ANCHORS.put(GridBagConstraints.NORTH, "NORTH");
        ANCHORS.put(GridBagConstraints.NORTHEAST, "NORTHEAST");
        ANCHORS.put(GridBagConstraints.EAST, "EAST");
        ANCHORS.put(GridBagConstraints.SOUTHEAST, "SOUTHEAST");
        ANCHORS.put(GridBagConstraints.SOUTH, "SOUTH");
        ANCHORS.put(GridBagConstraints.SOUTHWEST, "SOUTHWEST");
        ANCHORS.put(GridBagConstraints.WEST, "WEST");
        ANCHORS.put(GridBagConstraints.NORTHWEST, "NORTHWEST");
        ANCHORS.put(GridBagConstraints.LINE_START, "LINE_START");
        ANCHORS.put(GridBagConstraints.LINE_END, "LINE_END");
        ANCHORS.put(GridBagConstraints.BASELINE, "BASELINE");
        FILLS.put(GridBagConstraints.NONE, "NONE");
        FILLS.put(GridBagConstraints.HORIZONTAL, "HORIZONTAL");
        FILLS.put(GridBagConstraints.VERTICAL, "VERTICAL");
        FILLS.put(GridBagConstraints.BOTH, "BOTH");
    }

    private final JPanel content = new JPanel();
    private final JLabel note = new JLabel(" ");

    LayoutPanel() {
        super(new BorderLayout());
        setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(content, BorderLayout.NORTH);
        note.setFont(UiTokens.fontSmall());
        note.setForeground(UiTokens.muted());
        note.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2), UiTokens.space(1),
                UiTokens.space(2)));
        add(note, BorderLayout.NORTH);
        add(UiSupport.plainScroll(new JScrollPane(holder)), BorderLayout.CENTER);
    }

    void clear(String message) {
        content.removeAll();
        note.setText(message == null ? " " : message);
        content.revalidate();
        content.repaint();
    }

    void show(SnapshotNode node, SnapshotNode parent, Map<String, LayoutDescriptor> layouts, boolean editable,
              String reason, Actions actions) {
        content.removeAll();
        note.setText(editable ? " " : "Somente leitura: " + (reason == null ? "codigo indisponivel" : reason));
        if (node.hasLayout()) {
            content.add(section("Layout deste container"));
            layoutEditor(node, layouts, editable, actions);
        }
        if (parent != null && parent.hasLayout()) {
            content.add(section("Posicao dentro de " + parent.simpleClassName()));
            positionEditor(node, parent, editable, actions);
        }
        content.add(section("Tamanho preferido"));
        JTextField preferred = field(node.preferred().width + ", " + node.preferred().height, editable);
        content.add(line("largura, altura", preferred, apply(editable, () -> {
            int[] values = numbers(preferred.getText(), 2);
            actions.setPreferredSize(new Dimension(values[0], values[1]));
        })));
        if (content.getComponentCount() == 0) {
            note.setText("Nada para ajustar neste componente");
        }
        content.revalidate();
        content.repaint();
    }

    private void layoutEditor(SnapshotNode node, Map<String, LayoutDescriptor> layouts, boolean editable,
                              Actions actions) {
        List<String> classes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        classes.add(LayoutCode.ABSOLUTE);
        labels.add(ABSOLUTE_LABEL);
        layouts.forEach((className, descriptor) -> {
            classes.add(className);
            labels.add(descriptor.label());
        });
        String current = node.layoutClass();
        if (current != null && !classes.contains(current)) {
            classes.add(current);
            labels.add(current.substring(current.lastIndexOf('.') + 1));
        }
        JComboBox<String> combo = new JComboBox<>(labels.toArray(String[]::new));
        combo.setEnabled(editable);
        int selected = Math.max(0, classes.indexOf(current));
        combo.setSelectedIndex(selected);
        content.add(line("layout", combo, null));
        JPanel properties = new JPanel();
        properties.setLayout(new BoxLayout(properties, BoxLayout.Y_AXIS));
        properties.setOpaque(false);
        properties.setAlignmentX(LEFT_ALIGNMENT);
        content.add(properties);
        Map<String, Supplier<String>> values = new LinkedHashMap<>();
        Runnable fill = () -> {
            properties.removeAll();
            values.clear();
            String chosen = classes.get(combo.getSelectedIndex());
            LayoutDescriptor descriptor = layouts.get(chosen);
            boolean same = chosen.equals(current);
            if (descriptor != null && descriptor.properties() != null) {
                descriptor.properties().forEach((name, property) -> {
                    if (name.equals("target")) {
                        return;
                    }
                    JComponent editor = propertyEditor(property, same ? node.layout().path(name) : null, editable);
                    values.put(name, () -> valueOf(editor));
                    properties.add(line(name, editor, null));
                });
            }
            properties.revalidate();
            properties.repaint();
        };
        fill.run();
        combo.addActionListener(event -> fill.run());
        JButton apply = PillButtons.secondary("Aplicar layout", null);
        apply.setEnabled(editable);
        apply.addActionListener(event -> {
            Map<String, String> chosen = new LinkedHashMap<>();
            values.forEach((name, supplier) -> chosen.put(name, supplier.get()));
            actions.setLayout(classes.get(combo.getSelectedIndex()), chosen);
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bar.setOpaque(false);
        bar.add(apply);
        content.add(padded(bar));
    }

    private void positionEditor(SnapshotNode node, SnapshotNode parent, boolean editable, Actions actions) {
        String layout = parent.layoutClass();
        if (LayoutCode.BORDER.equals(layout)) {
            String[] regions = {"NORTH", "SOUTH", "EAST", "WEST", "CENTER", "PAGE_START", "PAGE_END",
                    "LINE_START", "LINE_END"};
            JComboBox<String> region = new JComboBox<>(regions);
            region.setEnabled(editable);
            String current = BORDER_REGIONS.getOrDefault(node.constraints().asText(""), "CENTER");
            region.setSelectedItem(current);
            region.addActionListener(event -> {
                if (!current.equals(region.getSelectedItem())) {
                    actions.setConstraints("java.awt.BorderLayout." + region.getSelectedItem());
                }
            });
            content.add(line("regiao", region, null));
            return;
        }
        if (LayoutCode.GRID_BAG.equals(layout)) {
            gridBagEditor(node.constraints(), editable, actions);
            return;
        }
        if (LayoutCode.ABSOLUTE.equals(layout)) {
            Rectangle bounds = node.bounds();
            Rectangle base = parent.bounds();
            JTextField field = field((bounds.x - base.x) + ", " + (bounds.y - base.y) + ", " + bounds.width + ", "
                    + bounds.height, editable);
            content.add(line("x, y, largura, altura", field, apply(editable, () -> {
                int[] values = numbers(field.getText(), 4);
                actions.setBounds(new Rectangle(values[0], values[1], values[2], values[3]));
            })));
            return;
        }
        JLabel order = new JLabel("posicao " + Math.max(0, node.index()) + " de " + parent.children().size());
        order.setFont(UiTokens.font());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.scale(4), 0));
        buttons.setOpaque(false);
        JButton earlier = PillButtons.secondary("Antes", null);
        JButton later = PillButtons.secondary("Depois", null);
        earlier.setEnabled(editable && node.index() > 0);
        later.setEnabled(editable && node.index() >= 0 && node.index() < parent.children().size() - 1);
        earlier.addActionListener(event -> actions.reorder(-1));
        later.addActionListener(event -> actions.reorder(1));
        buttons.add(earlier);
        buttons.add(later);
        content.add(line("ordem", order, buttons));
    }

    private void gridBagEditor(JsonNode constraints, boolean editable, Actions actions) {
        Map<String, JTextField> numeric = new LinkedHashMap<>();
        for (String name : List.of("gridx", "gridy", "gridwidth", "gridheight", "weightx", "weighty", "ipadx",
                "ipady")) {
            JsonNode value = constraints.path(name);
            String text;
            if (value.isMissingNode()) {
                text = "";
            } else if (value.isIntegralNumber() && value.asInt() == GridBagConstraints.RELATIVE) {
                text = "RELATIVE";
            } else if (value.isIntegralNumber() && value.asInt() == GridBagConstraints.REMAINDER
                    && (name.equals("gridwidth") || name.equals("gridheight"))) {
                text = "REMAINDER";
            } else {
                text = value.asText();
            }
            JTextField field = field(text, editable);
            numeric.put(name, field);
            content.add(line(name, field, null));
        }
        JComboBox<String> anchor = new JComboBox<>(ANCHORS.values().toArray(String[]::new));
        anchor.setSelectedItem(ANCHORS.getOrDefault(constraints.path("anchor").asInt(GridBagConstraints.CENTER),
                "CENTER"));
        anchor.setEnabled(editable);
        content.add(line("anchor", anchor, null));
        JComboBox<String> fill = new JComboBox<>(FILLS.values().toArray(String[]::new));
        fill.setSelectedItem(FILLS.getOrDefault(constraints.path("fill").asInt(GridBagConstraints.NONE), "NONE"));
        fill.setEnabled(editable);
        content.add(line("fill", fill, null));
        JsonNode margin = constraints.path("insets");
        JTextField insets = field(margin.path("top").asInt() + ", " + margin.path("left").asInt() + ", "
                + margin.path("bottom").asInt() + ", " + margin.path("right").asInt(), editable);
        content.add(line("insets", insets, null));
        JButton apply = PillButtons.secondary("Aplicar posicao", null);
        apply.setEnabled(editable);
        apply.addActionListener(event -> {
            Map<String, String> values = new LinkedHashMap<>();
            numeric.forEach((name, field) -> {
                String text = field.getText().trim();
                if (text.isEmpty()) {
                    return;
                }
                values.put(name, Character.isLetter(text.charAt(0))
                        ? "java.awt.GridBagConstraints." + text : text);
            });
            values.put("anchor", "java.awt.GridBagConstraints." + anchor.getSelectedItem());
            values.put("fill", "java.awt.GridBagConstraints." + fill.getSelectedItem());
            int[] margins = numbers(insets.getText(), 4);
            values.put("insets", "new java.awt.Insets(" + margins[0] + ", " + margins[1] + ", " + margins[2] + ", "
                    + margins[3] + ")");
            actions.setConstraints(LayoutCode.gridBag(values, name -> name));
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bar.setOpaque(false);
        bar.add(apply);
        content.add(padded(bar));
    }

    private static JComponent propertyEditor(PropertyDescriptor property, JsonNode current, boolean editable) {
        if (property.enumValues() != null && !property.enumValues().isEmpty()) {
            JComboBox<String> combo = new JComboBox<>(property.enumValues().toArray(String[]::new));
            combo.setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                       int index, boolean selected,
                                                                       boolean focused) {
                    return super.getListCellRendererComponent(list, value == null ? null
                            : InspectorValues.shortConstant(value.toString()), index, selected, focused);
                }
            });
            if (property.defaultValue() != null) {
                combo.setSelectedItem(property.defaultValue());
            }
            if (current != null && current.isIntegralNumber()) {
                int value = current.asInt();
                for (String constant : property.enumValues()) {
                    Integer resolved = constantValue(constant);
                    if (resolved != null && resolved == value) {
                        combo.setSelectedItem(constant);
                    }
                }
            }
            combo.setEnabled(editable);
            return combo;
        }
        String text = current != null && !current.isMissingNode() && !current.isNull() ? current.asText()
                : property.defaultValue() == null ? "0" : property.defaultValue();
        return field(text, editable);
    }

    private static Integer constantValue(String reference) {
        int dot = reference.lastIndexOf('.');
        try {
            Class<?> owner = Class.forName(reference.substring(0, dot));
            Object value = owner.getField(reference.substring(dot + 1)).get(null);
            return value instanceof Integer number ? number : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String valueOf(JComponent editor) {
        if (editor instanceof JComboBox<?> combo) {
            return String.valueOf(combo.getSelectedItem());
        }
        return ((JTextField) editor).getText().trim();
    }

    private static JTextField field(String text, boolean editable) {
        JTextField field = new JTextField(text);
        field.setEditable(editable);
        field.setFont(UiTokens.font());
        return field;
    }

    private static JButton apply(boolean editable, Runnable action) {
        JButton button = PillButtons.secondary("Aplicar", null);
        button.setEnabled(editable);
        button.addActionListener(event -> {
            try {
                action.run();
            } catch (IllegalArgumentException invalid) {
                button.setToolTipText(invalid.getMessage());
            }
        });
        return button;
    }

    private JPanel section(String text) {
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

    private static JPanel line(String label, JComponent editor, JComponent trailing) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(2), UiTokens.space(2), UiTokens.scale(2),
                UiTokens.space(2)));
        JLabel name = new JLabel(label);
        name.setFont(UiTokens.font());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 0, UiTokens.space(1));
        name.setPreferredSize(new Dimension(UiTokens.scale(110), name.getPreferredSize().height));
        row.add(name, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        row.add(editor, constraints);
        if (trailing != null) {
            constraints.gridx = 2;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.insets = new Insets(0, UiTokens.space(1), 0, 0);
            row.add(trailing, constraints);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static JPanel padded(JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(2), UiTokens.space(2), UiTokens.scale(4),
                UiTokens.space(2)));
        panel.add(component, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    static int[] numbers(String text, int count) {
        String[] parts = text.trim().split("[,;x\\s]+");
        int[] values = new int[count];
        int index = 0;
        for (String part : parts) {
            if (part.isBlank() || index >= count) {
                continue;
            }
            values[index++] = Integer.parseInt(part.trim());
        }
        if (index < count) {
            throw new IllegalArgumentException("Informe " + count + " numeros separados por virgula");
        }
        return values;
    }
}
