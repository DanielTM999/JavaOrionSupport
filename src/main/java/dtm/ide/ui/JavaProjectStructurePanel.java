package dtm.ide.ui;

import dtm.ide.project.JavaModule;
import dtm.ide.project.ProjectLayout;
import dtm.ide.run.form.RunFormUi;
import dtm.ide.sdk.JdkInstallation;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JavaProjectStructurePanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaProjectStructurePanel.class, key, fallback);
    }

    public interface Host {

        List<JavaModule> modules();

        List<JdkInstallation> installations();

        JdkInstallation projectJdk();

        Integer languageLevel();

        void apply(JdkInstallation jdk, Integer languageLevel, ProjectLayoutChange layout);

        JComponent librariesView();

        Path projectRoot();

        List<FolderRole> folders();
    }

    public record FolderRole(Path folder, ProjectLayout.Role role) {
    }

    public record ProjectLayoutChange(List<FolderRole> folders) {
    }

    private final Host host;

    private final JComboBox<JdkItem> jdkSelector = new JComboBox<>();
    private final JComboBox<Integer> languageLevel = new JComboBox<>();
    private final ModulesTableModel modulesModel = new ModulesTableModel();
    private final JTable modulesTable = new JTable(modulesModel);
    private final FoldersTableModel foldersModel = new FoldersTableModel();
    private final JTable foldersTable = new JTable(foldersModel);
    private final JButton applyButton = new JButton(text("action.apply", "Aplicar"),
            JavaIcons.create(JavaIcons.SMALL));
    private final JButton reloadButton = new JButton(text("action.reload", "Recarregar"),
            JavaIcons.sync(JavaIcons.SMALL));
    private final JButton addFolderButton = new JButton(
            text("action.addFolder", "Adicionar pasta..."), JavaIcons.folder(JavaIcons.SMALL));
    private final JButton removeFolderButton = new JButton(
            text("action.removeFolder", "Remover"), JavaIcons.stop(JavaIcons.SMALL));
    private final BadgeLabel status = new BadgeLabel(" ", BadgeLabel.Tone.NEUTRAL)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);

    public JavaProjectStructurePanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        int pad = UiTokens.space(2);
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(text("tab.sdk", "SDK"), sdkTab());
        tabs.addTab(text("tab.modules", "Modulos"), modulesTab());
        tabs.addTab(text("tab.sources", "Pastas"), foldersTab());
        JComponent libraries = host.librariesView();
        if (libraries != null) {
            tabs.addTab(text("tab.libraries", "Bibliotecas"), libraries);
        }

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);

        add(toolbar(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        applyButton.addActionListener(event -> apply());
        reloadButton.addActionListener(event -> reload());
        addFolderButton.addActionListener(event -> addFolder());
        removeFolderButton.addActionListener(event -> removeSelectedFolder());
        foldersTable.getSelectionModel().addListSelectionListener(
                event -> refreshFolderActions());
        refreshFolderActions();

        UiSupport.quietFocus(this);
        modulesTable.setFocusable(true);
        foldersTable.setFocusable(true);
        reload();
    }

    private ToolBarPanel toolbar() {
        ToolBarPanel bar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        bar.addSpacer().addItem(reloadButton).addItem(applyButton);
        return bar;
    }

    private JPanel sdkTab() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        form.add(field(text("label.jdk", "JDK do projeto:"), jdkSelector,
                text("hint.jdk", "Usada para compilar, executar e alimentar o IntelliSense.")));
        form.add(Box.createVerticalStrut(UiTokens.space(2)));
        form.add(field(text("label.languageLevel", "Nivel de linguagem:"), languageLevel,
                text("hint.languageLevel",
                        "Gravado no pom.xml ou no build.gradle ao aplicar.")));
        form.add(Box.createVerticalGlue());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(new CardPanel(text("card.sdk", "SDK e nivel de linguagem"),
                text("card.sdk.subtitle", "O que o projeto usa para compilar"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD))
                .setContent(form), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel modulesTab() {
        UiSupport.modernReadOnlyTable(modulesTable);
        modulesTable.getColumnModel().getColumn(2).setCellRenderer(UiSupport.monoColumn());
        modulesTable.getColumnModel().getColumn(3).setCellRenderer(UiSupport.monoColumn());
        JScrollPane scroll = UiSupport.plainScroll(new JScrollPane(modulesTable));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(new CardPanel(text("card.modules", "Modulos"),
                text("card.modules.subtitle", "Modulos detectados no build do projeto"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD))
                .setContent(scroll), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel foldersTab() {
        UiSupport.modernReadOnlyTable(foldersTable);

        JComboBox<ProjectLayout.Role> roles = new JComboBox<>(ProjectLayout.Role.values());
        UiSupport.noFocusRing(roles);
        foldersTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(roles));
        foldersTable.getColumnModel().getColumn(1)
                .setCellRenderer(UiSupport.accentColumn(UiTokens.accent()));
        foldersTable.getColumnModel().getColumn(0).setPreferredWidth(UiTokens.scale(320));
        foldersTable.getColumnModel().getColumn(0).setCellRenderer(UiSupport.monoColumn());

        JScrollPane scroll = UiSupport.plainScroll(new JScrollPane(foldersTable));

        ToolBarPanel actions = new ToolBarPanel().setPaintSurface(false)
                .setItemGap(UiTokens.space(1));
        actions.addItem(addFolderButton).addItem(removeFolderButton).addSpacer();

        JPanel content = new JPanel(new BorderLayout(0, UiTokens.space(1)));
        content.setOpaque(false);
        content.add(actions, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(new CardPanel(text("card.sources", "Pastas de codigo"),
                text("card.sources.subtitle",
                        "Marque cada pasta como codigo, teste, recurso ou excluida"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD))
                .setContent(content), BorderLayout.CENTER);
        return wrapper;
    }

    private void refreshFolderActions() {
        removeFolderButton.setEnabled(foldersTable.getSelectedRow() >= 0);
    }

    private void addFolder() {
        Path root = host.projectRoot();
        RunFormUi.chooseDirectory(this,
                text("action.addFolder.title", "Escolha a pasta do projeto"), root, chosen -> {
                    if (chosen == null) {
                        return;
                    }
                    Path folder = chosen.toAbsolutePath().normalize();
                    if (root != null && !folder.startsWith(root.toAbsolutePath().normalize())) {
                        status.setText(text("status.folderOutside",
                                        "A pasta precisa estar dentro do projeto"))
                                .setTone(BadgeLabel.Tone.DANGER);
                        return;
                    }
                    int existing = foldersModel.indexOf(folder);
                    if (existing >= 0) {
                        selectFolderRow(existing);
                        status.setText(text("status.folderAlreadyListed", "Pasta ja listada"))
                                .setTone(BadgeLabel.Tone.WARNING);
                        return;
                    }
                    foldersModel.add(new FolderRole(folder, ProjectLayout.Role.RESOURCE));
                    selectFolderRow(foldersModel.getRowCount() - 1);
                    status.setText(text("status.folderAdded",
                                    "Escolha o papel da pasta e clique em Aplicar"))
                            .setTone(BadgeLabel.Tone.INFO);
                });
    }

    private void removeSelectedFolder() {
        int row = foldersTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        if (foldersTable.isEditing()) {
            foldersTable.getCellEditor().cancelCellEditing();
        }
        foldersModel.remove(row);
        refreshFolderActions();
        status.setText(text("status.folderRemoved",
                        "Pasta removida; clique em Aplicar para confirmar"))
                .setTone(BadgeLabel.Tone.INFO);
    }

    private void selectFolderRow(int row) {
        if (row < 0 || row >= foldersModel.getRowCount()) {
            return;
        }
        foldersTable.setRowSelectionInterval(row, row);
        foldersTable.scrollRectToVisible(foldersTable.getCellRect(row, 0, true));
        refreshFolderActions();
    }

    private static JPanel field(String label, JComponent input, String hint) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel caption = new JLabel(label);
        caption.setFont(UiTokens.fontSmall());
        caption.setForeground(UiTokens.muted());
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        capHeight(input);

        JLabel help = new JLabel(hint);
        help.setFont(UiTokens.fontSmall());
        help.setForeground(UiTokens.muted());
        help.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(caption);
        row.add(Box.createVerticalStrut(Math.max(2, UiTokens.space(1) / 2)));
        row.add(input);
        row.add(Box.createVerticalStrut(Math.max(2, UiTokens.space(1) / 2)));
        row.add(help);
        capHeight(row);
        return row;
    }

    private static void capHeight(JComponent component) {
        component.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
    }

    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        List<JdkInstallation> installations = host.installations();
        DefaultComboBoxModel<JdkItem> jdks = new DefaultComboBoxModel<>();
        installations.stream().filter(JdkInstallation::isUsable)
                .forEach(installation -> jdks.addElement(new JdkItem(installation)));
        jdkSelector.setModel(jdks);

        JdkInstallation inUse = host.projectJdk();
        if (inUse != null) {
            for (int index = 0; index < jdks.getSize(); index++) {
                if (jdks.getElementAt(index).installation.home().equals(inUse.home())) {
                    jdkSelector.setSelectedIndex(index);
                    break;
                }
            }
        }

        languageLevel.setModel(new DefaultComboBoxModel<>(
                languageLevels(installations, host.languageLevel()).toArray(Integer[]::new)));
        Integer current = host.languageLevel();
        if (current != null) {
            languageLevel.setSelectedItem(current);
        }

        modulesModel.setRows(host.modules(), host.projectRoot());
        foldersModel.setRows(host.folders(), host.projectRoot());
        refreshFolderActions();
        status.setText(text("status.ready", "Pronto")).setTone(BadgeLabel.Tone.NEUTRAL);
    }

    private static List<Integer> languageLevels(List<JdkInstallation> installations,
                                                Integer declared) {
        Set<Integer> levels = new LinkedHashSet<>();
        installations.stream().map(JdkInstallation::major).filter(major -> major > 0)
                .sorted().forEach(levels::add);
        if (declared != null && declared > 0) {
            levels.add(declared);
        }
        List<Integer> sorted = new ArrayList<>(levels);
        sorted.sort(null);
        return sorted;
    }

    private void apply() {
        if (foldersTable.isEditing()) {
            foldersTable.getCellEditor().stopCellEditing();
        }
        JdkItem selectedJdk = (JdkItem) jdkSelector.getSelectedItem();
        Integer level = (Integer) languageLevel.getSelectedItem();
        host.apply(selectedJdk == null ? null : selectedJdk.installation, level,
                new ProjectLayoutChange(foldersModel.rows()));
        status.setText(text("status.applied", "Alteracoes aplicadas"))
                .setTone(BadgeLabel.Tone.SUCCESS);
    }

    private record JdkItem(JdkInstallation installation) {
        @Override
        public String toString() {
            return installation.displayName();
        }
    }

    private static final class ModulesTableModel extends AbstractTableModel {

        private final String[] columns = {
                text("column.module", "Modulo"),
                text("column.packaging", "Empacotamento"),
                text("column.path", "Caminho"),
                text("column.output", "Saida")
        };

        private List<JavaModule> rows = List.of();
        private Path root;

        void setRows(List<JavaModule> modules, Path projectRoot) {
            this.rows = modules == null ? List.of() : List.copyOf(modules);
            this.root = projectRoot;
            fireTableDataChanged();
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
            JavaModule module = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> module.name();
                case 1 -> module.packaging();
                case 2 -> relative(root, module.root());
                default -> relative(root, module.outputDir());
            };
        }
    }

    private static final class FoldersTableModel extends AbstractTableModel {

        private final String[] columns = {
                text("column.folder", "Pasta"),
                text("column.role", "Papel")
        };

        private final List<FolderRole> rows = new ArrayList<>();
        private Path root;

        void setRows(List<FolderRole> folders, Path projectRoot) {
            rows.clear();
            if (folders != null) {
                rows.addAll(folders);
            }
            this.root = projectRoot;
            fireTableDataChanged();
        }

        List<FolderRole> rows() {
            return List.copyOf(rows);
        }

        int indexOf(Path folder) {
            for (int index = 0; index < rows.size(); index++) {
                if (Objects.equals(normalized(rows.get(index).folder()), normalized(folder))) {
                    return index;
                }
            }
            return -1;
        }

        void add(FolderRole row) {
            rows.add(row);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int index) {
            if (index < 0 || index >= rows.size()) {
                return;
            }
            rows.remove(index);
            fireTableRowsDeleted(index, index);
        }

        private static Path normalized(Path path) {
            return path == null ? null : path.toAbsolutePath().normalize();
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
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? ProjectLayout.Role.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FolderRole row = rows.get(rowIndex);
            return columnIndex == 0 ? relative(root, row.folder()) : row.role();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 1 || !(value instanceof ProjectLayout.Role role)) {
                return;
            }
            rows.set(rowIndex, new FolderRole(rows.get(rowIndex).folder(), role));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private static String relative(Path root, Path path) {
        if (path == null) {
            return "";
        }
        if (root == null) {
            return path.toString();
        }
        try {
            String relative = root.relativize(path).toString();
            return relative.isBlank() ? "." : relative;
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

}
