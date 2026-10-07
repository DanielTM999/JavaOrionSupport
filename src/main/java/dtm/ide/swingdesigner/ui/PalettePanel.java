package dtm.ide.swingdesigner.ui;

import dtm.ide.swingdesigner.catalog.ComponentCatalog;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.UiSupport;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class PalettePanel extends JPanel {

    static final String PREFIX = "orion-swing-component:";

    private static volatile String dragging;

    record Item(String className, String label, String category, boolean header) {
    }

    private final JTextField search = new JTextField();
    private final DefaultListModel<Item> items = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(items);
    private List<ComponentCatalog.PaletteEntry> entries = List.of();
    private Predicate<String> drawable = name -> false;
    private Consumer<String> activation = className -> { };

    PalettePanel() {
        super(new BorderLayout());
        setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "Buscar ou digitar pacote.Classe");
        search.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2), UiTokens.space(1),
                UiTokens.space(2)));
        top.add(search, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setFixedCellHeight(UiTokens.scale(24));
        list.setCellRenderer(new ItemRenderer());
        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent component) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent component) {
                Item item = list.getSelectedValue();
                if (item == null || item.header()) {
                    return null;
                }
                dragging = item.className();
                return new StringSelection(PREFIX + item.className());
            }

            @Override
            protected void exportDone(JComponent source, Transferable data, int action) {
                dragging = null;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    Item item = list.getSelectedValue();
                    if (item != null && !item.header()) {
                        activation.accept(item.className());
                    }
                }
            }
        });
        list.setToolTipText("Arraste para o desenho ou de duplo clique para adicionar ao container selecionado");
        add(UiSupport.plainScroll(new JScrollPane(list)), BorderLayout.CENTER);
    }

    static String dragging() {
        return dragging;
    }

    static String classOf(String transferred) {
        if (transferred != null && transferred.startsWith(PREFIX)) {
            return transferred.substring(PREFIX.length());
        }
        return null;
    }

    void onActivate(Consumer<String> listener) {
        activation = listener == null ? className -> { } : listener;
    }

    void show(List<ComponentCatalog.PaletteEntry> palette, Predicate<String> isDrawable) {
        entries = palette.stream().filter(entry -> !entry.window()).toList();
        drawable = isDrawable == null ? name -> false : isDrawable;
        filter();
    }

    private void filter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        items.clear();
        List<Item> matched = new ArrayList<>();
        for (ComponentCatalog.PaletteEntry entry : entries) {
            if (query.isEmpty() || entry.label().toLowerCase(Locale.ROOT).contains(query)
                    || entry.className().toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(new Item(entry.className(), entry.label(), entry.category(), false));
            }
        }
        String typed = search.getText().trim();
        if (matched.isEmpty() && typed.contains(".") && drawable.test(typed)) {
            matched.add(new Item(typed, typed.substring(typed.lastIndexOf('.') + 1), "Classe digitada", false));
        }
        String category = null;
        for (Item item : matched) {
            if (!item.category().equals(category)) {
                category = item.category();
                items.addElement(new Item(null, category, category, true));
            }
            items.addElement(item);
        }
    }

    private static final class ItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean focused) {
            Item item = (Item) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(list, item.label(), index,
                    selected && !item.header(), false);
            label.setBorder(BorderFactory.createEmptyBorder(0, item.header() ? UiTokens.space(2)
                    : UiTokens.space(4), 0, UiTokens.space(2)));
            if (item.header()) {
                label.setFont(UiTokens.fontBold());
                label.setForeground(UiTokens.muted());
                label.setIcon(null);
                label.setOpaque(true);
                label.setBackground(UiTokens.overlay(UiTokens.foreground(), 0.04F));
                label.setToolTipText(null);
            } else {
                label.setFont(UiTokens.font());
                label.setIcon(JavaIcons.javaClass(JavaIcons.SMALL));
                label.setOpaque(selected);
                label.setToolTipText(item.className());
            }
            return label;
        }
    }
}
