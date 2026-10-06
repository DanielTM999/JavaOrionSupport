package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.build.BuildResult;
import dtm.ide.swingdesigner.ModuleSession;
import dtm.ide.swingdesigner.SwingDesignerWorkspace;
import dtm.ide.swingdesigner.catalog.ComponentDescriptor;
import dtm.ide.swingdesigner.catalog.ConstructorInfo;
import dtm.ide.swingdesigner.catalog.ParameterInfo;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.swingdesigner.runtime.ConstructorUse;
import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.ide.swingdesigner.runtime.SwingViewClient;
import dtm.ide.swingdesigner.runtime.ViewResult;
import dtm.ide.ui.FlatTreeRenderer;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.PillButtons;
import dtm.ide.ui.UiSupport;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.configs.UiTokens;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class SwingViewerPanel extends JPanel {

    private static final String CARD_CANVAS = "canvas";
    private static final String CARD_MESSAGE = "message";
    private static final String[] ZOOMS = {"50%", "75%", "100%", "125%", "150%", "200%"};

    private final SwingDesignerWorkspace workspace;
    private final ModuleSession session;
    private final Path file;
    private final String className;
    private final ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();

    private final SnapshotCanvas canvas = new SnapshotCanvas();
    private final TreeView<SnapshotNode> tree = new TreeView<>();
    private final ViewerInspector inspector = new ViewerInspector();
    private final JTextArea message = new JTextArea();
    private final JPanel center = new JPanel(new CardLayout());
    private final JLabel status = new JLabel(" ");
    private final Timer saveDebounce;

    private ConstructorUse constructor;
    private ViewResult current;
    private String selectedId;
    private boolean syncingSelection;

    public SwingViewerPanel(SwingDesignerWorkspace workspace, ModuleSession session, Path file,
                            String className) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.session = session;
        this.file = file;
        this.className = className;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "swing-viewer-" + simpleName(className));
            thread.setDaemon(true);
            return thread;
        });
        this.saveDebounce = new Timer(400, event -> refresh(true));
        this.saveDebounce.setRepeats(false);
        setOpaque(true);
        setBackground(UiTokens.background());
        add(toolbar(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        canvas.onSelection(node -> select(node.id()));
        inspector.onPropertyEdited(this::applyProperty);
        inspector.onArgumentEdited(this::applyArgument);
        inspector.clear("Carregando...");
    }

    public Path file() {
        return file;
    }

    public String className() {
        return className;
    }

    public ModuleSession session() {
        return session;
    }

    public void start() {
        refresh(true);
    }

    public void onSourceSaved() {
        saveDebounce.restart();
    }

    public void dispose() {
        saveDebounce.stop();
        worker.shutdownNow();
    }

    public void refresh(boolean rebuild) {
        long ticket = generation.incrementAndGet();
        setStatus(rebuild ? "Compilando..." : "Renderizando...");
        worker.submit(() -> {
            try {
                if (rebuild) {
                    BuildResult build = workspace.compile(session);
                    if (build != null && !build.successful()) {
                        showMessage(ticket, "A compilacao falhou; corrija os erros e salve de novo.\n\n"
                                + build.summary(), null);
                        return;
                    }
                }
                if (!session.catalog().isDrawable(className)) {
                    showMessage(ticket, className + " nao herda de java.awt.Component, entao nao ha"
                            + " o que desenhar.", null);
                    return;
                }
                SwingViewClient client = session.client();
                ViewResult result = client.view(className, constructor, -1, -1);
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get()) {
                        apply(result);
                    }
                });
            } catch (Exception error) {
                log.debug("Falha ao renderizar {}", className, error);
                showMessage(ticket, "Nao foi possivel renderizar " + className + ":\n"
                        + rootMessage(error), null);
            }
        });
    }

    private void apply(ViewResult result) {
        current = result;
        if (result.constructor() != null) {
            constructor = result.constructor();
        }
        if (result.failed()) {
            StringBuilder text = new StringBuilder("Nao foi possivel instanciar ")
                    .append(className).append(":\n").append(result.error()).append("\n");
            if (!result.attempts().isEmpty()) {
                text.append("\nTentativas:\n");
                result.attempts().forEach(attempt -> text.append("  - ").append(attempt).append('\n'));
            }
            if (result.stackTrace() != null) {
                text.append('\n').append(result.stackTrace());
            }
            message.setText(text.toString());
            message.setCaretPosition(0);
            ((CardLayout) center.getLayout()).show(center, CARD_MESSAGE);
            setStatus("Falhou");
            showRootInspector();
            return;
        }
        String windowTitle = result.title() == null || result.title().isBlank()
                ? simpleName(className) : result.title();
        canvas.show(result.image(), result.root(), result.window() ? windowTitle : null);
        ((CardLayout) center.getLayout()).show(center, CARD_CANVAS);
        rebuildTree(result.root());
        String keep = selectedId != null && result.root().find(selectedId).isPresent()
                ? selectedId : result.root().id();
        select(keep);
        setStatus(result.width() + " × " + result.height());
    }

    private void select(String id) {
        selectedId = id;
        canvas.select(id);
        syncTreeSelection(id);
        if (current == null || current.root() == null) {
            return;
        }
        Optional<SnapshotNode> node = current.root().find(id);
        if (node.isEmpty()) {
            return;
        }
        inspect(node.get());
    }

    private void inspect(SnapshotNode node) {
        long ticket = generation.get();
        worker.submit(() -> {
            try {
                ComponentDescriptor descriptor = session.catalog().descriptor(node.className()).orElse(null);
                Map<String, JsonNode> values = descriptor == null ? Map.of()
                        : session.client().inspect(node.id(), readable(descriptor)).values();
                boolean root = current != null && current.root() != null
                        && current.root().id().equals(node.id());
                List<ParameterInfo> parameters = root ? constructorParameters() : List.of();
                List<JsonNode> arguments = root && constructor != null ? constructor.values() : List.of();
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get() && node.id().equals(selectedId)) {
                        inspector.show(node.label(), node.className(), descriptor, values, parameters, arguments);
                    }
                });
            } catch (Exception error) {
                log.debug("Falha ao inspecionar {}", node.className(), error);
                SwingUtilities.invokeLater(() -> inspector.clear("Erro: " + rootMessage(error)));
            }
        });
    }

    private void showRootInspector() {
        List<ParameterInfo> parameters = constructorParameters();
        inspector.show(simpleName(className), className, null, Map.of(), parameters,
                constructor == null ? List.of() : constructor.values());
    }

    private List<ParameterInfo> constructorParameters() {
        if (constructor == null || !constructor.hasParameters()) {
            return List.of();
        }
        Optional<ComponentDescriptor> declared = session.catalog().declared(className);
        if (declared.isPresent()) {
            for (ConstructorInfo info : declared.get().constructorsOrEmpty()) {
                boolean sameFactory = java.util.Objects.equals(info.factoryMethod(), constructor.factory());
                if (sameFactory && info.parameterTypes().equals(constructor.types())) {
                    return info.parameters();
                }
            }
        }
        List<ParameterInfo> fallback = new ArrayList<>();
        for (int i = 0; i < constructor.types().size(); i++) {
            fallback.add(new ParameterInfo("arg" + i, constructor.types().get(i)));
        }
        return fallback;
    }

    private void applyProperty(PropertyDescriptor property, JsonNode value) {
        String nodeId = selectedId;
        if (nodeId == null || property.setter() == null || property.type() == null) {
            return;
        }
        long ticket = generation.incrementAndGet();
        setStatus("Aplicando " + property.label() + "...");
        worker.submit(() -> {
            try {
                ViewResult result = session.client().setProperty(nodeId, property.setter(),
                        List.of(property.type()), List.of(value));
                ViewResult merged = new ViewResult(result.image(), result.width(), result.height(),
                        result.root(), constructor, current == null ? List.of() : current.attempts(),
                        null, null, current != null && current.window(),
                        current == null ? null : current.title());
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get()) {
                        apply(merged);
                    }
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> setStatus("Erro em " + property.label() + ": "
                        + rootMessage(error)));
                Optional.ofNullable(current).map(ViewResult::root).flatMap(root -> root.find(nodeId))
                        .ifPresent(this::inspect);
            }
        });
    }

    private void applyArgument(int index, JsonNode value) {
        if (constructor == null) {
            return;
        }
        constructor = constructor.withValue(index, value);
        refresh(false);
    }

    private void preview() {
        setStatus("Abrindo preview...");
        worker.submit(() -> {
            try {
                String error = session.client().preview(className, constructor);
                SwingUtilities.invokeLater(() -> setStatus(error == null ? "Preview aberto" : "Preview: " + error));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> setStatus("Preview: " + rootMessage(failure)));
            }
        });
    }

    private void rebuildTree(SnapshotNode root) {
        java.util.Set<String> expansion = tree.snapshotExpansion();
        TreeNode<SnapshotNode> node = treeNode(root);
        tree.setRoot(node);
        if (expansion.isEmpty()) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
    }

    private TreeNode<SnapshotNode> treeNode(SnapshotNode snapshot) {
        TreeNode<SnapshotNode> node = new TreeNode<>(snapshot);
        node.setId(snapshot.id());
        node.setLabel(snapshot.label());
        node.setTooltip(snapshot.className());
        node.setIcon(snapshot.children().isEmpty() ? JavaIcons.javaClass(JavaIcons.SMALL)
                : JavaIcons.folder(JavaIcons.SMALL));
        for (SnapshotNode child : snapshot.children()) {
            node.addChild(treeNode(child));
        }
        return node;
    }

    private void syncTreeSelection(String id) {
        TreeNode<SnapshotNode> match = id == null ? null : tree.findById(id);
        if (match == null) {
            return;
        }
        syncingSelection = true;
        try {
            tree.expandParents(match);
            tree.selectNode(match);
        } finally {
            syncingSelection = false;
        }
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2), UiTokens.space(1),
                        UiTokens.space(2))));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        actions.setOpaque(false);
        JButton reload = PillButtons.secondary("Recarregar", JavaIcons.refresh(JavaIcons.SMALL));
        reload.addActionListener(event -> refresh(true));
        JButton preview = PillButtons.primary("Preview", JavaIcons.run(JavaIcons.SMALL));
        preview.addActionListener(event -> preview());
        JComboBox<String> zoom = new JComboBox<>(ZOOMS);
        zoom.setSelectedItem("100%");
        zoom.addActionListener(event -> canvas.setZoom(
                Integer.parseInt(String.valueOf(zoom.getSelectedItem()).replace("%", "")) / 100.0));
        actions.add(reload);
        actions.add(preview);
        actions.add(zoom);
        JLabel heading = new JLabel(simpleName(className));
        heading.setFont(UiTokens.fontBold());
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UiTokens.space(3)));
        heading.setToolTipText(className);
        status.setForeground(UiTokens.muted());
        status.setFont(UiTokens.fontSmall());
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(heading, BorderLayout.WEST);
        left.add(actions, BorderLayout.CENTER);
        bar.add(left, BorderLayout.WEST);
        bar.add(status, BorderLayout.EAST);
        return bar;
    }

    private JSplitPane body() {
        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(24));
        tree.setOpaque(false);
        tree.onTreeEvent(EventTreeView.SELECTION_CHANGE, event -> {
            if (syncingSelection) {
                return;
            }
            TreeNode<SnapshotNode> selected = tree.getSelectedNode();
            if (selected != null && selected.getData() != null) {
                select(selected.getData().id());
            }
        });
        JPanel hierarchy = new JPanel(new BorderLayout());
        hierarchy.setOpaque(false);
        JLabel hierarchyTitle = new JLabel("Hierarquia");
        hierarchyTitle.setFont(UiTokens.fontBold());
        hierarchyTitle.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), UiTokens.space(2),
                UiTokens.space(1), UiTokens.space(2)));
        hierarchy.add(hierarchyTitle, BorderLayout.NORTH);
        hierarchy.add(UiSupport.plainScroll(new JScrollPane(tree)), BorderLayout.CENTER);

        message.setEditable(false);
        message.setFont(UiTokens.fontMono());
        message.setLineWrap(false);
        message.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(3), UiTokens.space(3),
                UiTokens.space(3), UiTokens.space(3)));
        JScrollPane canvasScroll = UiSupport.plainScroll(new JScrollPane(canvas));
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(canvasScroll, CARD_CANVAS);
        center.add(UiSupport.plainScroll(new JScrollPane(message)), CARD_MESSAGE);

        JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, inspector);
        right.setResizeWeight(1.0);
        right.setBorder(null);
        right.setContinuousLayout(true);
        right.setDividerLocation(0.72);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hierarchy, right);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setDividerLocation(UiTokens.scale(240));
        inspector.setPreferredSize(new java.awt.Dimension(UiTokens.scale(320), 100));
        return split;
    }

    private void showMessage(long ticket, String text, String detail) {
        SwingUtilities.invokeLater(() -> {
            if (ticket != generation.get()) {
                return;
            }
            message.setText(detail == null ? text : text + "\n\n" + detail);
            message.setCaretPosition(0);
            ((CardLayout) center.getLayout()).show(center, CARD_MESSAGE);
            inspector.clear(" ");
            setStatus(" ");
        });
    }

    private List<PropertyDescriptor> readable(ComponentDescriptor descriptor) {
        return descriptor.propertiesOrEmpty().values().stream()
                .filter(property -> !property.isHidden() && property.isReadable() && property.arity() == 1)
                .toList();
    }

    private void setStatus(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            status.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> status.setText(text));
        }
    }

    static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return (dot < 0 ? className : className.substring(dot + 1)).replace('$', '.');
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
