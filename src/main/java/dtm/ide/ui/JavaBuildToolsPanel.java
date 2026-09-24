package dtm.ide.ui;

import dtm.ide.build.BuildRunConfigurations;
import dtm.ide.build.BuildToolModel;
import dtm.ide.build.MavenPluginGoals;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.menu.popup.ActionMenu;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.skeleton.SkeletonPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.component.tree.CheckState;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeNodeProvider;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.TreeViewMode;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class JavaBuildToolsPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaBuildToolsPanel.class, key, fallback);
    }

    public interface Host {
        BuildToolModel load();

        void execute(BuildToolModel.Node command);

        void cancel();

        default void sync() {
        }

        default void profilesChanged(Set<String> profiles) {
        }

        default Set<String> activeProfiles() {
            return Set.of();
        }

        default List<BuildRunConfigurations.Entry> runConfigurations() {
            return List.of();
        }

        default void saveRunConfiguration(BuildRunConfigurations.Entry entry) {
        }

        default void removeRunConfiguration(String name) {
        }

        default void executeGoals(BuildToolModel.Node context, List<String> goals) {
        }

        default List<MavenPluginGoals.Goal> goalsOf(BuildToolModel.Coordinate coordinate) {
            return List.of();
        }
    }

    private static final String CARD_LOADING = "loading";
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TASKS = "tasks";

    private record LoadedModel(BuildToolModel model, Set<String> activeProfiles) {
    }

    private final Host host;
    private final Executor executor;
    private final TreeView<BuildToolModel.Node> tree = new TreeView<>();
    private final MaskedTextField filter = new MaskedTextField();
    private final BadgeLabel toolBadge = badge("Build Tools", BadgeLabel.Tone.PRIMARY);
    private final BadgeLabel status = badge(text("status.loading", "Carregando"), BadgeLabel.Tone.INFO);
    private final BadgeLabel syncBadge =
            badge(text("status.syncPending", "Sincronizar"), BadgeLabel.Tone.WARNING);
    private final JButton syncButton = labelledButton(text("action.sync", "Sincronizar"),
            text("action.sync.tooltip", "Reler o arquivo de build e atualizar o classpath"),
            JavaIcons.sync(JavaIcons.SMALL));
    private final JButton refreshButton = iconButton(text("action.refresh", "Atualizar"),
            JavaIcons.refresh(JavaIcons.SMALL));
    private final JButton runButton = iconButton(text("action.run", "Executar"),
            JavaIcons.run(JavaIcons.SMALL));
    private final JButton stopButton = iconButton(text("action.stop", "Parar"),
            JavaIcons.stop(JavaIcons.SMALL));
    private final JLabel selection = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final EmptyStatePanel empty = new EmptyStatePanel(
            text("empty.title", "Nenhuma tarefa de build"),
            text("empty.description", "Abra um projeto Maven ou Gradle para ver e rodar as tarefas."))
            .setDashedBorder(false).setArc(UiTokens.radius(UiTokens.Radius.MD));
    private final SkeletonPanel loading = new SkeletonPanel().clearBlocks().addTextLines(9)
            .setAnimated(true).setBlockGap(UiTokens.space(2));

    private volatile BuildToolModel current = new BuildToolModel("Build Tools", List.of(), List.of());
    private final Set<String> activeProfiles = new LinkedHashSet<>();
    private boolean applyingProfiles;
    private long reloadTicket;
    private boolean filterRebuildQueued;

    public JavaBuildToolsPanel(Host host) {
        this(host, command -> Thread.startVirtualThread(command));
    }

    public JavaBuildToolsPanel(Host host, Executor executor) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        this.executor = executor == null
                ? command -> Thread.startVirtualThread(command) : executor;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));
        configureTree();
        configureFilter();
        add(toolbar(), BorderLayout.NORTH);

        ScrollPanel scroll = new ScrollPanel(tree).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(28));
        body.setOpaque(false);
        body.add(loading, CARD_LOADING);
        body.add(empty, CARD_EMPTY);
        body.add(scroll, CARD_TASKS);
        add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, UiTokens.space(1)));
        footer.setOpaque(false);
        JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        badges.setOpaque(false);
        badges.add(status);
        badges.add(syncBadge);
        selection.setFont(UiTokens.fontSmall());
        selection.setForeground(UiTokens.muted());
        selection.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.space(1), 0, 0));
        footer.add(selection, BorderLayout.NORTH);
        footer.add(badges, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        syncBadge.setVisible(false);
        runButton.setEnabled(false);
        stopButton.setEnabled(false);
        UiSupport.quietFocus(this);
        filter.setFocusable(true);
        tree.setFocusable(true);
        reload();
    }

    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        long ticket = ++reloadTicket;
        cards.show(body, CARD_LOADING);
        refreshButton.setEnabled(false);
        status.setText(text("status.loading", "Carregando")).setTone(BadgeLabel.Tone.INFO);
        CompletableFuture.supplyAsync(() -> new LoadedModel(host.load(), host.activeProfiles()), executor)
                .whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
                    if (ticket != reloadTicket) {
                        return;
                    }
                    refreshButton.setEnabled(true);
                    if (error != null) {
                        rebuild();
                        status.setText(text("status.loadFailed", "Falha ao carregar"))
                                .setTone(BadgeLabel.Tone.DANGER);
                        return;
                    }
                    current = value == null || value.model() == null
                            ? new BuildToolModel("Build Tools", List.of(), List.of()) : value.model();
                    reloadActiveProfiles(value == null ? Set.of() : value.activeProfiles());
                    rebuild();
                    status.setText(current.projects().size() + " "
                                    + text("status.projects", "projeto(s)"))
                            .setTone(BadgeLabel.Tone.NEUTRAL);
                }));
    }

    private void reloadActiveProfiles(Set<String> savedProfiles) {
        Set<String> saved = savedProfiles == null ? Set.of() : Set.copyOf(savedProfiles);
        Set<String> stored = new LinkedHashSet<>(saved);
        Set<String> available = new LinkedHashSet<>();
        current.profiles().forEach(profile -> available.add(profile.name()));
        stored.retainAll(available);

        activeProfiles.clear();
        activeProfiles.addAll(stored);
        if (!stored.equals(saved)) {
            host.profilesChanged(Set.copyOf(stored));
        }
    }

    public void setSyncPending(boolean pending) {
        SwingUtilities.invokeLater(() -> {
            if (pending && !syncButton.isEnabled()) {
                return;
            }
            syncBadge.setText(text("status.syncPending", "Sincronizar"))
                    .setTone(BadgeLabel.Tone.WARNING);
            syncBadge.setVisible(pending);
        });
    }

    public void setSyncing(boolean syncing) {
        SwingUtilities.invokeLater(() -> {
            syncButton.setEnabled(!syncing);
            if (syncing) {
                syncBadge.setText(text("status.syncing", "Sincronizando"))
                        .setTone(BadgeLabel.Tone.INFO);
            }
            syncBadge.setVisible(syncing);
        });
    }

    public void finished(String message, boolean successful) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message == null || message.isBlank()
                            ? text("status.finished", "Concluido") : message)
                    .setTone(successful ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.DANGER);
            runButton.setEnabled(selectedExecutable() != null);
            stopButton.setEnabled(false);
        });
    }

    public void warning(String message) {
        SwingUtilities.invokeLater(() -> status.setText(message).setTone(BadgeLabel.Tone.WARNING));
    }

    private ToolBarPanel toolbar() {
        ToolBarPanel toolbar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        filter.setPreferredSize(new Dimension(UiTokens.scale(190), UiTokens.scale(30)));

        syncButton.addActionListener(event -> {
            setSyncPending(false);
            host.sync();
        });
        refreshButton.addActionListener(event -> reload());
        runButton.addActionListener(event -> executeSelected());
        stopButton.addActionListener(event -> {
            host.cancel();
            stopButton.setEnabled(false);
            status.setText(text("status.stopping", "Parando")).setTone(BadgeLabel.Tone.WARNING);
        });

        toolbar.addItem(toolBadge).addItem(filter).addSpacer()
                .addItem(syncButton).addItem(refreshButton)
                .addItem(runButton).addItem(stopButton);
        return toolbar;
    }

    private void configureFilter() {
        filter.setPlaceholder(text("filter.placeholder", "Filtrar tarefas..."));
        filter.putClientProperty("JComponent.roundRect", false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleFilterRebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleFilterRebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleFilterRebuild();
            }
        });
    }

    private void scheduleFilterRebuild() {
        if (filterRebuildQueued) {
            return;
        }
        filterRebuildQueued = true;
        SwingUtilities.invokeLater(() -> {
            filterRebuildQueued = false;
            rebuild();
        });
    }

    private void configureTree() {
        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(28));
        tree.setOpaque(false);
        tree.setMode(TreeViewMode.DISCONTIGUOUS);
        tree.setShowCheckBoxPlaceholder(false);
        tree.setToggleCheckOnRowClick(false);
        tree.setExpandOnDoubleClick(false);
        tree.setPropagateCheckDown(false);
        tree.setPropagateCheckUp(false);
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(1), UiTokens.space(1), UiTokens.space(1)));

        tree.addTreeSelectionListener(event -> {
            runButton.setEnabled(selectedExecutable() != null);
            showSelectionDetail();
        });
        tree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> executeSelected());
        tree.onTreeEvent(EventTreeView.NODE_CHECK, event -> onProfileToggled(event.getNode()));
        tree.setPopupMenuProvider(context -> {
            focusPopupTarget(context.node());
            return contextMenu(context.node());
        });
    }

    private void rebuild() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        toolBadge.setText(current.tool());

        Set<String> expansion = tree.snapshotExpansion();
        TreeNode<BuildToolModel.Node> root = UiSupport.treeNode(null, "root");
        appendProfiles(root, current.profiles(), query);
        for (BuildToolModel.Node project : current.projects()) {
            append(root, project, query);
        }
        tree.setRoot(root);

        if (root.getChildrenList().isEmpty()) {
            empty.setTitle(query.isBlank()
                    ? text("empty.title", "Nenhuma tarefa de build")
                    : text("empty.filtered.title", "Nenhuma tarefa corresponde"));
            empty.setDescription(query.isBlank()
                    ? text("empty.description",
                            "Abra um projeto Maven ou Gradle para ver e rodar as tarefas.")
                    : text("empty.filtered.description",
                            "Tente outro nome de tarefa, dependencia, plugin ou profile."));
            cards.show(body, CARD_EMPTY);
            return;
        }
        cards.show(body, CARD_TASKS);
        if (expansion.isEmpty()) {
            tree.expandToDepth(2);
        } else {
            tree.restoreExpansion(expansion);
        }
    }

    private void appendProfiles(TreeNode<BuildToolModel.Node> root,
                                List<BuildToolModel.Node> profiles, String query) {
        if (profiles.isEmpty()) {
            return;
        }
        TreeNode<BuildToolModel.Node> group = UiSupport.treeNode(null, "group|profiles");
        group.setLabel(text("group.profiles", "Profiles"));
        group.setIcon(JavaIcons.folder(JavaIcons.SMALL));
        group.setForeground(UiTokens.muted());
        group.setFont(UiTokens.fontSmall());

        for (BuildToolModel.Node profile : profiles) {
            if (!query.isEmpty() && !profile.name().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            group.addChild(profileNode(profile));
        }
        if (!group.getChildrenList().isEmpty()) {
            root.addChild(group);
        }
    }

    private TreeNode<BuildToolModel.Node> profileNode(BuildToolModel.Node profile) {
        TreeNode<BuildToolModel.Node> node = UiSupport.treeNode(profile, nodeId(profile));
        node.setLabel(profile.name());
        node.setCheckable(true);
        node.setCheckState(activeProfiles.contains(profile.name())
                ? CheckState.CHECKED : CheckState.UNCHECKED);
        node.setTooltip(text("tooltip.profile",
                "Marque para ativar este profile nos builds e execucoes"));
        return node;
    }

    private boolean append(TreeNode<BuildToolModel.Node> parent, BuildToolModel.Node value,
                           String query) {
        TreeNode<BuildToolModel.Node> node = UiSupport.treeNode(value, nodeId(value));
        node.setLabel(labelOf(value));
        node.setIcon(iconFor(value));
        node.setTooltip(tooltipFor(value));
        if (value.kind() == BuildToolModel.Kind.GROUP) {
            node.setForeground(UiTokens.muted());
            node.setFont(UiTokens.fontSmall());
        } else if (!value.executable() && value.kind() != BuildToolModel.Kind.PROJECT) {
            node.setForeground(UiTokens.muted());
        }

        if (value.kind() == BuildToolModel.Kind.PLUGIN && value.coordinate() != null) {
            makeExpandablePlugin(node, value);
        }

        boolean childMatches = false;
        for (BuildToolModel.Node child : value.children()) {
            childMatches |= append(node, child, query);
        }
        if (value.kind() == BuildToolModel.Kind.PROJECT) {
            childMatches |= appendRunConfigurations(node, value, query);
        }
        boolean matches = query.isEmpty()
                || value.name().toLowerCase(Locale.ROOT).contains(query)
                || childMatches;
        if (matches) {
            parent.addChild(node);
        }
        return matches;
    }

    private static String labelOf(BuildToolModel.Node value) {
        if (value.detail().isBlank()) {
            return value.name();
        }
        return "<html>" + escape(value.name()) + "  <font color='" + hex(UiTokens.muted()) + "'>"
                + escape(value.detail()) + "</font></html>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(java.awt.Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private boolean appendRunConfigurations(TreeNode<BuildToolModel.Node> parent,
                                            BuildToolModel.Node project, String query) {
        List<BuildRunConfigurations.Entry> entries = host.runConfigurations();
        TreeNode<BuildToolModel.Node> group = UiSupport.treeNode(null,
                "group|runconfigs|" + project.name());
        group.setLabel(text("group.runConfigurations", "Run Configurations"));
        group.setIcon(JavaIcons.folder(JavaIcons.SMALL));
        group.setForeground(UiTokens.muted());
        group.setFont(UiTokens.fontSmall());

        boolean matched = false;
        for (BuildRunConfigurations.Entry entry : entries) {
            if (!query.isEmpty() && !entry.display().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            matched = true;
            BuildToolModel.Node value = new BuildToolModel.Node(
                    BuildToolModel.Kind.RUN_CONFIG, entry.name(),
                    "(" + String.join(" ", entry.goals()) + ")", project.module(),
                    entry.goals(), List.of());
            TreeNode<BuildToolModel.Node> node =
                    UiSupport.treeNode(value, "runconfig|" + project.name() + "|" + entry.name());
            node.setLabel(labelOf(value));
            node.setIcon(JavaIcons.run(JavaIcons.SMALL));
            node.setTooltip(text("tooltip.runConfiguration",
                    "Duplo clique executa; botao direito remove"));
            group.addChild(node);
        }
        if (query.isEmpty() || matched) {
            parent.addChild(group);
        }
        return matched;
    }

    private void makeExpandablePlugin(TreeNode<BuildToolModel.Node> node,
                                      BuildToolModel.Node value) {
        node.setLazy(true);
        node.setAlwaysParent(true);
        node.setChildrenProvider(new TreeNodeProvider<>() {
            @Override
            public boolean isAsync() {
                return true;
            }

            @Override
            public List<TreeNode<BuildToolModel.Node>> getChildren(
                    TreeNode<BuildToolModel.Node> parent) {
                List<TreeNode<BuildToolModel.Node>> children = new ArrayList<>();
                for (MavenPluginGoals.Goal goal : host.goalsOf(value.coordinate())) {
                    children.add(goalNode(value, goal));
                }
                if (children.isEmpty()) {
                    children.add(unavailableGoalsNode(value));
                }
                return children;
            }
        });
    }

    private TreeNode<BuildToolModel.Node> goalNode(BuildToolModel.Node plugin,
                                                   MavenPluginGoals.Goal goal) {
        BuildToolModel.Node value = new BuildToolModel.Node(
                BuildToolModel.Kind.PLUGIN_GOAL, goal.invocation(), goal.description(),
                plugin.coordinate(), plugin.module(), List.of(goal.invocation()), List.of());
        TreeNode<BuildToolModel.Node> node = UiSupport.treeNode(value,
                "goal|" + plugin.name() + "|" + goal.name());
        node.setLabel(goal.invocation());
        node.setIcon(JavaIcons.goal(JavaIcons.SMALL));
        node.setTooltip(goal.description().isBlank() ? goal.invocation() : goal.description());
        return node;
    }

    private TreeNode<BuildToolModel.Node> unavailableGoalsNode(BuildToolModel.Node plugin) {
        TreeNode<BuildToolModel.Node> node =
                UiSupport.treeNode(null, "goal|" + plugin.name() + "|unavailable");
        node.setLabel(text("plugin.goalsUnavailable", "Goals indisponiveis - rode o build uma vez"));
        node.setForeground(UiTokens.muted());
        node.setFont(UiTokens.fontSmall());
        node.setSelectable(false);
        return node;
    }

    private static String nodeId(BuildToolModel.Node value) {
        String module = value.module() == null ? "" : value.module().name();
        return value.kind() + "|" + module + "|" + value.name();
    }

    private static Icon iconFor(BuildToolModel.Node value) {
        return switch (value.kind()) {
            case PROJECT -> JavaIcons.module(JavaIcons.SMALL);
            case GROUP -> JavaIcons.folder(JavaIcons.SMALL);
            case COMMAND -> JavaIcons.goal(JavaIcons.SMALL);
            case DEPENDENCY -> JavaIcons.dependency(JavaIcons.SMALL);
            case PLUGIN -> JavaIcons.plugin(JavaIcons.SMALL);
            case REPOSITORY -> JavaIcons.repository(JavaIcons.SMALL);
            case PLUGIN_GOAL -> JavaIcons.goal(JavaIcons.SMALL);
            case RUN_CONFIG -> JavaIcons.run(JavaIcons.SMALL);
            case PROFILE -> JavaIcons.maven(JavaIcons.SMALL);
        };
    }

    private static String tooltipFor(BuildToolModel.Node value) {
        if (value.executable()) {
            return String.join(" ", value.command());
        }
        return value.kind() == BuildToolModel.Kind.PROFILE
                ? I18n.getText(JavaBuildToolsPanel.class, "tooltip.profile",
                        "Marque para ativar este profile nos builds e execucoes")
                : null;
    }

    private void focusPopupTarget(TreeNode<BuildToolModel.Node> node) {
        if (node == null) {
            return;
        }
        List<TreeNode<BuildToolModel.Node>> selected = tree.getSelectedNodes();
        if (selected != null && selected.contains(node)) {
            return;
        }
        tree.selectNodes(List.of(node));
    }

    private JPopupMenu contextMenu(TreeNode<BuildToolModel.Node> node) {
        BuildToolModel.Node value = node == null ? null : node.getData();
        ActionMenu menu = ActionMenu.of(new JMenu());
        boolean any = false;

        if (value != null && value.executable()) {
            menu.item(text("menu.execute", "Executar"), JavaIcons.run(JavaIcons.SMALL),
                    event -> executeSelected());
            any = true;
        }
        if (value != null && value.kind() == BuildToolModel.Kind.RUN_CONFIG) {
            menu.item(text("menu.removeRunConfiguration", "Remover configuracao"),
                    JavaIcons.error(JavaIcons.SMALL), event -> {
                        host.removeRunConfiguration(value.name());
                        rebuild();
                    });
            any = true;
        }

        List<String> goals = selectedGoals();
        if (!goals.isEmpty()) {
            if (any) {
                menu.separator();
            }
            menu.item(text("menu.saveRunConfiguration", "Salvar como configuracao...")
                            + "  (" + String.join(" ", goals) + ")",
                    JavaIcons.create(JavaIcons.SMALL), event -> saveRunConfiguration(goals));
            any = true;
        }
        return any ? menu.getMenu().getPopupMenu() : null;
    }

    private List<String> selectedGoals() {
        List<TreeNode<BuildToolModel.Node>> selected = selectedGoalNodes();
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<String> goals = new ArrayList<>();
        for (TreeNode<BuildToolModel.Node> node : selected) {
            BuildToolModel.Node value = node.getData();
            if (value != null && value.executable()
                    && value.kind() != BuildToolModel.Kind.RUN_CONFIG) {
                value.command().stream().filter(goal -> !goals.contains(goal)).forEach(goals::add);
            }
        }
        return List.copyOf(goals);
    }

    private List<TreeNode<BuildToolModel.Node>> selectedGoalNodes() {
        List<TreeNode<BuildToolModel.Node>> selected = new ArrayList<>(tree.getSelectedNodes());
        selected.removeIf(node -> node.getData() == null || !node.getData().executable()
                || node.getData().kind() == BuildToolModel.Kind.RUN_CONFIG);
        selected.sort(Comparator.comparingInt(node -> tree.getRowForPath(tree.getPathForNode(node))));
        return selected;
    }

    private void saveRunConfiguration(List<String> goals) {
        String suggested = goals.isEmpty() ? "" : goals.getFirst();
        String name = JOptionPane.showInputDialog(this,
                text("dialog.runConfigurationName", "Nome da configuracao:"), suggested);
        if (name == null || name.isBlank()) {
            return;
        }
        host.saveRunConfiguration(new BuildRunConfigurations.Entry(name.trim(), goals));
        rebuild();
    }

    private void onProfileToggled(TreeNode<BuildToolModel.Node> node) {
        if (applyingProfiles || node == null || node.getData() == null) {
            return;
        }
        BuildToolModel.Node value = node.getData();
        if (value.kind() != BuildToolModel.Kind.PROFILE) {
            return;
        }
        if (node.getCheckState() == CheckState.CHECKED) {
            activeProfiles.add(value.name());
        } else {
            activeProfiles.remove(value.name());
        }
        Set<String> snapshot = Set.copyOf(activeProfiles);
        host.profilesChanged(snapshot);
        status.setText(snapshot.isEmpty()
                        ? text("status.noProfiles", "Sem profiles ativos")
                        : String.join(", ", snapshot))
                .setTone(snapshot.isEmpty() ? BadgeLabel.Tone.NEUTRAL : BadgeLabel.Tone.PRIMARY);
    }

    private void showSelectionDetail() {
        List<String> goals = selectedGoals();
        if (goals.size() > 1) {
            String summary = String.join(", ", goals);
            selection.setText(summary);
            selection.setToolTipText(summary);
            return;
        }
        TreeNode<BuildToolModel.Node> selected = tree.getSelectedNode();
        BuildToolModel.Node value = selected == null ? null : selected.getData();
        if (value == null) {
            selection.setText(" ");
            return;
        }
        StringBuilder detail = new StringBuilder(value.name());
        if (!value.detail().isBlank()) {
            detail.append("  ").append(value.detail());
        }
        if (value.module() != null && value.kind() == BuildToolModel.Kind.PROJECT) {
            detail.append("  -  ").append(value.module().root());
        }
        selection.setText(detail.toString());
        selection.setToolTipText(detail.toString());
    }

    private BuildToolModel.Node selectedExecutable() {
        TreeNode<BuildToolModel.Node> selected = tree.getSelectedNode();
        BuildToolModel.Node value = selected == null ? null : selected.getData();
        return value != null && value.executable() ? value : null;
    }

    private void executeSelected() {
        BuildToolModel.Node node = selectedExecutable();
        if (node == null) {
            return;
        }
        List<TreeNode<BuildToolModel.Node>> selected = selectedGoalNodes();
        List<String> goals = selectedGoals();
        if (selected.size() > 1 && !goals.isEmpty()) {
            node = selected.getFirst().getData();
        }
        String executionName = selected.size() > 1 ? String.join(" ", goals) : node.name();
        status.setText(text("status.running", "Executando") + " " + executionName)
                .setTone(BadgeLabel.Tone.INFO);
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        if (selected.size() > 1) {
            host.executeGoals(node, goals);
        } else if (node.command().size() > 1 || node.kind() == BuildToolModel.Kind.RUN_CONFIG
                || node.kind() == BuildToolModel.Kind.PLUGIN_GOAL) {
            host.executeGoals(node, node.command());
        } else {
            host.execute(node);
        }
    }

    private static BadgeLabel badge(String text, BadgeLabel.Tone tone) {
        return new BadgeLabel(text, tone).setStyle(BadgeLabel.Style.SOFT)
                .setShowDot(true).setSize(BadgeLabel.Size.SM);
    }

    private static JButton iconButton(String tooltip, Icon icon) {
        JButton button = new JButton();
        if (icon == null) {
            button.setText(tooltip);
        } else {
            button.setIcon(icon);
        }
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        return button;
    }

    private static JButton labelledButton(String label, String tooltip, Icon icon) {
        JButton button = new JButton(label);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setToolTipText(tooltip);
        button.setFont(UiTokens.fontSmall());
        button.setFocusPainted(false);
        return button;
    }

}
