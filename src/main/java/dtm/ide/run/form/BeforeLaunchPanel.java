package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.run.chain.RunChainStep;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.PillButtons;
import dtm.ide.ui.UiSupport;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class BeforeLaunchPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(BeforeLaunchPanel.class, key, fallback);
    }

    private static final int ORDER_COLUMN = 0;
    private static final int STEP_COLUMN = 1;
    private static final int MODE_COLUMN = 2;
    private static final int WAIT_COLUMN = 3;

    private final StepTableModel model = new StepTableModel();
    private final JTable table = new JTable(model);
    private final Supplier<List<RunConfigurationData>> configurationsSupplier;
    private final Supplier<String> currentIdSupplier;
    private final Runnable onChanged;

    private final JButton addButton;
    private final JButton removeButton;
    private final JButton upButton;
    private final JButton downButton;

    public BeforeLaunchPanel(Supplier<List<RunConfigurationData>> configurationsSupplier,
                             Supplier<String> currentIdSupplier,
                             Runnable onChanged) {
        super(new BorderLayout(0, UiTokens.space(1)));
        this.configurationsSupplier = configurationsSupplier;
        this.currentIdSupplier = currentIdSupplier;
        this.onChanged = onChanged == null ? () -> {
        } : onChanged;

        setOpaque(false);
        addButton = PillButtons.secondary(text("action.add", "Adicionar"), JavaIcons.create(14));
        removeButton = PillButtons.secondary(text("action.remove", "Remover"), null);
        upButton = PillButtons.secondary(text("action.up", "Subir"), null);
        downButton = PillButtons.secondary(text("action.down", "Descer"), null);

        addButton.addActionListener(event -> addStep());
        removeButton.addActionListener(event -> removeSelected());
        upButton.addActionListener(event -> move(-1));
        downButton.addActionListener(event -> move(1));

        configureTable();
        add(tableScroll(), BorderLayout.CENTER);
        add(toolbar(), BorderLayout.SOUTH);
        updateButtons();
    }

    private void configureTable() {
        UiSupport.modernReadOnlyTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        table.getColumnModel().getColumn(ORDER_COLUMN).setMaxWidth(UiTokens.scale(44));
        table.getColumnModel().getColumn(MODE_COLUMN).setMaxWidth(UiTokens.scale(90));
        table.getColumnModel().getColumn(WAIT_COLUMN).setMaxWidth(UiTokens.scale(110));
    }

    private JComponent tableScroll() {
        JScrollPane scroll = UiSupport.plainScroll(new JScrollPane(table));
        scroll.setPreferredSize(new Dimension(UiTokens.scale(420), UiTokens.scale(132)));
        scroll.setBorder(BorderFactory.createLineBorder(UiTokens.border(), 1, true));
        return scroll;
    }

    private JComponent toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        bar.setOpaque(false);
        bar.add(addButton);
        bar.add(removeButton);
        bar.add(upButton);
        bar.add(downButton);
        return bar;
    }

    public List<RunChainStep> steps() {
        return List.copyOf(model.rows);
    }

    public void setSteps(List<RunChainStep> steps) {
        model.rows.clear();
        if (steps != null) {
            model.rows.addAll(steps);
        }
        model.fireTableDataChanged();
        updateButtons();
    }

    public void refreshLabels() {
        List<RunConfigurationData> available = available();
        for (int index = 0; index < model.rows.size(); index++) {
            RunChainStep step = model.rows.get(index);
            available.stream()
                    .filter(candidate -> step.configurationId().equals(candidate.getId()))
                    .findFirst()
                    .ifPresent(candidate -> model.rows.set(model.rows.indexOf(step),
                            step.withLabel(candidate.getTitle())));
        }
        model.fireTableDataChanged();
    }

    private List<RunConfigurationData> available() {
        List<RunConfigurationData> configurations = configurationsSupplier == null
                ? List.of() : configurationsSupplier.get();
        return configurations == null ? List.of() : configurations;
    }

    private void addStep() {
        String currentId = currentIdSupplier == null ? null : currentIdSupplier.get();
        List<RunConfigurationData> candidates = new ArrayList<>();
        for (RunConfigurationData configuration : available()) {
            if (configuration == null || configuration.getId() == null) {
                continue;
            }
            if (configuration.getId().equals(currentId)) {
                continue;
            }
            candidates.add(configuration);
        }
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    text("empty.message",
                            "Nenhuma outra configuracao salva para adicionar. Crie a configuracao "
                                    + "primeiro e volte aqui."),
                    text("empty.title", "Antes de executar"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StepDialog dialog = new StepDialog(candidates);
        if (dialog.show(this)) {
            model.rows.add(dialog.toStep());
            model.fireTableDataChanged();
            table.setRowSelectionInterval(model.rows.size() - 1, model.rows.size() - 1);
            changed();
        }
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        model.rows.remove(table.convertRowIndexToModel(row));
        model.fireTableDataChanged();
        changed();
    }

    private void move(int offset) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        int from = table.convertRowIndexToModel(row);
        int to = from + offset;
        if (to < 0 || to >= model.rows.size()) {
            return;
        }
        Collections.swap(model.rows, from, to);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
        changed();
    }

    private void changed() {
        updateButtons();
        onChanged.run();
    }

    private void updateButtons() {
        int row = table.getSelectedRow();
        boolean selected = row >= 0;
        removeButton.setEnabled(selected);
        upButton.setEnabled(selected && row > 0);
        downButton.setEnabled(selected && row < model.rows.size() - 1);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        preferred.height = Math.max(preferred.height, UiTokens.scale(180));
        return preferred;
    }

    private final class StepTableModel extends AbstractTableModel {

        private final transient List<RunChainStep> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case ORDER_COLUMN -> "#";
                case STEP_COLUMN -> text("column.step", "Configuracao");
                case MODE_COLUMN -> text("column.mode", "Modo");
                default -> text("column.wait", "Esperar");
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RunChainStep step = rows.get(rowIndex);
            return switch (columnIndex) {
                case ORDER_COLUMN -> String.valueOf(rowIndex + 1);
                case STEP_COLUMN -> step.display();
                case MODE_COLUMN -> step.debug() ? "Debug" : "Run";
                default -> step.waitForExit()
                        ? text("wait.yes", "Sim") : text("wait.no", "Nao");
            };
        }
    }

    private final class StepDialog {

        private final JComboBox<String> configuration = RunFormUi.combo(false);
        private final JComboBox<String> mode = RunFormUi.combo(false);
        private final JCheckBox waitForExit = RunFormUi.checkBox(
                text("field.wait", "Esperar terminar antes do proximo passo"));
        private final List<RunConfigurationData> candidates;

        private StepDialog(List<RunConfigurationData> candidates) {
            this.candidates = candidates;
            RunFormUi.fill(configuration, candidates.stream()
                    .map(RunConfigurationData::getTitle).toList(), null);
            waitForExit.setSelected(true);
            configuration.addActionListener(event -> refreshModes());
            refreshModes();
        }

        private RunConfigurationData selected() {
            int index = configuration.getSelectedIndex();
            return index < 0 || index >= candidates.size() ? null : candidates.get(index);
        }

        private void refreshModes() {
            RunConfigurationData target = selected();
            List<String> modes = new ArrayList<>();
            String type = target == null ? "" : target.getType();
            if (JavaRunTypes.supportsRun(type)) {
                modes.add("Run");
            }
            if (JavaRunTypes.supportsDebug(type)) {
                modes.add("Debug");
            }
            if (modes.isEmpty()) {
                modes.add("Run");
            }
            RunFormUi.fill(mode, modes, modes.getFirst());
        }

        private boolean show(JComponent parent) {
            JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, UiTokens.space(1)));
            form.setOpaque(false);
            form.add(label(text("field.configuration", "Configuracao")));
            form.add(configuration);
            form.add(label(text("field.mode", "Modo")));
            form.add(mode);
            form.add(waitForExit);

            int result = JOptionPane.showConfirmDialog(parent, form,
                    text("dialog.title", "Adicionar passo"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            return result == JOptionPane.OK_OPTION && selected() != null;
        }

        private JLabel label(String value) {
            JLabel component = new JLabel(value);
            component.setFont(UiTokens.fontSmall());
            component.setForeground(UiTokens.muted());
            return component;
        }

        private RunChainStep toStep() {
            RunConfigurationData target = selected();
            boolean debug = "Debug".equals(RunFormUi.valueOf(mode));
            return new RunChainStep(target.getId(), debug, waitForExit.isSelected(),
                    target.getTitle());
        }
    }
}
