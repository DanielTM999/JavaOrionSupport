package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dtm.ide.swingdesigner.catalog.ComponentDescriptor;
import dtm.ide.swingdesigner.catalog.ParameterInfo;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.ui.UiSupport;
import dtm.stools.configs.UiTokens;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ViewerInspector extends JPanel {

    private final JLabel title = new JLabel(" ");
    private final JLabel subtitle = new JLabel(" ");
    private final RowsModel model = new RowsModel();
    private final JTable table = new JTable(model);
    private BiConsumer<PropertyDescriptor, JsonNode> propertyListener = (property, value) -> { };
    private BiConsumer<Integer, JsonNode> argumentListener = (index, value) -> { };
    private Consumer<String> nameListener = name -> { };
    private Consumer<PropertyDescriptor> resetListener = property -> { };
    private Consumer<PropertyDescriptor> navigateListener = property -> { };
    private Set<String> definedSetters = Set.of();

    ViewerInspector() {
        super(new BorderLayout());
        setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), UiTokens.space(2),
                UiTokens.space(1), UiTokens.space(2)));
        title.setFont(UiTokens.fontBold());
        subtitle.setFont(UiTokens.fontSmall());
        subtitle.setForeground(UiTokens.muted());
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        UiSupport.modernTable(table);
        table.setRowHeight(UiTokens.scale(26));
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                popup(event);
            }
        });
        add(UiSupport.plainScroll(new JScrollPane(table)), BorderLayout.CENTER);
    }

    private void popup(java.awt.event.MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int index = table.rowAtPoint(event.getPoint());
        if (index < 0) {
            return;
        }
        Row row = model.row(index);
        if (row.kind() != Kind.PROPERTY || !definedSetters.contains(row.property().setter())) {
            return;
        }
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem open = new javax.swing.JMenuItem("Ir para o codigo");
        open.addActionListener(click -> navigateListener.accept(row.property()));
        javax.swing.JMenuItem reset = new javax.swing.JMenuItem("Remover do codigo (voltar ao padrao)");
        reset.addActionListener(click -> resetListener.accept(row.property()));
        menu.add(open);
        menu.add(reset);
        menu.show(table, event.getX(), event.getY());
    }

    void onNameEdited(Consumer<String> listener) {
        nameListener = listener == null ? name -> { } : listener;
    }

    void onPropertyReset(Consumer<PropertyDescriptor> listener) {
        resetListener = listener == null ? property -> { } : listener;
    }

    void onPropertyNavigate(Consumer<PropertyDescriptor> listener) {
        navigateListener = listener == null ? property -> { } : listener;
    }

    void onPropertyEdited(BiConsumer<PropertyDescriptor, JsonNode> listener) {
        propertyListener = listener;
    }

    void onArgumentEdited(BiConsumer<Integer, JsonNode> listener) {
        argumentListener = listener;
    }

    void clear(String message) {
        stopEditing();
        title.setText(message == null ? " " : message);
        subtitle.setText(" ");
        model.setRows(List.of());
    }

    void show(String heading, String className, ComponentDescriptor descriptor, Map<String, JsonNode> values,
              List<ParameterInfo> constructorParameters, List<JsonNode> constructorValues) {
        show(heading, className, descriptor, values, constructorParameters, constructorValues, null, false,
                Set.of(), null);
    }

    void show(String heading, String className, ComponentDescriptor descriptor, Map<String, JsonNode> values,
              List<ParameterInfo> constructorParameters, List<JsonNode> constructorValues, String variable,
              boolean nameEditable, Set<String> defined, String readOnlyReason) {
        stopEditing();
        definedSetters = defined == null ? Set.of() : Set.copyOf(defined);
        title.setText(heading);
        subtitle.setText(readOnlyReason == null ? className : "Somente leitura: " + readOnlyReason);
        subtitle.setToolTipText(className);
        List<Row> rows = new ArrayList<>();
        if (variable != null) {
            rows.add(Row.header("Codigo"));
            rows.add(Row.name(variable, nameEditable));
        }
        if (constructorParameters != null && !constructorParameters.isEmpty()) {
            rows.add(Row.header("Argumentos do construtor"));
            for (int i = 0; i < constructorParameters.size(); i++) {
                ParameterInfo parameter = constructorParameters.get(i);
                JsonNode value = constructorValues != null && i < constructorValues.size()
                        ? constructorValues.get(i) : JsonNodeFactory.instance.nullNode();
                rows.add(Row.argument(i, parameter.name(), parameter.type(), value));
            }
        }
        if (descriptor != null) {
            List<PropertyDescriptor> properties = new ArrayList<>(descriptor.propertiesOrEmpty().values());
            properties.removeIf(property -> property.isHidden() || property.arity() != 1
                    || !property.isReadable());
            properties.sort(Comparator.comparing((PropertyDescriptor property) -> !property.isPreferred())
                    .thenComparing(property -> category(property))
                    .thenComparing(PropertyDescriptor::label, String.CASE_INSENSITIVE_ORDER));
            String currentCategory = null;
            boolean preferredSection = false;
            for (PropertyDescriptor property : properties) {
                String section = property.isPreferred() ? "Principais" : category(property);
                if (property.isPreferred() && !preferredSection) {
                    rows.add(Row.header(section));
                    preferredSection = true;
                    currentCategory = section;
                } else if (!property.isPreferred() && !section.equals(currentCategory)) {
                    rows.add(Row.header(section));
                    currentCategory = section;
                }
                rows.add(Row.property(property, values.get(property.name())));
            }
        }
        model.setRows(rows);
    }

    private static String category(PropertyDescriptor property) {
        return property.category() == null ? "Outras" : property.category();
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
    }

    private void commit(Row row, Object edited) {
        JsonNode value;
        try {
            value = edited instanceof Boolean flag ? JsonNodeFactory.instance.booleanNode(flag)
                    : InspectorValues.parse(edited == null ? "" : edited.toString(), row.type());
        } catch (RuntimeException error) {
            subtitle.setText("Valor invalido: " + error.getMessage());
            return;
        }
        if (row.kind() == Kind.NAME) {
            String name = edited == null ? "" : edited.toString().trim();
            if (!name.isEmpty() && !name.equals(row.value().asText())) {
                nameListener.accept(name);
            }
        } else if (row.kind() == Kind.ARGUMENT) {
            argumentListener.accept(row.argumentIndex(), value);
        } else if (row.kind() == Kind.PROPERTY) {
            propertyListener.accept(row.property(), value);
        }
    }

    enum Kind {
        HEADER,
        NAME,
        ARGUMENT,
        PROPERTY
    }

    record Row(Kind kind, String label, String type, PropertyDescriptor property, int argumentIndex,
               JsonNode value, List<String> choices) {

        static Row header(String label) {
            return new Row(Kind.HEADER, label, null, null, -1, null, null);
        }

        static Row name(String variable, boolean editable) {
            return new Row(Kind.NAME, "Nome da variavel", editable ? "java.lang.String" : null, null, -1,
                    JsonNodeFactory.instance.textNode(variable), null);
        }

        static Row argument(int index, String name, String type, JsonNode value) {
            return new Row(Kind.ARGUMENT, name, type, null, index, value, null);
        }

        static Row property(PropertyDescriptor property, JsonNode value) {
            return new Row(Kind.PROPERTY, property.label(), property.type(), property, -1, value,
                    property.enumValues());
        }

        boolean editable() {
            if (kind == Kind.HEADER) {
                return false;
            }
            if (kind == Kind.PROPERTY && !property.isWritable()) {
                return false;
            }
            return InspectorValues.isEditable(type, choices != null && !choices.isEmpty());
        }
    }

    private final class RowsModel extends AbstractTableModel {

        private List<Row> rows = List.of();

        void setRows(List<Row> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        Row row(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Propriedade" : "Valor";
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            if (column == 0) {
                return row.label();
            }
            if (row.kind() == Kind.HEADER) {
                return "";
            }
            String text = InspectorValues.display(row.value());
            if (row.choices() != null && !row.choices().isEmpty() && row.value() != null
                    && row.value().isTextual()) {
                return InspectorValues.shortConstant(text);
            }
            return text;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int column) {
            return column == 1 && rows.get(rowIndex).editable();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            commit(rows.get(rowIndex), value);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        table.getColumnModel().getColumn(1).setCellEditor(new RowEditor());
        table.getColumnModel().getColumn(0).setPreferredWidth(UiTokens.scale(170));
        table.getColumnModel().getColumn(1).setPreferredWidth(UiTokens.scale(150));
    }

    private final class RowEditor extends AbstractCellEditor implements TableCellEditor {

        private TableCellEditor delegate;

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected,
                                                     int rowIndex, int column) {
            Row row = model.row(rowIndex);
            if (row.choices() != null && !row.choices().isEmpty()) {
                JComboBox<String> combo = new JComboBox<>(row.choices().toArray(String[]::new));
                combo.setRenderer(new DefaultListRendererWithShortNames());
                String current = row.value() == null ? null : row.value().asText();
                for (String choice : row.choices()) {
                    if (choice.equals(current) || InspectorValues.shortConstant(choice).equals(current)) {
                        combo.setSelectedItem(choice);
                    }
                }
                delegate = new DefaultCellEditor(combo);
            } else if (InspectorValues.isBoolean(row.type())) {
                JCheckBox check = new JCheckBox();
                check.setSelected(row.value() != null && row.value().asBoolean());
                delegate = new DefaultCellEditor(check) {
                    @Override
                    public Object getCellEditorValue() {
                        return check.isSelected();
                    }
                };
            } else {
                JTextField field = new JTextField();
                delegate = new DefaultCellEditor(field);
            }
            delegate.addCellEditorListener(new javax.swing.event.CellEditorListener() {
                @Override
                public void editingStopped(javax.swing.event.ChangeEvent event) {
                    fireEditingStopped();
                }

                @Override
                public void editingCanceled(javax.swing.event.ChangeEvent event) {
                    fireEditingCanceled();
                }
            });
            return delegate.getTableCellEditorComponent(table, value, selected, rowIndex, column);
        }

        @Override
        public Object getCellEditorValue() {
            return delegate == null ? null : delegate.getCellEditorValue();
        }

        @Override
        public boolean stopCellEditing() {
            return delegate == null || delegate.stopCellEditing();
        }

        @Override
        public void cancelCellEditing() {
            if (delegate != null) {
                delegate.cancelCellEditing();
            }
        }
    }

    private static javax.swing.Icon swatch(String hex) {
        java.awt.Color color;
        try {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            if (clean.length() < 6) {
                return null;
            }
            color = new java.awt.Color(Integer.parseInt(clean.substring(0, 6), 16));
        } catch (RuntimeException e) {
            return null;
        }
        int size = UiTokens.scale(12);
        return new javax.swing.Icon() {
            @Override
            public void paintIcon(Component component, java.awt.Graphics graphics, int x, int y) {
                graphics.setColor(color);
                graphics.fillRect(x, y, size, size);
                graphics.setColor(UiTokens.border());
                graphics.drawRect(x, y, size - 1, size - 1);
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static final class DefaultListRendererWithShortNames extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Object shown = value == null ? null : InspectorValues.shortConstant(value.toString());
            return super.getListCellRendererComponent(list, shown, index, selected, focused);
        }
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int rowIndex, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, rowIndex, column);
            Row row = model.row(rowIndex);
            setBorder(BorderFactory.createEmptyBorder(0, UiTokens.space(2), 0, UiTokens.space(1)));
            setIcon(null);
            if (row.kind() == Kind.HEADER) {
                setFont(UiTokens.fontBold());
                setForeground(UiTokens.muted());
                setBackground(UiTokens.overlay(UiTokens.foreground(), 0.04F));
                setOpaque(true);
                return this;
            }
            setOpaque(selected);
            boolean defined = row.kind() == Kind.PROPERTY && definedSetters.contains(row.property().setter());
            Font font = column == 1 && row.kind() == Kind.ARGUMENT
                    ? UiTokens.font().deriveFont(Font.ITALIC) : UiTokens.font();
            setFont(column == 0 && defined ? font.deriveFont(Font.BOLD) : font);
            setForeground(column == 1 && !row.editable() ? UiTokens.muted() : UiTokens.foreground());
            setToolTipText(defined ? row.type() + " - definido no codigo (botao direito para opcoes)" : row.type());
            if (column == 1 && "java.awt.Color".equals(row.type()) && row.value() != null && row.value().isTextual()) {
                setIcon(swatch(row.value().asText()));
            }
            return this;
        }
    }
}
