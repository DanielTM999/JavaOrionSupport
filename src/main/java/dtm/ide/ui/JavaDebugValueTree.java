package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.skeleton.SkeletonPanel;
import dtm.stools.configs.UiTokens;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class JavaDebugValueTree extends JPanel {

    @FunctionalInterface
    public interface ChildrenProvider {
        List<JavaDebugSnapshot.Variable> load(int reference) throws Exception;
    }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final EmptyStatePanel empty = new EmptyStatePanel(
            "No variables", "Pause on a line with local values or select another stack frame.")
            .setDashedBorder(false);
    private final SkeletonPanel loading = new SkeletonPanel().clearBlocks().addTextLines(7)
            .setAnimated(true).setBlockGap(9);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Executor executor;
    private volatile ChildrenProvider childrenProvider = reference -> List.of();

    public JavaDebugValueTree() {
        this(command -> Thread.startVirtualThread(command));
    }

    public JavaDebugValueTree(Executor executor) {
        super(new BorderLayout());
        this.executor = executor == null
                ? command -> Thread.startVirtualThread(command) : executor;
        setBackground(JavaDebugTheme.content());
        styleTree();
        ScrollPanel scroll = new ScrollPanel(tree).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(22);
        scroll.getViewport().setBackground(JavaDebugTheme.content());
        content.setOpaque(false);
        content.add(scroll, "tree");
        content.add(empty, "empty");
        content.add(loading, "loading");
        add(content, BorderLayout.CENTER);
        cards.show(content, "empty");
    }

    public void bindChildrenProvider(ChildrenProvider provider) {
        childrenProvider = provider == null ? reference -> List.of() : provider;
    }

    public void setScopes(List<JavaDebugSnapshot.Scope> scopes) {
        runOnEdt(() -> {
            root.removeAllChildren();
            if (scopes != null) {
                for (JavaDebugSnapshot.Scope scope : scopes) {
                    ValueNode node = ValueNode.scope(scope);
                    visible(scope.variables()).forEach(value -> node.add(variableNode(value)));
                    root.add(node);
                }
            }
            refreshTree("No variables in the current frame", true);
        });
    }

    public void setValue(JavaDebugSnapshot.Variable value) {
        runOnEdt(() -> {
            root.removeAllChildren();
            if (value != null && !syntheticNoFields(value)) {
                root.add(variableNode(value));
            }
            refreshTree("No value", true);
        });
    }

    public void setVariables(List<JavaDebugSnapshot.Variable> values, String emptyText) {
        runOnEdt(() -> {
            root.removeAllChildren();
            visible(values).forEach(value -> root.add(variableNode(value)));
            refreshTree(emptyText, false);
        });
    }

    public void setLoading() {
        runOnEdt(() -> {
            root.removeAllChildren();
            model.reload();
            cards.show(content, "loading");
        });
    }

    public void clear() {
        setVariables(List.of(), "No variables in the current frame");
    }

    public JComponent component() {
        return this;
    }

    private void styleTree() {
        tree.setBackground(JavaDebugTheme.content());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(30));
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(2), UiTokens.space(1), UiTokens.space(2)));
        tree.setToggleClickCount(1);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeWillExpandListener(new Loader());
        installCopyActions();
    }

    private void installCopyActions() {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyDebugValue");
        tree.getActionMap().put("copyDebugValue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                copySelected(false);
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showMenu(event);
            }
        });
    }

    private void showMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof ValueNode node)
                || node.placeholder) {
            return;
        }
        tree.setSelectionPath(path);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyValue = new JMenuItem("Copy value");
        copyValue.setEnabled(node.variable != null);
        copyValue.addActionListener(action -> copySelected(false));
        JMenuItem copyExpression = new JMenuItem("Copy expression");
        copyExpression.setEnabled(node.variable != null
                && node.variable.evaluateName() != null
                && !node.variable.evaluateName().isBlank());
        copyExpression.addActionListener(action -> copySelected(true));
        menu.add(copyValue);
        menu.add(copyExpression);
        menu.show(tree, event.getX(), event.getY());
    }

    private void copySelected(boolean expression) {
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof ValueNode node) || node.variable == null) {
            return;
        }
        String value = expression ? node.variable.evaluateName() : node.variable.value();
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value), null);
        } catch (RuntimeException ignored) {
        }
    }

    private ValueNode variableNode(JavaDebugSnapshot.Variable value) {
        ValueNode node = ValueNode.variable(value);
        if (value.expandable()) {
            node.add(ValueNode.placeholder());
        }
        return node;
    }

    private void load(ValueNode node, TreePath path) {
        if (node.loaded || node.reference <= 0) {
            return;
        }
        node.loaded = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return visible(childrenProvider.load(node.reference));
            } catch (Exception error) {
                return List.<JavaDebugSnapshot.Variable>of();
            }
        }, executor).thenAccept(values -> SwingUtilities.invokeLater(() -> {
            node.removeAllChildren();
            values.forEach(value -> node.add(variableNode(value)));
            if (values.isEmpty()) {
                node.add(ValueNode.empty());
            }
            model.nodeStructureChanged(node);
            tree.expandPath(path);
        }));
    }

    private void refreshTree(String emptyText, boolean expandFirst) {
        model.reload();
        if (root.getChildCount() == 0) {
            empty.setTitle(emptyText == null || emptyText.isBlank() ? "No value" : emptyText);
            empty.setDescription("The selected object or frame does not expose child values.");
            cards.show(content, "empty");
            return;
        }
        cards.show(content, "tree");
        if (expandFirst) {
            tree.expandPath(new TreePath(new Object[]{root, root.getFirstChild()}));
        }
    }

    private static List<JavaDebugSnapshot.Variable> visible(
            List<JavaDebugSnapshot.Variable> variables) {
        return variables == null ? List.of()
                : variables.stream().filter(value -> value != null && !syntheticNoFields(value)).toList();
    }

    private static boolean syntheticNoFields(JavaDebugSnapshot.Variable value) {
        String name = value.name() == null ? "" : value.name().trim().toLowerCase(java.util.Locale.ROOT);
        String content = value.value() == null ? "" : value.value().trim().toLowerCase(java.util.Locale.ROOT);
        return name.equals("class has no fields") || content.equals("class has no fields");
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private final class Loader implements TreeWillExpandListener {
        @Override
        public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            if (event.getPath().getLastPathComponent() instanceof ValueNode node) {
                load(node, event.getPath());
            }
        }

        @Override
        public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        }
    }

    private static final class Renderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel value = new JLabel();
        private final BadgeLabel type = new BadgeLabel("", BadgeLabel.Tone.NEUTRAL)
                .setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.SM);

        Renderer() {
            super(new BorderLayout(UiTokens.space(3), 0));
            setOpaque(true);
            JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), 0));
            identity.setOpaque(false);
            icon.setPreferredSize(new java.awt.Dimension(UiTokens.scale(16), UiTokens.scale(16)));
            name.setFont(UiTokens.fontSmall().deriveFont(Font.BOLD));
            identity.add(icon);
            identity.add(name);
            value.setFont(UiTokens.fontMono());
            add(identity, BorderLayout.WEST);
            add(value, BorderLayout.CENTER);
            add(type, BorderLayout.EAST);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                       boolean expanded, boolean leaf, int row,
                                                       boolean focus) {
            setBorder(BorderFactory.createEmptyBorder(0, UiTokens.space(1), 0, UiTokens.space(2)));
            setBackground(selected ? JavaDebugTheme.selection()
                    : row % 2 == 0 ? JavaDebugTheme.content() : JavaDebugTheme.stripe());
            name.setText("");
            this.value.setText("");
            type.setVisible(false);
            if (!(value instanceof ValueNode node)) {
                icon.setIcon(null);
                return this;
            }
            if (node.placeholder) {
                icon.setIcon(null);
                name.setText(node.empty ? "No fields" : "Loading...");
                name.setFont(UiTokens.fontSmall());
                name.setForeground(JavaDebugTheme.muted());
                return this;
            }
            if (node.scope) {
                icon.setIcon(JavaDebugValueIcon.scope());
                name.setText(node.name);
                name.setFont(UiTokens.fontSmall().deriveFont(Font.BOLD));
                name.setForeground(JavaDebugTheme.accent());
                setBackground(selected ? JavaDebugTheme.selection()
                        : UiTokens.overlay(JavaDebugTheme.accent(), 0.08F));
                setToolTipText(node.name);
                return this;
            }
            JavaDebugSnapshot.Variable variable = node.variable;
            icon.setIcon(JavaDebugValueIcon.forValue(variable));
            name.setText(node.name);
            name.setFont(UiTokens.fontSmall().deriveFont(Font.BOLD));
            name.setForeground(JavaDebugTheme.name());
            this.value.setText(node.displayValue == null || node.displayValue.isBlank()
                    ? "" : node.displayValue);
            this.value.setForeground(JavaDebugTheme.valueFor(node.displayValue, node.type));
            if (node.type != null && !node.type.isBlank()) {
                type.setText(shortType(node.type));
                type.setTone(typeTone(node.type));
                type.setVisible(true);
            }
            setToolTipText(node.tooltip());
            return this;
        }

        private static String shortType(String type) {
            String shortened = type.replace("java.lang.", "")
                    .replace("java.util.", "");
            return shortened.length() <= 30 ? shortened : shortened.substring(0, 27) + "...";
        }

        private static BadgeLabel.Tone typeTone(String type) {
            String normalized = type.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("string") || normalized.contains("char")) {
                return BadgeLabel.Tone.SUCCESS;
            }
            if (normalized.contains("boolean")) {
                return BadgeLabel.Tone.WARNING;
            }
            if (normalized.matches(".*(int|long|double|float|short|byte|decimal).*")) {
                return BadgeLabel.Tone.INFO;
            }
            return BadgeLabel.Tone.NEUTRAL;
        }
    }

    private static final class ValueNode extends DefaultMutableTreeNode {
        private final String name;
        private final String displayValue;
        private final String type;
        private final int reference;
        private final boolean scope;
        private final boolean placeholder;
        private final boolean empty;
        private final JavaDebugSnapshot.Variable variable;
        private boolean loaded;

        private ValueNode(String name, String displayValue, String type, int reference,
                          boolean scope, boolean placeholder, boolean empty,
                          JavaDebugSnapshot.Variable variable, boolean loaded) {
            super(name);
            this.name = name;
            this.displayValue = displayValue;
            this.type = type;
            this.reference = reference;
            this.scope = scope;
            this.placeholder = placeholder;
            this.empty = empty;
            this.variable = variable;
            this.loaded = loaded;
        }

        static ValueNode scope(JavaDebugSnapshot.Scope scope) {
            return new ValueNode(scope.name(), "", "", scope.variablesReference(), true,
                    false, false, null, true);
        }

        static ValueNode variable(JavaDebugSnapshot.Variable value) {
            return new ValueNode(value.name(), value.value(), value.type(),
                    value.variablesReference(), false, false, false, value, !value.expandable());
        }

        static ValueNode placeholder() {
            return new ValueNode("", "", "", 0, false, true, false, null, true);
        }

        static ValueNode empty() {
            return new ValueNode("", "", "", 0, false, true, true, null, true);
        }

        String tooltip() {
            String suffix = type == null || type.isBlank() ? "" : "  " + type;
            return name + (displayValue == null || displayValue.isBlank() ? "" : " = " + displayValue)
                    + suffix;
        }
    }
}
