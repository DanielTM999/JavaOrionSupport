package dtm.ide.ui;

import dtm.ide.coverage.CoverageDisplay;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.FileCoverage;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class JavaCoveragePanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaCoveragePanel.class, key, fallback);
    }

    public record Row(String qualifiedName, Path file, int coveredLines, int totalLines,
                      int coveredBranches, int totalBranches) {

        public double linePercentage() {
            return totalLines <= 0 ? 0d : (coveredLines * 100d) / totalLines;
        }

        public double branchPercentage() {
            return totalBranches <= 0 ? 0d : (coveredBranches * 100d) / totalBranches;
        }

        public String simpleName() {
            int dot = qualifiedName.lastIndexOf('.');
            return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
        }

        public String packageName() {
            int dot = qualifiedName.lastIndexOf('.');
            return dot < 0 ? "" : qualifiedName.substring(0, dot);
        }
    }

    public static List<Row> rowsOf(CoverageReport report) {
        List<Row> rows = new ArrayList<>();
        if (report == null) {
            return rows;
        }
        for (Map.Entry<String, FileCoverage> entry : report.classes().entrySet()) {
            FileCoverage coverage = entry.getValue();
            if (coverage == null || coverage.isEmpty()) {
                continue;
            }
            rows.add(new Row(entry.getKey(), coverage.file(),
                    coverage.coveredLines(), coverage.totalLines(),
                    coverage.coveredBranches(), coverage.totalBranches()));
        }
        rows.sort(Comparator.comparingDouble(Row::linePercentage)
                .thenComparing(Row::qualifiedName));
        return rows;
    }

    static Color colorFor(double percentage) {
        if (percentage >= 80d) {
            return UiTokens.success();
        }
        return percentage >= 50d ? UiTokens.warning() : UiTokens.danger();
    }

    private final transient List<Row> rows;

    public JavaCoveragePanel(CoverageReport report, Consumer<Row> onOpen) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.rows = rowsOf(report);
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        add(header(report), BorderLayout.NORTH);

        JTable table = new JTable(new CoverageTableModel(rows));
        table.setBackground(UiTokens.surface());
        table.setForeground(UiTokens.foreground());
        table.setGridColor(UiTokens.background());
        table.setRowHeight(UiTokens.scale(24));
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CoverageCellRenderer(rows));
        table.getColumnModel().getColumn(0).setPreferredWidth(UiTokens.scale(240));
        table.getColumnModel().getColumn(1).setPreferredWidth(UiTokens.scale(200));
        table.getColumnModel().getColumn(2).setPreferredWidth(UiTokens.scale(90));
        table.getColumnModel().getColumn(3).setPreferredWidth(UiTokens.scale(90));

        if (onOpen != null) {
            table.addMouseListener(new MouseInputAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    int index = table.rowAtPoint(event.getPoint());
                    if (event.getClickCount() == 2 && index >= 0 && index < rows.size()) {
                        onOpen.accept(rows.get(index));
                    }
                }
            });
        }

        ScrollPanel scroll = new ScrollPanel(table).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(24));
        CardPanel card = new CardPanel(text("card.classes", "Classes"),
                text("card.classes.subtitle",
                        "Menor cobertura primeiro. Duplo clique abre o arquivo."))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD))
                .setContent(scroll);
        add(card, BorderLayout.CENTER);
        setPreferredSize(new Dimension(UiTokens.scale(720), UiTokens.scale(420)));
    }

    private JPanel header(CoverageReport report) {
        CoverageReport.Totals totals = report == null
                ? CoverageReport.Totals.EMPTY : report.totals();
        JPanel panel = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        panel.setOpaque(false);

        JLabel percentage = new JLabel(CoverageDisplay.percent(totals.linePercentage()));
        percentage.setForeground(colorFor(totals.linePercentage()));
        percentage.setFont(UiTokens.font().deriveFont(java.awt.Font.BOLD, UiTokens.scale(22)));
        panel.add(percentage, BorderLayout.WEST);

        StringBuilder detail = new StringBuilder();
        detail.append(totals.coveredLines()).append('/').append(totals.totalLines())
                .append(' ').append(text("header.lines", "linhas"));
        if (totals.totalBranches() > 0) {
            detail.append("   ").append(text("header.branches", "branches")).append(' ')
                    .append(CoverageDisplay.percent(totals.branchPercentage()))
                    .append(" (").append(totals.coveredBranches()).append('/')
                    .append(totals.totalBranches()).append(')');
        }
        JLabel summary = new JLabel(detail.toString());
        summary.setForeground(UiTokens.muted());
        summary.setFont(UiTokens.fontSmall());
        panel.add(summary, BorderLayout.CENTER);
        return panel;
    }

    private static final class CoverageTableModel extends AbstractTableModel {

        private final transient List<Row> rows;

        private CoverageTableModel(List<Row> rows) {
            this.rows = rows;
        }

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
                case 0 -> text("column.class", "Classe");
                case 1 -> text("column.package", "Pacote");
                case 2 -> text("column.lines", "Linhas");
                default -> text("column.branches", "Branches");
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.simpleName();
                case 1 -> row.packageName();
                case 2 -> CoverageDisplay.percent(row.linePercentage())
                        + "  (" + row.coveredLines() + "/" + row.totalLines() + ")";
                default -> row.totalBranches() == 0
                        ? "-"
                        : CoverageDisplay.percent(row.branchPercentage())
                                + "  (" + row.coveredBranches() + "/" + row.totalBranches() + ")";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }

    private static final class CoverageCellRenderer extends DefaultTableCellRenderer {

        private final transient List<Row> rows;

        private CoverageCellRenderer(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component component = super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            if (selected || row < 0 || row >= rows.size()) {
                return component;
            }
            component.setForeground(column == 2
                    ? colorFor(rows.get(row).linePercentage())
                    : UiTokens.foreground());
            return component;
        }
    }
}
