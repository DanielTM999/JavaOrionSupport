package dtm.ide.ui;

import dtm.ide.build.BuildDiagnostic;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.TreeViewMode;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Navigable list of build and language-server diagnostics. */
public final class JavaProblemsPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaProblemsPanel.class, key, fallback);
    }

    public interface Host {
        void open(BuildDiagnostic problem);

        void clearBuildProblems();
    }

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_PROBLEMS = "problems";

    private final Host host;
    private final TreeView<BuildDiagnostic> tree = new TreeView<>();
    private final MaskedTextField filter = new MaskedTextField();
    private final JToggleButton errors = toggle(text("filter.errors", "Erros"), true);
    private final JToggleButton warnings = toggle(text("filter.warnings", "Avisos"), true);
    private final JToggleButton information = toggle(text("filter.information", "Info"), true);
    private final JButton clear = new JButton(text("action.clear", "Limpar"),
            JavaIcons.error(JavaIcons.SMALL));
    private final BadgeLabel status = new BadgeLabel("0", BadgeLabel.Tone.NEUTRAL)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final EmptyStatePanel empty = new EmptyStatePanel(
            text("empty.title", "Nenhum problema"),
            text("empty.description", "Erros do Java, Maven e Gradle aparecerao aqui."))
            .setDashedBorder(false).setArc(UiTokens.radius(UiTokens.Radius.MD));

    private List<BuildDiagnostic> buildProblems = List.of();
    private List<BuildDiagnostic> liveProblems = List.of();
    private Path projectRoot;
    private boolean rebuildQueued;

    public JavaProblemsPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        configureTree();
        configureActions();
        body.setOpaque(false);
        body.add(empty, CARD_EMPTY);
        body.add(new ScrollPanel(tree).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(28)), CARD_PROBLEMS);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);
        add(toolbar(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiSupport.quietFocus(this);
    }

    public void setProblems(List<BuildDiagnostic> build, List<BuildDiagnostic> live, Path root) {
        SwingUtilities.invokeLater(() -> {
            buildProblems = build == null ? List.of() : List.copyOf(build);
            liveProblems = live == null ? List.of() : List.copyOf(live);
            projectRoot = root;
            updateToggleLabels();
            rebuild();
        });
    }

    private ToolBarPanel toolbar() {
        filter.setPreferredSize(new Dimension(UiTokens.scale(190), UiTokens.scale(30)));
        filter.setPlaceholder(text("filter.placeholder", "Filtrar problemas..."));
        filter.putClientProperty("JComponent.roundRect", false);
        ToolBarPanel toolbar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(1));
        toolbar.addItem(filter).addItem(errors).addItem(warnings).addItem(information)
                .addSpacer().addItem(clear);
        return toolbar;
    }

    private void configureActions() {
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleRebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleRebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleRebuild();
            }
        });
        errors.addActionListener(event -> rebuild());
        warnings.addActionListener(event -> rebuild());
        information.addActionListener(event -> rebuild());
        clear.addActionListener(event -> host.clearBuildProblems());
    }

    private void configureTree() {
        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(28));
        tree.setOpaque(false);
        tree.setMode(TreeViewMode.SINGLE);
        tree.setExpandOnDoubleClick(false);
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(1), UiTokens.space(1), UiTokens.space(1)));
        tree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelected());
        tree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelected());
    }

    private void scheduleRebuild() {
        if (rebuildQueued) {
            return;
        }
        rebuildQueued = true;
        SwingUtilities.invokeLater(() -> {
            rebuildQueued = false;
            rebuild();
        });
    }

    private void rebuild() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        List<BuildDiagnostic> visible = allProblems().stream()
                .filter(this::severityVisible)
                .filter(problem -> matches(problem, query))
                .sorted(Comparator.comparingInt(JavaProblemsPanel::severityOrder)
                        .thenComparing(problem -> problem.file() == null ? "" : problem.file().toString())
                        .thenComparingInt(BuildDiagnostic::line)
                        .thenComparingInt(BuildDiagnostic::column))
                .toList();

        if (visible.isEmpty()) {
            empty.setTitle(query.isBlank()
                    ? text("empty.title", "Nenhum problema")
                    : text("empty.filtered.title", "Nenhum problema corresponde"));
            empty.setDescription(query.isBlank()
                    ? text("empty.description", "Erros do Java, Maven e Gradle aparecerao aqui.")
                    : text("empty.filtered.description", "Tente outro texto ou habilite mais severidades."));
            cards.show(body, CARD_EMPTY);
            updateStatus(0);
            return;
        }

        TreeNode<BuildDiagnostic> root = UiSupport.treeNode(null, "root");
        Map<Path, List<BuildDiagnostic>> grouped = new LinkedHashMap<>();
        for (BuildDiagnostic problem : visible) {
            grouped.computeIfAbsent(problem.file(), ignored -> new ArrayList<>()).add(problem);
        }
        grouped.forEach((file, problems) -> {
            String id = file == null ? "build" : file.toString();
            TreeNode<BuildDiagnostic> group = UiSupport.treeNode(null, "file|" + id);
            group.setLabel(displayPath(file) + "  (" + problems.size() + ")");
            group.setIcon(file == null ? JavaIcons.buildTool(null, JavaIcons.SMALL)
                    : JavaIcons.java(JavaIcons.SMALL));
            group.setForeground(UiTokens.muted());
            group.setFont(UiTokens.fontSmall());
            for (BuildDiagnostic problem : problems) {
                group.addChild(problemNode(problem));
            }
            root.addChild(group);
        });
        tree.setRoot(root);
        cards.show(body, CARD_PROBLEMS);
        tree.expandAll();
        updateStatus(visible.size());
    }

    private TreeNode<BuildDiagnostic> problemNode(BuildDiagnostic problem) {
        String id = problem.file() + "|" + problem.line() + "|" + problem.column()
                + "|" + problem.severity() + "|" + problem.message();
        TreeNode<BuildDiagnostic> node = UiSupport.treeNode(problem, id);
        String message = problem.message().replace('\n', ' ').replaceAll("\\s+", " ").trim();
        String position = problem.hasLocation()
                ? "  " + problem.line() + ":" + Math.max(1, problem.column()) : "";
        node.setLabel("<html>" + escape(message) + "  <font color='" + hex(UiTokens.muted())
                + "'>" + escape(position + "  " + problem.source()) + "</font></html>");
        node.setTooltip("<html>" + escape(problem.message()).replace("\n", "<br>") + "</html>");
        node.setIcon(iconFor(problem.severity()));
        node.setForeground(colorFor(problem.severity()));
        node.setSelectable(problem.file() != null);
        return node;
    }

    private List<BuildDiagnostic> allProblems() {
        Map<String, BuildDiagnostic> unique = new LinkedHashMap<>();
        java.util.stream.Stream.concat(liveProblems.stream(), buildProblems.stream())
                .forEach(problem -> unique.putIfAbsent(problem.file() + "|" + problem.line() + "|"
                        + problem.column() + "|" + problem.severity() + "|" + problem.message(), problem));
        return List.copyOf(unique.values());
    }

    private boolean severityVisible(BuildDiagnostic problem) {
        return switch (problem.severity()) {
            case ERROR -> errors.isSelected();
            case WARNING -> warnings.isSelected();
            case INFO, HINT -> information.isSelected();
        };
    }

    private static boolean matches(BuildDiagnostic problem, String query) {
        return query.isEmpty()
                || problem.message().toLowerCase(Locale.ROOT).contains(query)
                || problem.source().toLowerCase(Locale.ROOT).contains(query)
                || problem.file() != null
                && problem.file().toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void updateToggleLabels() {
        List<BuildDiagnostic> all = allProblems();
        errors.setText(text("filter.errors", "Erros") + " " + count(all, DiagnosticSeverity.ERROR));
        warnings.setText(text("filter.warnings", "Avisos") + " " + count(all, DiagnosticSeverity.WARNING));
        long info = all.stream().filter(problem -> problem.severity() == DiagnosticSeverity.INFO
                || problem.severity() == DiagnosticSeverity.HINT).count();
        information.setText(text("filter.information", "Info") + " " + info);
    }

    private void updateStatus(int visible) {
        long errorsCount = allProblems().stream().filter(BuildDiagnostic::isError).count();
        status.setText(visible + " " + text("status.problems", "problema(s)"))
                .setTone(errorsCount > 0 ? BadgeLabel.Tone.DANGER
                        : visible > 0 ? BadgeLabel.Tone.WARNING : BadgeLabel.Tone.NEUTRAL);
        clear.setEnabled(!buildProblems.isEmpty());
    }

    private String displayPath(Path file) {
        if (file == null) {
            return text("group.build", "Build");
        }
        if (projectRoot != null) {
            try {
                return projectRoot.relativize(file).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    private void openSelected() {
        TreeNode<BuildDiagnostic> selected = tree.getSelectedNode();
        if (selected != null && selected.getData() != null && selected.getData().file() != null) {
            host.open(selected.getData());
        }
    }

    private static long count(List<BuildDiagnostic> problems, DiagnosticSeverity severity) {
        return problems.stream().filter(problem -> problem.severity() == severity).count();
    }

    private static int severityOrder(BuildDiagnostic problem) {
        return switch (problem.severity()) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
            case HINT -> 3;
        };
    }

    private static javax.swing.Icon iconFor(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> JavaIcons.failed(JavaIcons.SMALL);
            case WARNING -> JavaIcons.todo(JavaIcons.SMALL);
            case INFO, HINT -> JavaIcons.java(JavaIcons.SMALL);
        };
    }

    private static java.awt.Color colorFor(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> UiTokens.danger();
            case WARNING -> UiTokens.warning();
            case INFO, HINT -> UiTokens.info();
        };
    }

    private static JToggleButton toggle(String label, boolean selected) {
        JToggleButton button = new JToggleButton(label, selected);
        button.setFocusable(false);
        button.setFont(UiTokens.fontSmall());
        return button;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(java.awt.Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
