package dtm.ide.ui;

import dtm.ide.coverage.CoverageDisplay;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.test.JavaTest;
import dtm.ide.test.JavaTestRunner;
import dtm.ide.test.TestResult;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.split.SplitPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.TreeViewMode;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

public final class JavaTestExplorerPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaTestExplorerPanel.class, key, fallback);
    }

    public interface Host {

        List<JavaTest> discover();

        void discoverSemantic(List<JavaTest> provisional, Consumer<List<JavaTest>> onFinished);

        void run(List<JavaTest> tests, Consumer<JavaTestRunner.TestRun> onFinished);

        void debug(List<JavaTest> tests, Consumer<JavaTestRunner.TestRun> onFinished);

        default void runWithCoverage(List<JavaTest> tests,
                                     Consumer<JavaTestRunner.TestRun> onFinished) {
            run(tests, onFinished);
        }

        default boolean supportsCoverage() {
            return false;
        }

        default void clearCoverage() {
        }

        void cancel();

        void openFile(Path file, int line);
    }

    private final Host host;

    private final TreeView<Object> tree = new TreeView<>();
    private final JTextArea details = new JTextArea();
    private final CardLayout detailCards = new CardLayout();
    private final JPanel detailBody = new JPanel(detailCards);
    private final EmptyStatePanel detailEmpty = new EmptyStatePanel(
            text("detail.none.title", "Nenhum teste selecionado"),
            text("detail.none.description",
                    "Selecione um teste para ver o ultimo resultado e a pilha."))
            .setDashedBorder(false).setArc(UiTokens.radius(UiTokens.Radius.MD));

    private final JButton runAllButton = new JButton(text("action.runAll", "Rodar tudo"),
            JavaIcons.test(JavaIcons.SMALL));
    private final JButton runSelectedButton = new JButton(text("action.runSelected", "Rodar selecao"),
            JavaIcons.test(JavaIcons.SMALL));
    private final JButton debugSelectedButton = new JButton(
            text("action.debugSelected", "Depurar selecao"), JavaIcons.debug(JavaIcons.SMALL));
    private final JButton coverageButton = new JButton(
            text("action.runCoverage", "Rodar com cobertura"), JavaIcons.test(JavaIcons.SMALL));
    private final JButton clearCoverageButton = new JButton(
            text("action.clearCoverage", "Limpar cobertura"), JavaIcons.refresh(JavaIcons.SMALL));
    private final JButton refreshButton = new JButton(text("action.refresh", "Atualizar"),
            JavaIcons.refresh(JavaIcons.SMALL));
    private final JButton rerunFailuresButton = new JButton(
            text("action.rerunFailures", "Repetir falhas"), JavaIcons.error(JavaIcons.SMALL));
    private final JButton stopButton = new JButton(text("action.stop", "Parar"),
            JavaIcons.stop(JavaIcons.SMALL));
    private final JCheckBox onlyFailures =
            new JCheckBox(text("action.onlyFailures", "So falhas"));
    private final BadgeLabel status = new BadgeLabel("Discovering", BadgeLabel.Tone.INFO)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);

    private final Map<String, TestResult> resultsByKey = new LinkedHashMap<>();
    private final Set<String> runningKeys = new LinkedHashSet<>();
    private CoverageReport coverage = CoverageReport.EMPTY;
    private List<JavaTest> tests = List.of();
    private volatile long discoveryTicket;

    public JavaTestExplorerPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(26));
        tree.setOpaque(false);
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(1), UiTokens.space(1), UiTokens.space(1)));
        tree.setMode(TreeViewMode.DISCONTIGUOUS);
        tree.setShowCheckBoxPlaceholder(false);
        tree.setExpandOnDoubleClick(false);
        tree.addTreeSelectionListener(event -> showDetails());
        tree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelected());
        tree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelected());

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(UiTokens.fontMono());
        details.setBackground(UiTokens.surface());
        details.setForeground(UiTokens.foreground());
        details.setCaretColor(UiTokens.foreground());
        details.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(3), UiTokens.space(2), UiTokens.space(3)));
        details.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openStackTraceLocation(details.viewToModel2D(event.getPoint()));
                }
            }
        });

        ScrollPanel treeScroll = new ScrollPanel(tree).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(28));
        ScrollPanel detailScroll = new ScrollPanel(details).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(24));
        detailBody.setOpaque(false);
        detailBody.add(detailEmpty, "empty");
        detailBody.add(detailScroll, "details");
        detailCards.show(detailBody, "empty");
        CardPanel testsCard = new CardPanel(text("card.tests", "Testes"),
                text("card.tests.subtitle", "Testes descobertos e o estado da ultima execucao"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setContent(treeScroll);
        CardPanel resultCard = new CardPanel(text("card.result", "Resultado"),
                text("card.result.subtitle", "Detalhes da falha e pilha de execucao"))
                .setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setContent(detailBody);
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, testsCard, resultCard)
                .setDividerThickness(UiTokens.space(2)).setCollapseOnDoubleClick(true);
        split.setResizeWeight(0.58);
        split.setPreferredSize(new Dimension(880, 320));

        add(toolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);
        add(footer, BorderLayout.SOUTH);

        stopButton.setEnabled(false);
        rerunFailuresButton.setEnabled(false);
        clearCoverageButton.setEnabled(false);

        UiSupport.quietFocus(this);
        tree.setFocusable(true);
        details.setFocusable(true);
        onlyFailures.setFocusable(true);

        reload();
    }

    private ToolBarPanel toolbar() {
        runAllButton.addActionListener(event -> run(List.of()));
        runSelectedButton.addActionListener(event -> run(selectedTests()));
        debugSelectedButton.addActionListener(event -> debug(selectedTests()));
        coverageButton.addActionListener(event -> runCoverage(selectedTests()));
        clearCoverageButton.addActionListener(event -> host.clearCoverage());
        refreshButton.addActionListener(event -> reload());
        rerunFailuresButton.addActionListener(event -> run(tests.stream()
                .filter(this::isFailure).toList()));
        stopButton.addActionListener(event -> host.cancel());
        onlyFailures.addActionListener(event -> rebuildTree());

        ToolBarPanel bar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        bar.addItem(runAllButton).addItem(runSelectedButton).addItem(debugSelectedButton)
                .addItem(coverageButton).addItem(clearCoverageButton);
        bar.addSeparator().addItem(refreshButton).addItem(rerunFailuresButton)
                .addItem(stopButton).addSpacer().addItem(onlyFailures);
        refreshCoverageControls();
        return bar;
    }

    private void refreshCoverageControls() {
        boolean supported = host.supportsCoverage();
        coverageButton.setVisible(supported);
        clearCoverageButton.setVisible(supported);
        clearCoverageButton.setEnabled(supported && !coverage.isEmpty());
    }

    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        long ticket = ++discoveryTicket;
        refreshCoverageControls();
        refreshButton.setEnabled(false);
        status.setText(text("status.discovering", "Descobrindo testes..."))
                .setTone(BadgeLabel.Tone.INFO);
        java.util.concurrent.CompletableFuture.supplyAsync(host::discover)
                .whenComplete((discovered, error) -> SwingUtilities.invokeLater(() -> {
                    if (ticket != discoveryTicket) {
                        return;
                    }
                    refreshButton.setEnabled(true);
                    if (error != null) {
                        status.setText(text("status.discoveryFailed", "Falha ao descobrir testes."))
                                .setTone(BadgeLabel.Tone.DANGER);
                        return;
                    }
                    tests = discovered == null ? List.of() : List.copyOf(discovered);
                    rebuildTree();
                    status.setText(tests.size() + " " + text("status.tests", "teste(s)"))
                            .setTone(BadgeLabel.Tone.NEUTRAL);
                    host.discoverSemantic(tests, semantic -> SwingUtilities.invokeLater(() -> {
                        if (ticket != discoveryTicket || semantic == null || semantic.equals(tests)) {
                            return;
                        }
                        tests = List.copyOf(semantic);
                        rebuildTree();
                        status.setText(tests.size() + " " + text("status.tests", "teste(s)"))
                                .setTone(BadgeLabel.Tone.NEUTRAL);
                    }));
                }));
    }

    private void rebuildTree() {
        boolean filtering = onlyFailures.isSelected();
        Set<String> expansion = tree.snapshotExpansion();
        TreeNode<Object> root = UiSupport.treeNode(null, "root");

        Map<String, TreeNode<Object>> packages = new LinkedHashMap<>();
        for (Map.Entry<String, List<JavaTest>> entry : JavaTest.byClass(tests).entrySet()) {
            List<JavaTest> visible = new ArrayList<>();
            for (JavaTest test : entry.getValue()) {
                if (!filtering || isFailure(test)) {
                    visible.add(test);
                }
            }
            if (visible.isEmpty()) {
                continue;
            }
            String className = entry.getKey();
            TreeNode<Object> classNode = UiSupport.treeNode(
                    new ClassNode(className, summaryOf(entry.getValue())), "class|" + className);
            classNode.setLabel(simpleClassName(className) + classCoverageLabel(className));
            classNode.setIcon(JavaIcons.java(JavaIcons.SMALL));
            classNode.setTooltip(className + "  -  " + summaryOf(entry.getValue()));
            for (JavaTest test : visible) {
                classNode.addChild(testNode(test));
            }

            String packageName = packageName(className);
            TreeNode<Object> packageNode = packages.computeIfAbsent(packageName, name -> {
                TreeNode<Object> node = UiSupport.treeNode(new PackageNode(name), "package|" + name);
                node.setLabel((name.isBlank() ? text("tree.defaultPackage", "(pacote padrao)") : name)
                        + packageCoverageLabel(name));
                node.setIcon(JavaIcons.folder(JavaIcons.SMALL));
                node.setForeground(UiTokens.muted());
                node.setFont(UiTokens.fontSmall());
                root.addChild(node);
                return node;
            });
            packageNode.addChild(classNode);
        }

        tree.setRoot(root);
        if (expansion.isEmpty()) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
    }

    private TreeNode<Object> testNode(JavaTest test) {
        TestResult result = resultOf(test);
        boolean running = runningKeys.contains(test.selector());
        TreeNode<Object> node = UiSupport.treeNode(
                new TestNode(test, result, running), "test|" + test.selector());

        String duration = result == null ? "" : "   " + result.durationMs() + " ms";
        node.setLabel(test.display() + duration);
        node.setIcon(testIcon(result, running));
        node.setForeground(testColor(result, running));
        node.setTooltip(test.selector());
        return node;
    }

    private static Icon testIcon(TestResult result, boolean running) {
        if (running) {
            return JavaIcons.run(JavaIcons.SMALL);
        }
        if (result == null) {
            return JavaIcons.test(JavaIcons.SMALL);
        }
        return switch (result.status()) {
            case PASSED -> JavaIcons.passed(JavaIcons.SMALL);
            case FAILED, ERROR -> JavaIcons.failed(JavaIcons.SMALL);
            case SKIPPED -> JavaIcons.skipped(JavaIcons.SMALL);
        };
    }

    private static Color testColor(TestResult result, boolean running) {
        if (running) {
            return UiTokens.info();
        }
        if (result == null) {
            return UiTokens.foreground();
        }
        return switch (result.status()) {
            case PASSED -> UiTokens.foreground();
            case FAILED, ERROR -> UiTokens.danger();
            case SKIPPED -> UiTokens.muted();
        };
    }

    private void run(List<JavaTest> selection) {
        setBusy(true);
        markRunning(selection);
        status.setText(text("status.running", "Executando testes..."))
                .setTone(BadgeLabel.Tone.INFO);

        host.run(selection, run -> SwingUtilities.invokeLater(() -> finishRun(run)));
    }

    public void runTests(List<JavaTest> selection) {
        run(selection);
    }

    private void debug(List<JavaTest> selection) {
        setBusy(true);
        markRunning(selection);
        status.setText(text("status.debugging", "Depurando testes..."))
                .setTone(BadgeLabel.Tone.WARNING);
        host.debug(selection, run -> SwingUtilities.invokeLater(() -> finishRun(run)));
    }

    public void debugTests(List<JavaTest> selection) {
        debug(selection);
    }

    private void runCoverage(List<JavaTest> selection) {
        setBusy(true);
        markRunning(selection);
        status.setText(text("status.coverage", "Executando testes com cobertura..."))
                .setTone(BadgeLabel.Tone.INFO);
        host.runWithCoverage(selection, run -> SwingUtilities.invokeLater(() -> finishRun(run)));
    }

    public void runTestsWithCoverage(List<JavaTest> selection) {
        runCoverage(selection);
    }

    public void setCoverage(CoverageReport report) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setCoverage(report));
            return;
        }
        coverage = report == null ? CoverageReport.EMPTY : report;
        refreshCoverageControls();
        rebuildTree();
    }

    private void finishRun(JavaTestRunner.TestRun run) {
        setBusy(false);
        runningKeys.clear();
        if (run == null) {
            status.setText(text("status.noRunner", "Nenhum build system para rodar os testes."))
                    .setTone(BadgeLabel.Tone.DANGER);
            return;
        }
        resultsByKey.clear();
        run.results().forEach(result -> resultsByKey.put(result.key(), result));
        rebuildTree();
        boolean failed = run.results().stream().anyMatch(TestResult::isFailure);
        status.setText(run.summary()).setTone(
                failed ? BadgeLabel.Tone.DANGER : BadgeLabel.Tone.SUCCESS);
    }

    private void markRunning(List<JavaTest> selection) {
        runningKeys.clear();
        List<JavaTest> values = selection == null || selection.isEmpty() ? tests : selection;
        values.forEach(test -> runningKeys.add(test.selector()));
        rebuildTree();
    }

    private void setBusy(boolean busy) {
        runAllButton.setEnabled(!busy);
        runSelectedButton.setEnabled(!busy);
        debugSelectedButton.setEnabled(!busy);
        coverageButton.setEnabled(!busy);
        clearCoverageButton.setEnabled(!busy && !coverage.isEmpty() && host.supportsCoverage());
        refreshButton.setEnabled(!busy);
        rerunFailuresButton.setEnabled(!busy && resultsByKey.values().stream()
                .anyMatch(TestResult::isFailure));
        stopButton.setEnabled(busy);
    }

    private List<JavaTest> selectedTests() {
        List<TreeNode<Object>> nodes = tree.getSelectedNodes();
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<String, JavaTest> selected = new LinkedHashMap<>();
        for (TreeNode<Object> node : nodes) {
            for (JavaTest test : testsOf(node.getData())) {
                selected.putIfAbsent(test.selector(), test);
            }
        }
        return List.copyOf(selected.values());
    }

    private Object focusedNodeData() {
        TreeNode<Object> node = tree.getSelectedNode();
        return node == null ? null : node.getData();
    }

    private List<JavaTest> testsOf(Object node) {
        if (node instanceof TestNode testNode) {
            return List.of(testNode.test());
        }
        if (node instanceof ClassNode classNode) {
            return tests.stream()
                    .filter(test -> test.className().equals(classNode.className()))
                    .toList();
        }
        if (node instanceof PackageNode packageNode) {
            return tests.stream().filter(test -> packageName(test.className())
                    .equals(packageNode.name())).toList();
        }
        return List.of();
    }

    private void openSelected() {
        Object selected = focusedNodeData();
        if (selected == null) {
            return;
        }
        if (selected instanceof TestNode testNode) {
            host.openFile(testNode.test().file(), testNode.test().line());
        } else if (selected instanceof ClassNode classNode) {
            tests.stream()
                    .filter(test -> test.className().equals(classNode.className()))
                    .findFirst()
                    .ifPresent(test -> host.openFile(test.file(), 1));
        }
    }

    private void showDetails() {
        Object selected = focusedNodeData();
        if (selected == null) {
            details.setText("");
            detailEmpty.setTitle(text("detail.none.title", "Nenhum teste selecionado"));
            detailEmpty.setDescription(text("detail.none.description",
                    "Selecione um teste para ver o ultimo resultado e a pilha."));
            detailCards.show(detailBody, "empty");
            return;
        }
        if (!(selected instanceof TestNode testNode) || testNode.result() == null) {
            details.setText("");
            detailEmpty.setTitle(text("detail.pending.title", "Ainda sem resultado"));
            detailEmpty.setDescription(text("detail.pending.description",
                    "Execute o teste selecionado para ver tempo, erros e pilha."));
            detailCards.show(detailBody, "empty");
            return;
        }
        TestResult result = testNode.result();
        StringBuilder content = new StringBuilder();
        content.append(result.className()).append('#').append(result.methodName()).append('\n');
        content.append(result.status()).append("  ").append(result.durationMs()).append(" ms\n");
        if (!result.message().isBlank()) {
            content.append('\n').append(result.message()).append('\n');
        }
        if (!result.stackTrace().isBlank()) {
            content.append('\n').append(result.stackTrace());
        }
        details.setText(content.toString());
        details.setCaretPosition(0);
        detailCards.show(detailBody, "details");
    }

    private void openStackTraceLocation(int offset) {
        if (offset < 0 || details.getText().isBlank()) {
            return;
        }
        String content = details.getText();
        int start = content.lastIndexOf('\n', Math.min(offset, content.length()) - 1) + 1;
        int end = content.indexOf('\n', offset);
        String line = content.substring(start, end < 0 ? content.length() : end);
        java.util.regex.Matcher location = java.util.regex.Pattern
                .compile("\\(([^():]+\\.java):(\\d+)\\)").matcher(line);
        if (!location.find()) {
            return;
        }
        int targetLine = Integer.parseInt(location.group(2));
        tests.stream().map(JavaTest::file).filter(java.util.Objects::nonNull)
                .filter(file -> file.getFileName().toString().equals(location.group(1)))
                .findFirst().ifPresent(file -> host.openFile(file, targetLine));
    }

    private TestResult resultOf(JavaTest test) {
        return resultsByKey.get(test.className() + "#" + test.methodName());
    }

    private boolean isFailure(JavaTest test) {
        TestResult result = resultOf(test);
        return result != null && result.isFailure();
    }

    private String summaryOf(List<JavaTest> classTests) {
        long executed = classTests.stream().filter(test -> resultOf(test) != null).count();
        if (executed == 0) {
            return classTests.size() + " " + text("status.tests", "teste(s)");
        }
        long passed = classTests.stream()
                .map(this::resultOf)
                .filter(result -> result != null && result.isSuccess())
                .count();
        return passed + "/" + executed + " " + text("status.passed", "passou");
    }

    private String classCoverageLabel(String className) {
        return coverage.forClass(className)
                .filter(file -> !file.isEmpty())
                .map(file -> "   " + CoverageDisplay.percent(file.linePercentage()))
                .orElse("");
    }

    private String packageCoverageLabel(String packageName) {
        CoverageReport.Totals totals = coverage.forPackage(packageName);
        return totals.isEmpty() ? "" : "   " + CoverageDisplay.percent(totals.linePercentage());
    }

    private static String simpleClassName(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? String.valueOf(name) : name.substring(dot + 1);
    }

    private record ClassNode(String className, String summary) {
        @Override
        public String toString() {
            int lastDot = className.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
            return simpleName + "  -  " + summary;
        }
    }

    private record PackageNode(String name) {
        @Override
        public String toString() {
            return name.isBlank() ? "(default package)" : name;
        }
    }

    private static String packageName(String className) {
        int lastDot = className == null ? -1 : className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    private record TestNode(JavaTest test, TestResult result, boolean running) {
        @Override
        public String toString() {
            if (running) {
                return "[executando] " + test.display();
            }
            if (result == null) {
                return test.display();
            }
            String marker = switch (result.status()) {
                case PASSED -> "[ok]";
                case FAILED -> "[falhou]";
                case ERROR -> "[erro]";
                case SKIPPED -> "[pulado]";
            };
            return marker + " " + test.display() + "  (" + result.durationMs() + " ms)";
        }
    }
}
