package dtm.ide.run.form;

import dtm.stools.configs.UiTokens;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout responsivo dos campos de uma secao.
 *
 * <p>Distribui as celulas em duas colunas quando ha largura suficiente e as empilha em uma
 * unica coluna quando o painel fica estreito, sem nunca produzir rolagem horizontal. Uma
 * celula pode ocupar a linha inteira declarando o span cheio.</p>
 */
final class FormFlowLayout implements LayoutManager {

    /** Propriedade de cliente que marca quantas colunas a celula ocupa. */
    static final String SPAN = "dtm.ide.run.form.span";

    /** Abaixo desta largura util o formulario passa a empilhar os campos. */
    static final int TWO_COLUMN_THRESHOLD = 520;

    private final int columns;

    FormFlowLayout() {
        this(2);
    }

    FormFlowLayout(int columns) {
        this.columns = Math.max(1, columns);
    }

    static void setSpan(JComponent component, int span) {
        component.putClientProperty(SPAN, span);
    }

    private int spanOf(Component component, int available) {
        int span = component instanceof JComponent widget
                && widget.getClientProperty(SPAN) instanceof Integer value ? value : 1;
        return Math.min(Math.max(1, span), available);
    }

    private int columnsFor(Container parent, int width) {
        if (columns == 1) {
            return 1;
        }
        return width >= UiTokens.scale(TWO_COLUMN_THRESHOLD) ? columns : 1;
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
        // O span e lido da propriedade de cliente da celula.
    }

    @Override
    public void removeLayoutComponent(Component component) {
        // Nada a liberar.
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            if (available <= 0) {
                available = UiTokens.scale(TWO_COLUMN_THRESHOLD);
            }
            List<Row> rows = rows(parent, available);
            int gap = UiTokens.space(2);
            int height = insets.top + insets.bottom;
            for (int index = 0; index < rows.size(); index++) {
                height += rows.get(index).height();
                if (index < rows.size() - 1) {
                    height += gap;
                }
            }
            return new Dimension(available + insets.left + insets.right, height);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets insets = parent.getInsets();
        int width = UiTokens.scale(220) + insets.left + insets.right;
        return new Dimension(width, preferredLayoutSize(parent).height);
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            if (available <= 0) {
                return;
            }
            int gap = UiTokens.space(2);
            int columnCount = columnsFor(parent, available);
            int columnWidth = (available - gap * (columnCount - 1)) / columnCount;
            int y = insets.top;

            for (Row row : rows(parent, available)) {
                int x = insets.left;
                for (Cell cell : row.cells()) {
                    int width = cell.span() == columnCount
                            ? available
                            : columnWidth * cell.span() + gap * (cell.span() - 1);
                    cell.component().setBounds(x, y, width, row.height());
                    x += width + gap;
                }
                y += row.height() + gap;
            }
        }
    }

    private List<Row> rows(Container parent, int available) {
        int columnCount = columnsFor(parent, available);
        List<Row> rows = new ArrayList<>();
        List<Cell> current = new ArrayList<>();
        int used = 0;

        for (Component component : parent.getComponents()) {
            if (!component.isVisible()) {
                continue;
            }
            int span = Math.min(spanOf(component, columns), columnCount);
            if (used + span > columnCount && !current.isEmpty()) {
                rows.add(Row.of(current));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(new Cell(component, span));
            used += span;
            if (used >= columnCount) {
                rows.add(Row.of(current));
                current = new ArrayList<>();
                used = 0;
            }
        }
        if (!current.isEmpty()) {
            rows.add(Row.of(current));
        }
        return rows;
    }

    private record Cell(Component component, int span) {
    }

    private record Row(List<Cell> cells, int height) {

        static Row of(List<Cell> cells) {
            int height = cells.stream()
                    .mapToInt(cell -> cell.component().getPreferredSize().height)
                    .max()
                    .orElse(0);
            return new Row(List.copyOf(cells), height);
        }
    }
}
