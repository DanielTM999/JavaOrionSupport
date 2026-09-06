package dtm.ide.ui;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class JdkManagerPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JdkManagerPanel.class, key, fallback);
    }

    public interface Host {

        List<JdkInstallation> installations();

        JdkInstallation projectJdk();

        void refreshInstallations();

        void download(int major, Consumer<String> onDone);

        void useForProject(JdkInstallation installation);

        boolean remove(JdkInstallation installation);

        void addExisting(Path home, Consumer<String> onDone);

        Integer requiredMajor();
    }

    private final Host host;
    private final JdkTableModel model = new JdkTableModel();
    private final JTable table = new JTable(model);
    private final JComboBox<Integer> versions = new JComboBox<>();
    private final JButton downloadButton = new JButton(text("action.download", "Baixar JDK"),
            JavaIcons.create(JavaIcons.SMALL));
    private final JButton useButton = new JButton(text("action.use", "Usar neste projeto"),
            JavaIcons.java(JavaIcons.SMALL));
    private final JButton removeButton = new JButton(text("action.remove", "Remover"),
            JavaIcons.error(JavaIcons.SMALL));
    private final JButton refreshButton = new JButton(text("action.refresh", "Atualizar"),
            JavaIcons.sync(JavaIcons.SMALL));
    private final JButton addExistingButton = new JButton(
            text("action.addExisting", "Adicionar JDK do disco..."),
            JavaIcons.create(JavaIcons.SMALL));
    private final BadgeLabel status = new BadgeLabel(" ", BadgeLabel.Tone.NEUTRAL)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);

    public JdkManagerPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        int pad = UiTokens.space(2);
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));

        JdkService.DOWNLOADABLE_MAJORS.forEach(versions::addItem);
        versions.setSelectedItem(preferredVersion());

        configureTable();
        JScrollPane scroll = UiSupport.plainScroll(new JScrollPane(table));
        CardPanel card = new CardPanel(text("card.title", "JDKs instaladas"),
                text("card.subtitle", "Escolha a JDK que este projeto usa para compilar e executar"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD))
                .setContent(scroll);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);

        add(toolbar(), BorderLayout.NORTH);
        add(card, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        downloadButton.addActionListener(event -> downloadSelectedVersion());
        addExistingButton.addActionListener(event -> addExistingFromDisk());
        useButton.addActionListener(event -> useSelected());
        removeButton.addActionListener(event -> removeSelected());
        refreshButton.addActionListener(event -> {
            host.refreshInstallations();
            reload();
        });

        UiSupport.quietFocus(this);
        table.setFocusable(true);
        reload();
    }

    private void configureTable() {
        UiSupport.modernReadOnlyTable(table);
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        table.getColumnModel().getColumn(IN_USE_COLUMN)
                .setCellRenderer(UiSupport.badgeColumn(value -> BadgeLabel.Tone.SUCCESS));
        table.getColumnModel().getColumn(PATH_COLUMN).setCellRenderer(UiSupport.monoColumn());
    }

    private ToolBarPanel toolbar() {
        ToolBarPanel bar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        JLabel label = new JLabel(text("label.version", "Versao:"));
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        bar.addItem(label).addItem(versions).addItem(downloadButton).addItem(addExistingButton)
                .addSpacer()
                .addItem(useButton).addItem(removeButton).addItem(refreshButton);
        return bar;
    }

    private Integer preferredVersion() {
        Integer required = host.requiredMajor();
        return required != null && JdkService.DOWNLOADABLE_MAJORS.contains(required)
                ? required
                : JdkService.DEFAULT_MAJOR;
    }

    public void promptForMissingJdk(Integer major, String failure) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> promptForMissingJdk(major, failure));
            return;
        }
        if (major != null && JdkService.DOWNLOADABLE_MAJORS.contains(major)) {
            versions.setSelectedItem(major);
        }
        status.setTone(BadgeLabel.Tone.WARNING);
        status.setText(failure == null
                ? text("status.missing", "Nenhuma JDK compativel foi encontrada.")
                : failure);
        String detail = major == null
                ? text("prompt.missingAny", "Nenhuma JDK utilizavel foi encontrada nesta maquina.")
                : text("prompt.missing", "O projeto precisa da JDK") + " " + major
                        + " " + text("prompt.missingTail", "e ela nao foi encontrada nesta maquina.");
        String action = text("prompt.action",
                "Use \"Baixar JDK\" para instalar agora ou \"Adicionar JDK do disco...\""
                        + " para apontar uma instalacao existente.");
        String message = failure == null
                ? detail + "\n\n" + action
                : detail + "\n\n" + failure + "\n\n" + action;
        JOptionPane.showMessageDialog(this, message, text("prompt.title", "JDK necessaria"),
                JOptionPane.WARNING_MESSAGE);
    }

    private void addExistingFromDisk() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(text("chooser.title", "Selecione o diretorio da JDK"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path home = chooser.getSelectedFile().toPath();
        status.setTone(BadgeLabel.Tone.INFO);
        setBusy(true, text("status.inspecting", "Verificando") + " " + home + "...");
        host.addExisting(home, error -> SwingUtilities.invokeLater(() -> {
            setBusy(false, error == null
                    ? text("status.added", "JDK adicionada:") + " " + home
                    : error);
            status.setTone(error == null ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.DANGER);
            reload();
        }));
    }

    private static final int IN_USE_COLUMN = 3;
    private static final int PATH_COLUMN = 4;

    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        JdkInstallation selected = selectedInstallation();
        model.setRows(host.installations(), host.projectJdk(), host.requiredMajor());
        if (selected != null) {
            selectByHome(selected);
        }
        updateButtons();
    }

    private void downloadSelectedVersion() {
        Integer major = (Integer) versions.getSelectedItem();
        if (major == null) {
            return;
        }
        status.setTone(BadgeLabel.Tone.INFO);
        setBusy(true, text("status.downloading", "Baixando JDK") + " " + major + "...");
        host.download(major, error -> SwingUtilities.invokeLater(() -> {
            setBusy(false, error == null
                    ? text("status.downloaded", "JDK instalada:") + " " + major
                    : error);
            status.setTone(error == null ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.DANGER);
            reload();
        }));
    }

    private void useSelected() {
        JdkInstallation selected = selectedInstallation();
        if (selected == null) {
            return;
        }
        host.useForProject(selected);
        status.setText(text("status.inUse", "Projeto usando") + " " + selected.displayName());
        status.setTone(BadgeLabel.Tone.SUCCESS);
        reload();
    }

    private void removeSelected() {
        JdkInstallation selected = selectedInstallation();
        if (selected == null) {
            return;
        }
        if (host.remove(selected)) {
            status.setText(text("status.removed", "JDK removida:") + " " + selected.displayName());
            status.setTone(BadgeLabel.Tone.NEUTRAL);
        } else {
            status.setText(text("status.notManaged",
                    "Somente as JDKs baixadas pelo plugin podem ser removidas."));
            status.setTone(BadgeLabel.Tone.WARNING);
        }
        reload();
    }

    private void setBusy(boolean busy, String message) {
        downloadButton.setEnabled(!busy);
        addExistingButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        status.setText(message);
        if (busy) {
            useButton.setEnabled(false);
            removeButton.setEnabled(false);
        } else {
            updateButtons();
        }
    }

    private void updateButtons() {
        JdkInstallation selected = selectedInstallation();
        useButton.setEnabled(selected != null && selected.isUsable());
        removeButton.setEnabled(selected != null && selected.isManaged());
    }

    private JdkInstallation selectedInstallation() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.rowAt(table.convertRowIndexToModel(row));
    }

    private void selectByHome(JdkInstallation installation) {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (model.rowAt(row).home().equals(installation.home())) {
                int viewRow = table.convertRowIndexToView(row);
                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                return;
            }
        }
    }

    private static final class JdkTableModel extends AbstractTableModel {

        private final String[] columns = {
                text("column.vendor", "Fornecedor"),
                text("column.version", "Versao"),
                text("column.origin", "Origem"),
                text("column.inUse", "Em uso"),
                text("column.path", "Caminho")
        };

        private List<JdkInstallation> rows = List.of();
        private JdkInstallation inUse;
        private Integer requiredMajor;

        void setRows(List<JdkInstallation> installations, JdkInstallation inUse, Integer requiredMajor) {
            this.rows = installations == null ? List.of() : new ArrayList<>(installations);
            this.inUse = inUse;
            this.requiredMajor = requiredMajor;
            fireTableDataChanged();
        }

        JdkInstallation rowAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JdkInstallation row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.vendor().displayName();
                case 1 -> row.fullVersion() + (row.isJdk() ? "" : "  (JRE)");
                case 2 -> originLabel(row);
                case 3 -> usageLabel(row);
                default -> row.home().toString();
            };
        }

        private String usageLabel(JdkInstallation row) {
            if (inUse != null && inUse.home().equals(row.home())) {
                return text("inUse.yes", "Em uso");
            }
            return requiredMajor != null && requiredMajor == row.major()
                    ? text("inUse.required", "Requerida pelo projeto")
                    : "";
        }

        private static String originLabel(JdkInstallation installation) {
            return switch (installation.origin()) {
                case MANAGED -> text("origin.managed", "Gerenciada");
                case JAVA_HOME -> "JAVA_HOME";
                case PATH -> "PATH";
                case VERSION_MANAGER -> text("origin.versionManager", "Gerenciador de versoes");
                case SYSTEM -> text("origin.system", "Sistema");
            };
        }
    }
}
