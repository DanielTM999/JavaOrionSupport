package dtm.ide.ui;

import dtm.ide.todo.TodoItem;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.loading.LoadingPanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.TreeViewMode;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class JavaTodoPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaTodoPanel.class, key, fallback);
    }

    public interface Host {

        void rescan();

        void open(TodoItem item);
    }

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_ITEMS = "items";

    private final Host host;
    private final TreeView<Object> tree = new TreeView<>();
    private final MaskedTextField filter = new MaskedTextField();
    private final BadgeLabel status =
            new BadgeLabel(text("status.loading", "Varrendo"), BadgeLabel.Tone.INFO)
                    .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);
    private final JButton refreshButton = new JButton(text("action.refresh", "Revarrer"),
            JavaIcons.sync(JavaIcons.SMALL));
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final EmptyStatePanel empty = new EmptyStatePanel(
            text("empty.title", "Nenhum marcador"),
            text("empty.description", "Escreva um comentario com TODO ou FIXME para ve-lo aqui."))
            .setDashedBorder(false).setArc(UiTokens.radius(UiTokens.Radius.MD));
    private final LoadingPanel loading = new LoadingPanel();

    private List<TodoItem> items = List.of();
    private boolean scanning = true;
    private Path projectRoot;

    public JavaTodoPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        configureTree();
        configureFilter();

        ScrollPanel scroll = new ScrollPanel(tree).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(28));

        body.setOpaque(false);
        body.add(empty, CARD_EMPTY);
        body.add(scroll, CARD_ITEMS);

        loading.setContent(body);
        loading.setShowMessage(true).setIndeterminate()
                .setMessage(text("status.firstScan", "Procurando marcadores no projeto..."))
                .start();

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);

        add(toolbar(), BorderLayout.NORTH);
        add(loading, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        refreshButton.addActionListener(event -> {
            beginScan();
            host.rescan();
        });
        UiSupport.quietFocus(this);
        filter.setFocusable(true);
        tree.setFocusable(true);
    }

    public void beginScan() {
        SwingUtilities.invokeLater(() -> {
            scanning = true;
            status.setText(text("status.loading", "Varrendo")).setTone(BadgeLabel.Tone.INFO);
            loading.setMessage(text("status.firstScan", "Procurando marcadores no projeto..."))
                    .setIndeterminate().start();
        });
    }

    public void setItems(List<TodoItem> found, Path root) {
        SwingUtilities.invokeLater(() -> {
            this.projectRoot = root;
            this.items = found == null ? List.of() : List.copyOf(found);
            this.scanning = false;
            loading.stop();
            rebuild();
        });
    }

    public void setProjectRoot(Path root) {
        this.projectRoot = root;
    }

    private ToolBarPanel toolbar() {
        ToolBarPanel toolbar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        filter.setPreferredSize(new Dimension(UiTokens.scale(190), UiTokens.scale(30)));
        toolbar.addItem(filter).addSpacer().addItem(refreshButton);
        return toolbar;
    }

    private void configureFilter() {
        filter.setPlaceholder(text("filter.placeholder", "Filtrar marcadores..."));
        filter.putClientProperty("JComponent.roundRect", false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                rebuild();
            }
        });
    }

    private void configureTree() {
        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(26));
        tree.setOpaque(false);
        tree.setMode(TreeViewMode.DISCONTIGUOUS);
        tree.setExpandOnDoubleClick(false);
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(1), UiTokens.space(1), UiTokens.space(1)));
        tree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelected());
        tree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelected());
    }

    private void rebuild() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        List<TodoItem> visible = items.stream().filter(item -> matches(item, query)).toList();

        if (visible.isEmpty()) {
            empty.setTitle(query.isBlank()
                    ? text("empty.title", "Nenhum marcador")
                    : text("empty.filtered.title", "Nenhum marcador corresponde"));
            empty.setDescription(query.isBlank()
                    ? text("empty.description",
                            "Escreva um comentario com TODO ou FIXME para ve-lo aqui.")
                    : text("empty.filtered.description", "Tente outro termo."));
            cards.show(body, CARD_EMPTY);
            updateStatus(0);
            return;
        }

        Map<Path, List<TodoItem>> grouped = new LinkedHashMap<>();
        for (TodoItem item : visible) {
            grouped.computeIfAbsent(item.file(), ignored -> new ArrayList<>()).add(item);
        }

        java.util.Set<String> expansion = tree.snapshotExpansion();
        TreeNode<Object> root = UiSupport.treeNode(null, "root");
        grouped.forEach((file, fileItems) -> {
            TreeNode<Object> fileNode = UiSupport.treeNode(file, "file|" + file);
            fileNode.setLabel(displayPath(file) + "  (" + fileItems.size() + ")");
            fileNode.setIcon(JavaIcons.java(JavaIcons.SMALL));
            fileNode.setTooltip(file.toString());
            for (TodoItem item : fileItems) {
                TreeNode<Object> itemNode =
                        UiSupport.treeNode(item, "item|" + file + "|" + item.line());
                itemNode.setLabel(label(item));
                itemNode.setIcon(JavaIcons.todo(JavaIcons.SMALL));
                itemNode.setTooltip(item.display());
                fileNode.addChild(itemNode);
            }
            root.addChild(fileNode);
        });
        tree.setRoot(root);
        cards.show(body, CARD_ITEMS);
        if (expansion.isEmpty()) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
        updateStatus(visible.size());
    }

    private static String label(TodoItem item) {
        StringBuilder label = new StringBuilder(item.display());
        label.append("   ").append(item.line() + 1);
        if (!item.context().isBlank()) {
            label.append("  ").append(item.context());
        }
        return label.toString();
    }

    private String displayPath(Path file) {
        Path root = projectRoot;
        if (root == null) {
            return file.getFileName().toString();
        }
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private static boolean matches(TodoItem item, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return item.display().toLowerCase(Locale.ROOT).contains(query)
                || item.context().toLowerCase(Locale.ROOT).contains(query)
                || item.file().toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void updateStatus(int count) {
        if (scanning) {
            status.setText(text("status.loading", "Varrendo")).setTone(BadgeLabel.Tone.INFO);
            return;
        }
        status.setText(count + " " + text("status.items", "marcador(es)"))
                .setTone(count == 0 ? BadgeLabel.Tone.NEUTRAL : BadgeLabel.Tone.WARNING);
    }

    private void openSelected() {
        TreeNode<Object> selected = tree.getSelectedNode();
        if (selected != null && selected.getData() instanceof TodoItem item) {
            host.open(item);
        }
    }
}
