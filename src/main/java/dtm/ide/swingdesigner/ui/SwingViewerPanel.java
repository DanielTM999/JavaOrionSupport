package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.build.BuildResult;
import dtm.ide.swingdesigner.ModuleSession;
import dtm.ide.swingdesigner.SwingDesignerWorkspace;
import dtm.ide.swingdesigner.catalog.ComponentCatalog;
import dtm.ide.swingdesigner.catalog.ComponentDescriptor;
import dtm.ide.swingdesigner.catalog.ConstructorInfo;
import dtm.ide.swingdesigner.catalog.DesignerOptions;
import dtm.ide.swingdesigner.catalog.EventDescriptor;
import dtm.ide.swingdesigner.catalog.LayoutDescriptor;
import dtm.ide.swingdesigner.catalog.ParameterInfo;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.swingdesigner.form.FormCall;
import dtm.ide.swingdesigner.form.FormComponent;
import dtm.ide.swingdesigner.form.FormModel;
import dtm.ide.swingdesigner.form.SourceEditPlanner;
import dtm.ide.swingdesigner.recovery.LifecycleRecovery;
import dtm.ide.swingdesigner.runtime.ConstructorUse;
import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.ide.swingdesigner.runtime.SwingViewClient;
import dtm.ide.swingdesigner.runtime.ViewOptions;
import dtm.ide.swingdesigner.runtime.ViewResult;
import dtm.ide.swingdesigner.runtime.ViewWarning;
import dtm.ide.ui.FlatTreeRenderer;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.PillButtons;
import dtm.ide.ui.UiSupport;
import dtm.stools.component.panels.loading.LoadingPanel;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;
import dtm.stools.component.tree.event.EventTreeView;
import dtm.stools.configs.UiTokens;
import lombok.extern.slf4j.Slf4j;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

@Slf4j
public final class SwingViewerPanel extends JPanel {

    private static final String CARD_CANVAS = "canvas";
    private static final String CARD_MESSAGE = "message";
    private static final String CARD_LOADING = "loading";
    private static final String TREE_CARD = "tree";
    private static final String TREE_EMPTY = "empty";
    private static final String LEFT_PALETTE = "Paleta";
    private static final String LEFT_TREE = "Hierarquia";
    private static final String ZOOM_FIT = "Ajustar";
    private static final String[] ZOOMS = {ZOOM_FIT, "50%", "75%", "100%", "125%", "150%", "200%"};

    private final SwingDesignerWorkspace workspace;
    private final ModuleSession session;
    private final Path file;
    private final String className;
    private final ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();
    private final DesignEditor editor;

    private final SnapshotCanvas canvas = new SnapshotCanvas();
    private final TreeView<SnapshotNode> tree = new TreeView<>();
    private final ViewerInspector inspector = new ViewerInspector();
    private final LayoutPanel layoutPanel = new LayoutPanel();
    private final EventsPanel eventsPanel = new EventsPanel();
    private final DesignInspector designInspector = new DesignInspector(inspector, layoutPanel, eventsPanel);
    private final PalettePanel palette = new PalettePanel();
    private final JTextArea message = new JTextArea();
    private final JPanel center = new JPanel(new CardLayout());
    private final JPanel hierarchyCards = new JPanel(new CardLayout());
    private final JLabel hierarchyHint = new JLabel(" ", SwingConstants.CENTER);
    private final JPanel leftCards = new JPanel(new CardLayout());
    private final JPanel hierarchy = new JPanel(new BorderLayout());
    private final JPanel stage = new JPanel(new BorderLayout());
    private final JPanel body = new JPanel(new BorderLayout());
    private final ProblemsPanel problems = new ProblemsPanel();
    private final JSplitPane stageSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JButton problemsChip = new JButton();
    private final LoadingPanel loading = new LoadingPanel();
    private final JLabel status = new JLabel(" ");
    private final JLabel editState = new JLabel(" ");
    private final JLabel sizeLabel = new JLabel(" ");
    private final JButton undo = PillButtons.iconAction(new GlyphIcon(GlyphIcon.Kind.UNDO), "Desfazer (Ctrl+Z)");
    private final JButton redo = PillButtons.iconAction(new GlyphIcon(GlyphIcon.Kind.REDO), "Refazer (Ctrl+Y)");
    private final Timer saveDebounce;
    private final Timer shadowDebounce;

    private JToggleButton hierarchyToggle;
    private JToggleButton inspectorToggle;
    private boolean compact;
    private boolean showHierarchy;
    private boolean showInspector;
    private int hierarchyWidth = UiTokens.scale(240);
    private int inspectorWidth = UiTokens.scale(340);
    private int problemsHeight = UiTokens.scale(220);
    private ConstructorUse constructor;
    private ViewResult current;
    private String selectedId;
    private String selectedComponent;
    private boolean syncingSelection;
    private boolean paletteLoaded;
    private List<ViewWarning> editWarnings = List.of();

    public SwingViewerPanel(SwingDesignerWorkspace workspace, ModuleSession session, Path file,
                            String className) {
        super(new BorderLayout());
        this.workspace = workspace;
        this.session = session;
        this.file = file;
        this.className = className;
        this.editor = new DesignEditor(workspace.environment(), session, file, className);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "swing-viewer-" + simpleName(className));
            thread.setDaemon(true);
            return thread;
        });
        this.saveDebounce = new Timer(400, event -> refresh(true));
        this.saveDebounce.setRepeats(false);
        this.shadowDebounce = new Timer(800, event -> shadowRefresh());
        this.shadowDebounce.setRepeats(false);
        setOpaque(true);
        setBackground(UiTokens.background());
        showHierarchy = preference("full.hierarchy", true);
        showInspector = preference("full.inspector", true);
        buildContent();
        add(toolbar(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        layoutBody();
        canvas.onSelection(node -> select(node.id()));
        canvas.setHooks(new CanvasHooks());
        inspector.onPropertyEdited(this::applyProperty);
        inspector.onArgumentEdited(this::applyArgument);
        inspector.onPropertyReset(this::resetProperty);
        inspector.onPropertyNavigate(this::navigateProperty);
        inspector.onNameEdited(this::rename);
        designInspector.onTabChanged(this::refreshSideTabs);
        palette.onActivate(this::addToSelection);
        problems.configure(session::sourceOf, (path, line) -> workspace.environment().openSource(path, line),
                expanded -> {
                    storePreference("problems.open", expanded);
                    placeProblems();
                });
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "designer.undo",
                this::undo);
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "designer.redo",
                this::redo);
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "designer.redo.shift", this::redo);
        updateHistoryButtons();
        showLoading("Preparando...");
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
        shadowDebounce.stop();
        saveDebounce.restart();
    }

    public void dispose() {
        saveDebounce.stop();
        shadowDebounce.stop();
        loading.stop();
        worker.shutdownNow();
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        showHierarchy = preference(prefix() + "hierarchy", !compact);
        showInspector = preference(prefix() + "inspector", !compact);
        hierarchyToggle.setSelected(showHierarchy);
        inspectorToggle.setSelected(showInspector);
        layoutBody();
    }

    public void refresh(boolean rebuild) {
        long ticket = generation.incrementAndGet();
        setStatus(rebuild ? "Compilando..." : "Renderizando...");
        showLoading(rebuild ? "Compilando o modulo..." : "Renderizando...");
        worker.submit(() -> {
            try {
                if (rebuild) {
                    BuildResult build = workspace.compile(session);
                    if (build != null && !build.successful()) {
                        showMessage(ticket, "A compilacao falhou; corrija os erros e salve de novo.\n\n"
                                + build.summary());
                        return;
                    }
                }
                if (!session.catalog().isDrawable(className)) {
                    showMessage(ticket, className + " nao herda de java.awt.Component, entao nao ha"
                            + " o que desenhar.");
                    return;
                }
                if (!session.hasLiveClient()) {
                    showLoading("Iniciando a JVM do designer...");
                }
                showLoading("Renderizando " + simpleName(className) + "...");
                ViewResult result = render(true);
                editWarnings = List.of();
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get()) {
                        apply(result);
                    }
                });
            } catch (Exception error) {
                log.debug("Falha ao renderizar {}", className, error);
                showMessage(ticket, "Nao foi possivel renderizar " + className + ":\n" + rootMessage(error));
            }
        });
    }

    private ViewResult render(boolean showProgress) {
        SwingViewClient client = session.client();
        ComponentCatalog catalog = session.catalog();
        DesignerOptions designer = catalog.options();
        ViewResult viewed = client.view(className, constructor, viewOptions(catalog), -1, -1);
        if (designer.recoveryEnabled() && !viewed.failed() && !viewed.errors().isEmpty()) {
            if (showProgress) {
                showLoading("Recuperando a montagem de " + simpleName(className) + "...");
            }
            viewed = LifecycleRecovery.using(
                    owner -> catalog.index().superChain(className).contains(owner),
                    session::sourceOf, client, designer.stubsEnabled()).run(viewed);
        }
        editor.refresh(viewed.root());
        return viewed;
    }

    private void shadowRefresh() {
        if (!editor.editable()) {
            return;
        }
        long ticket = generation.incrementAndGet();
        worker.submit(() -> {
            try {
                BuildResult build = editor.compileShadow();
                if (build == null || !build.successful()) {
                    List<ViewWarning> diagnostics = build == null ? List.of()
                            : DesignEditor.diagnostics(build);
                    editWarnings = diagnostics;
                    SwingUtilities.invokeLater(() -> {
                        if (ticket == generation.get()) {
                            showProblems(merged(current == null ? List.of() : current.warnings()));
                            if (!problems.isExpanded()) {
                                problems.setExpanded(true);
                            }
                            setStatus("O codigo nao compila; o desenho mostra o ultimo estado valido");
                        }
                    });
                    return;
                }
                editWarnings = List.of();
                ViewResult result = render(false);
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get()) {
                        apply(result);
                    }
                });
            } catch (Exception error) {
                log.debug("Falha ao recompilar {} no designer", className, error);
                SwingUtilities.invokeLater(() -> setStatus("Falha ao atualizar: " + rootMessage(error)));
            }
        });
    }

    private void apply(ViewResult result) {
        current = result;
        loading.stop();
        showProblems(merged(result.warnings()));
        if (result.constructor() != null) {
            constructor = result.constructor();
        }
        loadPalette();
        updateEditState();
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
            showCard(CARD_MESSAGE);
            showHierarchyHint("Nada para mostrar");
            setStatus("Falhou");
            showRootInspector();
            return;
        }
        String windowTitle = result.title() == null || result.title().isBlank()
                ? simpleName(className) : result.title();
        canvas.show(result.image(), result.root(), result.window() ? windowTitle : null);
        showCard(CARD_CANVAS);
        rebuildTree(result.root());
        ((CardLayout) hierarchyCards.getLayout()).show(hierarchyCards, TREE_CARD);
        String keep = selectedComponent == null ? null : editor.nodeOf(selectedComponent).orElse(null);
        if (keep == null || result.root().find(keep).isEmpty()) {
            keep = selectedId != null && result.root().find(selectedId).isPresent() ? selectedId : result.root().id();
        }
        select(keep);
        sizeLabel.setText(result.width() + " x " + result.height());
        if (status.getText().endsWith("...") || status.getText().isBlank()) {
            setStatus(" ");
        }
    }

    private List<ViewWarning> merged(List<ViewWarning> warnings) {
        if (editWarnings.isEmpty()) {
            return warnings;
        }
        List<ViewWarning> all = new ArrayList<>(editWarnings);
        all.addAll(warnings);
        return all;
    }

    private void loadPalette() {
        if (paletteLoaded) {
            return;
        }
        paletteLoaded = true;
        worker.submit(() -> {
            try {
                ComponentCatalog catalog = session.catalog();
                List<ComponentCatalog.PaletteEntry> entries = catalog.palette();
                SwingUtilities.invokeLater(() -> palette.show(entries, catalog::isDrawable));
            } catch (Exception error) {
                log.debug("Paleta indisponivel", error);
            }
        });
    }

    private void updateEditState() {
        if (editor.editable()) {
            editState.setText("Editando o codigo");
            editState.setToolTipText("As mudancas no desenho sao gravadas em " + file.getFileName());
        } else {
            editState.setText("Somente visualizacao");
            editState.setToolTipText(editor.readProblem());
        }
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        undo.setEnabled(editor.history().canUndo());
        redo.setEnabled(editor.history().canRedo());
        undo.setToolTipText(editor.history().nextUndoLabel().map(label -> "Desfazer " + label + " (Ctrl+Z)")
                .orElse("Desfazer (Ctrl+Z)"));
        redo.setToolTipText(editor.history().nextRedoLabel().map(label -> "Refazer " + label + " (Ctrl+Y)")
                .orElse("Refazer (Ctrl+Y)"));
    }

    private void select(String id) {
        selectedId = id;
        selectedComponent = editor.links().componentOf(id).orElse(null);
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
        refreshSideTabs();
    }

    private void refreshSideTabs() {
        if (current == null || current.root() == null || selectedId == null) {
            layoutPanel.clear(" ");
            eventsPanel.clear(" ");
            return;
        }
        Optional<SnapshotNode> node = current.root().find(selectedId);
        if (node.isEmpty()) {
            return;
        }
        SnapshotNode selected = node.get();
        SnapshotNode parent = current.root().parentOf(selected.id()).orElse(null);
        String reason = editor.lockReason(selected.id());
        boolean editable = editor.editable() && reason == null;
        if (DesignInspector.LAYOUT.equals(designInspector.active())) {
            worker.submit(() -> {
                Map<String, LayoutDescriptor> layouts = session.catalog().layouts();
                SwingUtilities.invokeLater(() -> {
                    if (selected.id().equals(selectedId)) {
                        layoutPanel.show(selected, parent, layouts, editable, reason, new LayoutActions(selected));
                    }
                });
            });
        } else if (DesignInspector.EVENTS.equals(designInspector.active())) {
            boolean eventsEditable = editor.editable()
                    && (reason == null || editor.componentOf(selected.id())
                    .map(component -> component.kind() == FormComponent.Kind.FIELD).orElse(false)
                    || "0".equals(selected.id()));
            worker.submit(() -> {
                List<EventDescriptor> events = session.catalog().descriptor(selected.className())
                        .map(ComponentDescriptor::eventsOrEmpty).orElse(List.of());
                SwingUtilities.invokeLater(() -> {
                    if (selected.id().equals(selectedId)) {
                        eventsPanel.show(events, eventsEditable, reason, new EventActions(selected.id()));
                    }
                });
            });
        }
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
                Optional<FormComponent> component = editor.componentOf(node.id());
                String variable = component.filter(found -> found.kind() == FormComponent.Kind.FIELD
                        || found.kind() == FormComponent.Kind.LOCAL).map(FormComponent::name).orElse(null);
                String reason = editor.lockReason(node.id());
                boolean nameEditable = variable != null && reason == null;
                Set<String> defined = editor.definedSetters(node.id());
                SwingUtilities.invokeLater(() -> {
                    if (ticket <= generation.get() && node.id().equals(selectedId)) {
                        inspector.show(node.label(), node.className(), descriptor, values, parameters, arguments,
                                variable, nameEditable, defined, editor.editable() ? reason : null);
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
        if (editor.editable() && editor.lockReason(nodeId) == null) {
            edit("Alterando " + property.label(), () -> editor.setProperty(nodeId, property, value));
            return;
        }
        long ticket = generation.incrementAndGet();
        setStatus("Aplicando " + property.label() + " so no desenho...");
        worker.submit(() -> {
            try {
                ViewResult result = session.client().setProperty(nodeId, property.setter(),
                        List.of(property.type()), List.of(value));
                ViewResult merged = mergeLive(result);
                SwingUtilities.invokeLater(() -> {
                    if (ticket == generation.get()) {
                        apply(merged);
                        setStatus(property.label() + " alterado so no desenho: " + editor.lockReason(nodeId));
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

    private void resetProperty(PropertyDescriptor property) {
        String nodeId = selectedId;
        if (nodeId != null) {
            edit("Removendo " + property.label(), () -> editor.resetProperty(nodeId, property));
        }
    }

    private void navigateProperty(PropertyDescriptor property) {
        String nodeId = selectedId;
        Optional<FormModel> model = editor.model();
        if (nodeId == null || model.isEmpty()) {
            return;
        }
        editor.componentOf(nodeId).flatMap(component -> component.property(property.setter()))
                .map(FormCall::statement)
                .ifPresent(statement -> workspace.environment().openSource(file, model.get().line(statement.start())));
    }

    private void rename(String name) {
        String nodeId = selectedId;
        if (nodeId != null) {
            edit("Renomeando", () -> editor.rename(nodeId, name));
        }
    }

    private void applyArgument(int index, JsonNode value) {
        if (constructor == null) {
            return;
        }
        constructor = constructor.withValue(index, value);
        refresh(false);
    }

    private void addToSelection(String paletteClass) {
        if (current == null || current.root() == null) {
            return;
        }
        SnapshotNode target = Optional.ofNullable(selectedId).flatMap(current.root()::find).orElse(current.root());
        while (target != null && !target.hasLayout()) {
            target = current.root().parentOf(target.id()).orElse(null);
        }
        if (target == null && "0".equals(current.root().id())) {
            target = current.root().children().stream().filter(child -> "contentPane".equals(child.role()))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            setStatus("Selecione um container para adicionar " + simpleName(paletteClass));
            return;
        }
        Rectangle bounds = target.bounds();
        Point point = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        SnapshotNode container = target;
        Optional<DropPolicies.Preview> preview = DropPolicies.resolve(current.root(), point, Set.of(),
                this::policyOf, new Dimension(100, 24));
        preview.filter(found -> found.parentNodeId().equals(container.id())).ifPresentOrElse(found -> {
            String constraints = DropPolicies.BORDER.equals(found.policy()) ? null : found.constraints();
            DropPolicies.Preview end = new DropPolicies.Preview(found.parentNodeId(), found.policy(), -1, constraints,
                    found.indicator(), false, DropPolicies.ABSOLUTE.equals(found.policy())
                    ? new Rectangle(10, 10, 100, 24) : null, found.hint());
            dropNew(paletteClass, end);
        }, () -> setStatus("Nao foi possivel adicionar em " + container.label()));
    }

    private void dropNew(String paletteClass, DropPolicies.Preview preview) {
        SnapshotNode root = current == null ? null : current.root();
        edit("Adicionando " + simpleName(paletteClass), () -> editor.addComponent(paletteClass,
                placement(preview), root));
    }

    private DesignEditor.Placement placement(DropPolicies.Preview preview) {
        List<Map.Entry<String, String>> extra = new ArrayList<>();
        if (preview.absoluteBounds() != null) {
            Rectangle area = preview.absoluteBounds();
            extra.add(Map.entry("setBounds", area.x + ", " + area.y + ", " + area.width + ", " + area.height));
        }
        return new DesignEditor.Placement(preview.parentNodeId(), preview.swingIndex(), preview.constraints(),
                Set.of(), extra);
    }

    private String policyOf(String layoutClass) {
        LayoutDescriptor descriptor = session.catalogIfReady().map(catalog -> catalog.layouts().get(layoutClass))
                .orElse(null);
        return descriptor == null ? null : descriptor.dropPolicy();
    }

    private void edit(String label, Supplier<DesignEditor.Applied> operation) {
        if (!editor.editable()) {
            setStatus("Somente visualizacao: " + Optional.ofNullable(editor.readProblem()).orElse("codigo indisponivel"));
            return;
        }
        long ticket = generation.incrementAndGet();
        setStatus(label + "...");
        shadowDebounce.stop();
        worker.submit(() -> {
            try {
                DesignEditor.Applied applied = operation.get();
                ViewResult live = applied.live() == null || applied.live().root() == null ? null
                        : mergeLive(applied.live());
                editor.refresh(live != null ? live.root() : current == null ? null : current.root());
                SwingUtilities.invokeLater(() -> {
                    String notes = applied.notes().isEmpty() ? "" : " - " + String.join("; ", applied.notes());
                    if (live != null && ticket == generation.get()) {
                        apply(live);
                    } else {
                        updateEditState();
                        if (selectedId != null) {
                            select(selectedId);
                        }
                    }
                    setStatus(applied.label() + notes);
                    updateHistoryButtons();
                    if (applied.focus() >= 0 && applied.label().startsWith("Evento")) {
                        reveal(applied.text(), applied.focus());
                    }
                    if (live == null) {
                        shadowRefresh();
                    } else {
                        shadowDebounce.restart();
                    }
                });
            } catch (SourceEditPlanner.Rejected rejected) {
                SwingUtilities.invokeLater(() -> setStatus(rejected.getMessage()));
            } catch (Exception error) {
                log.debug("Falha ao editar {}", className, error);
                SwingUtilities.invokeLater(() -> setStatus("Falha: " + rootMessage(error)));
            }
        });
    }

    private void reveal(String text, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        workspace.environment().openSourceAt(file, line, offset - lineStart);
    }

    private ViewResult mergeLive(ViewResult result) {
        return new ViewResult(result.image(), result.width(), result.height(),
                result.root(), constructor, current == null ? List.of() : current.attempts(),
                null, null, current != null && current.window(),
                current == null ? null : current.title(),
                current == null ? List.of() : current.warnings());
    }

    private void undo() {
        if (editor.history().canUndo()) {
            edit("Desfazendo", editor::undo);
        }
    }

    private void redo() {
        if (editor.history().canRedo()) {
            edit("Refazendo", editor::redo);
        }
    }

    private void preview() {
        setStatus("Abrindo preview...");
        worker.submit(() -> {
            try {
                String error = session.client().preview(className, constructor, viewOptions(session.catalog()));
                SwingUtilities.invokeLater(() -> setStatus(error == null ? "Preview aberto" : "Preview: " + error));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> setStatus("Preview: " + rootMessage(failure)));
            }
        });
    }

    private void contextMenu(String nodeId, Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        boolean editable = editor.editable() && editor.lockReason(nodeId) == null;
        JMenuItem code = new JMenuItem("Ir para o codigo");
        Optional<Integer> line = editor.declarationLine(nodeId);
        code.setEnabled(line.isPresent());
        code.addActionListener(event -> line.ifPresent(found -> workspace.environment().openSource(file, found)));
        menu.add(code);
        JMenuItem events = new JMenuItem("Eventos...");
        events.addActionListener(event -> {
            designInspector.showTab(DesignInspector.EVENTS);
            ensureInspectorVisible();
            refreshSideTabs();
        });
        menu.add(events);
        JMenuItem layout = new JMenuItem("Layout e posicao...");
        layout.addActionListener(event -> {
            designInspector.showTab(DesignInspector.LAYOUT);
            ensureInspectorVisible();
            refreshSideTabs();
        });
        menu.add(layout);
        if (current != null && current.root() != null) {
            current.root().parentOf(nodeId).ifPresent(parent -> {
                JMenuItem up = new JMenuItem("Selecionar o container");
                up.addActionListener(event -> select(parent.id()));
                menu.add(up);
            });
        }
        menu.addSeparator();
        JMenuItem delete = new JMenuItem("Excluir");
        delete.setEnabled(editable && !"0".equals(nodeId));
        delete.addActionListener(event -> delete(nodeId));
        menu.add(delete);
        JMenuItem undoItem = new JMenuItem("Desfazer");
        undoItem.setEnabled(editor.history().canUndo());
        undoItem.addActionListener(event -> undo());
        menu.add(undoItem);
        menu.show(invoker, x, y);
    }

    private void delete(String nodeId) {
        SnapshotNode root = current == null ? null : current.root();
        if (root == null || "0".equals(nodeId)) {
            return;
        }
        String parent = root.parentOf(nodeId).map(SnapshotNode::id).orElse(null);
        String parentComponent = parent == null ? null : editor.links().componentOf(parent).orElse(null);
        selectedComponent = parentComponent;
        selectedId = parent;
        edit("Excluindo", () -> editor.removeComponent(nodeId, root));
    }

    private void ensureInspectorVisible() {
        if (!showInspector) {
            showInspector = true;
            inspectorToggle.setSelected(true);
            storePreference(prefix() + "inspector", true);
            layoutBody();
        }
    }

    private void rebuildTree(SnapshotNode root) {
        java.util.Set<String> expansion = tree.snapshotExpansion();
        tree.setRoot(treeNode(root));
        if (expansion.isEmpty()) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
    }

    private TreeNode<SnapshotNode> treeNode(SnapshotNode snapshot) {
        TreeNode<SnapshotNode> node = new TreeNode<>(snapshot);
        node.setId(snapshot.id());
        String reason = editor.editable() ? editor.lockReason(snapshot.id()) : null;
        node.setLabel(reason == null ? snapshot.label() : snapshot.label() + "  (somente leitura)");
        node.setTooltip(reason == null ? snapshot.className() : snapshot.className() + " - " + reason);
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
        JPanel bar = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(1), UiTokens.space(1),
                        UiTokens.space(1))));

        hierarchyToggle = toggle(new PanelToggleIcon(PanelToggleIcon.Side.LEFT),
                "Mostrar ou ocultar a paleta e a hierarquia", showHierarchy, selected -> {
                    showHierarchy = selected;
                    storePreference(prefix() + "hierarchy", selected);
                    layoutBody();
                });
        inspectorToggle = toggle(new PanelToggleIcon(PanelToggleIcon.Side.RIGHT),
                "Mostrar ou ocultar as propriedades", showInspector, selected -> {
                    showInspector = selected;
                    storePreference(prefix() + "inspector", selected);
                    layoutBody();
                });

        JLabel heading = new JLabel(simpleName(className));
        heading.setFont(UiTokens.fontBold());
        heading.setToolTipText(className);
        heading.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.space(1), 0, UiTokens.space(2)));

        JButton reload = PillButtons.iconAction(JavaIcons.refresh(JavaIcons.SMALL), "Recarregar (compila e renderiza)");
        reload.addActionListener(event -> refresh(true));
        JButton preview = PillButtons.secondary("Preview", JavaIcons.run(JavaIcons.SMALL));
        preview.setToolTipText("Abrir a janela real");
        preview.addActionListener(event -> preview());
        undo.addActionListener(event -> undo());
        redo.addActionListener(event -> redo());
        JComboBox<String> zoom = new JComboBox<>(ZOOMS);
        zoom.setSelectedItem(ZOOM_FIT);
        zoom.setToolTipText("Zoom");
        zoom.addActionListener(event -> {
            String choice = String.valueOf(zoom.getSelectedItem());
            if (ZOOM_FIT.equals(choice)) {
                canvas.setFit();
            } else {
                canvas.setZoom(Integer.parseInt(choice.replace("%", "")) / 100.0);
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        left.setOpaque(false);
        left.add(hierarchyToggle);
        left.add(heading);
        left.add(reload);
        left.add(undo);
        left.add(redo);
        left.add(preview);
        left.add(zoom);

        status.setForeground(UiTokens.muted());
        status.setFont(UiTokens.fontSmall());
        editState.setFont(UiTokens.fontSmall());
        editState.setForeground(UiTokens.muted());
        editState.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.scale(1), UiTokens.scale(6), UiTokens.scale(1),
                        UiTokens.scale(6))));
        problemsChip.putClientProperty("JButton.buttonType", "toolBarButton");
        problemsChip.setFocusable(false);
        problemsChip.setFont(UiTokens.fontSmall());
        problemsChip.setIconTextGap(UiTokens.scale(4));
        problemsChip.setVisible(false);
        problemsChip.addActionListener(event -> problems.setExpanded(!problems.isExpanded()));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(1), 0));
        right.setOpaque(false);
        right.add(problemsChip);
        right.add(status);
        sizeLabel.setForeground(UiTokens.muted());
        sizeLabel.setFont(UiTokens.fontSmall());
        right.add(sizeLabel);
        right.add(editState);
        right.add(inspectorToggle);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void buildContent() {
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
        hierarchyHint.setForeground(UiTokens.muted());
        hierarchyHint.setFont(UiTokens.fontSmall());
        hierarchyCards.setOpaque(false);
        hierarchyCards.add(UiSupport.plainScroll(new JScrollPane(tree)), TREE_CARD);
        hierarchyCards.add(hierarchyHint, TREE_EMPTY);

        leftCards.setOpaque(false);
        leftCards.add(palette, LEFT_PALETTE);
        leftCards.add(hierarchyCards, LEFT_TREE);
        JPanel leftTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.scale(2), 0));
        leftTabs.setOpaque(false);
        leftTabs.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(1), UiTokens.space(1),
                        UiTokens.space(1))));
        ButtonGroup group = new ButtonGroup();
        String initial = preferenceText("left.tab", LEFT_TREE);
        for (String name : List.of(LEFT_PALETTE, LEFT_TREE)) {
            JToggleButton button = new JToggleButton(name, name.equals(initial));
            button.setFocusable(false);
            button.setFont(UiTokens.fontSmall());
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.addActionListener(event -> {
                ((CardLayout) leftCards.getLayout()).show(leftCards, name);
                storePreferenceText("left.tab", name);
            });
            group.add(button);
            leftTabs.add(button);
        }
        ((CardLayout) leftCards.getLayout()).show(leftCards, initial);
        hierarchy.setOpaque(false);
        hierarchy.setMinimumSize(new Dimension(UiTokens.scale(140), 0));
        hierarchy.add(leftTabs, BorderLayout.NORTH);
        hierarchy.add(leftCards, BorderLayout.CENTER);

        message.setEditable(false);
        message.setFont(UiTokens.fontMono());
        message.setLineWrap(false);
        message.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(3), UiTokens.space(3),
                UiTokens.space(3), UiTokens.space(3)));

        JPanel blank = new JPanel();
        blank.setOpaque(false);
        loading.setContent(blank);
        loading.setBlockInput(true);
        loading.setAccentColor(UiTokens.accent());
        loading.setTextColor(UiTokens.foreground());
        loading.setTrackColor(UiTokens.border());
        loading.setOverlayColor(UiTokens.background());

        JScrollPane canvasScroll = UiSupport.plainScroll(new JScrollPane(canvas));
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(canvasScroll, CARD_CANVAS);
        center.add(UiSupport.plainScroll(new JScrollPane(message)), CARD_MESSAGE);
        center.add(loading, CARD_LOADING);

        stage.setOpaque(false);
        stage.setMinimumSize(new Dimension(UiTokens.scale(160), 0));
        center.setMinimumSize(new Dimension(0, UiTokens.scale(80)));
        stageSplit.setTopComponent(center);
        stageSplit.setBottomComponent(problems);
        stageSplit.setResizeWeight(1.0);
        stageSplit.setBorder(null);
        stageSplit.setContinuousLayout(true);
        stageSplit.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                placeProblems();
            }
        });
        if (stageSplit.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI splitUi) {
            splitUi.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseReleased(java.awt.event.MouseEvent event) {
                    if (problems.isExpanded() && stageSplit.getHeight() > 0) {
                        problemsHeight = Math.max(UiTokens.scale(80), stageSplit.getHeight()
                                - stageSplit.getDividerLocation() - stageSplit.getDividerSize());
                    }
                }
            });
        }
        problems.setVisible(false);
        stage.add(stageSplit, BorderLayout.CENTER);
        if (preference("problems.open", false)) {
            problems.setExpanded(true);
        }
        designInspector.setMinimumSize(new Dimension(UiTokens.scale(220), 0));
        body.setOpaque(false);
    }

    private void layoutBody() {
        body.removeAll();
        JComponent content = stage;
        if (showInspector) {
            JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stage, designInspector);
            right.setResizeWeight(1.0);
            right.setBorder(null);
            right.setContinuousLayout(true);
            anchorRight(right);
            content = right;
        }
        if (showHierarchy) {
            JSplitPane left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hierarchy, content);
            left.setResizeWeight(0.0);
            left.setBorder(null);
            left.setContinuousLayout(true);
            left.setDividerLocation(hierarchyWidth);
            left.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
                if (left.getWidth() > 0 && left.getDividerLocation() > 0) {
                    hierarchyWidth = left.getDividerLocation();
                }
            });
            content = left;
        }
        body.add(content, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    private void anchorRight(JSplitPane split) {
        boolean[] positioned = {false};
        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (!positioned[0] && split.getWidth() > 0) {
                    positioned[0] = true;
                    split.setDividerLocation(Math.max(UiTokens.scale(160),
                            split.getWidth() - inspectorWidth - split.getDividerSize()));
                }
            }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (positioned[0] && split.getWidth() > 0) {
                inspectorWidth = Math.max(UiTokens.scale(200),
                        split.getWidth() - split.getDividerLocation() - split.getDividerSize());
            }
        });
    }

    private void showMessage(long ticket, String text) {
        SwingUtilities.invokeLater(() -> {
            if (ticket != generation.get()) {
                return;
            }
            loading.stop();
            showProblems(List.of());
            message.setText(text);
            message.setCaretPosition(0);
            showCard(CARD_MESSAGE);
            showHierarchyHint("Nada para mostrar");
            inspector.clear(" ");
            layoutPanel.clear(" ");
            eventsPanel.clear(" ");
            setStatus(" ");
        });
    }

    private void showLoading(String text) {
        Runnable show = () -> {
            loading.setMessage(text);
            loading.start();
            showProblems(List.of());
            showCard(CARD_LOADING);
            showHierarchyHint("Carregando...");
            inspector.clear(" ");
            layoutPanel.clear(" ");
            eventsPanel.clear(" ");
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private void showProblems(List<ViewWarning> items) {
        Runnable update = () -> {
            problems.show(items);
            boolean visible = !problems.isEmpty();
            problems.setVisible(visible);
            long errors = problems.errors();
            problemsChip.setIcon(new WarningIcon(errors > 0));
            problemsChip.setText(String.valueOf(errors > 0 ? errors : problems.infos()));
            problemsChip.setToolTipText(errors + " erro(s) e " + problems.infos() + " aviso(s): clique para "
                    + "abrir a aba Problemas");
            problemsChip.setVisible(visible);
            placeProblems();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void placeProblems() {
        if (!problems.isVisible() || stageSplit.getHeight() <= 0) {
            stageSplit.revalidate();
            return;
        }
        int collapsed = problems.headerHeight();
        if (problems.isExpanded()) {
            stageSplit.setDividerSize(UiTokens.scale(5));
            stageSplit.setDividerLocation(Math.max(UiTokens.scale(80),
                    stageSplit.getHeight() - problemsHeight - stageSplit.getDividerSize()));
        } else {
            stageSplit.setDividerSize(0);
            stageSplit.setDividerLocation(Math.max(0, stageSplit.getHeight() - collapsed));
        }
    }

    private void showCard(String card) {
        ((CardLayout) center.getLayout()).show(center, card);
    }

    private void showHierarchyHint(String text) {
        hierarchyHint.setText(text);
        ((CardLayout) hierarchyCards.getLayout()).show(hierarchyCards, TREE_EMPTY);
    }

    private ViewOptions viewOptions(ComponentCatalog catalog) {
        Optional<ComponentDescriptor> descriptor = catalog.descriptor(className);
        return new ViewOptions(
                descriptor.map(ComponentDescriptor::designInitOrEmpty).orElse(List.of()),
                catalog.injectionRules(),
                descriptor.map(ComponentDescriptor::designValuesOrEmpty).orElse(Map.of()),
                catalog.options().stubsEnabled());
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

    private String prefix() {
        return compact ? "compact." : "full.";
    }

    private void bindShortcut(KeyStroke stroke, String name, Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static JToggleButton toggle(Icon icon, String tooltip, boolean selected, Consumer<Boolean> onChange) {
        JToggleButton button = new JToggleButton(icon, selected);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(event -> onChange.accept(button.isSelected()));
        return button;
    }

    private static boolean preference(String key, boolean fallback) {
        try {
            return preferences().getBoolean(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String preferenceText(String key, String fallback) {
        try {
            return preferences().get(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void storePreference(String key, boolean value) {
        try {
            preferences().putBoolean(key, value);
        } catch (RuntimeException e) {
            log.debug("Nao foi possivel salvar a preferencia {}: {}", key, e.toString());
        }
    }

    private static void storePreferenceText(String key, String value) {
        try {
            preferences().put(key, value);
        } catch (RuntimeException e) {
            log.debug("Nao foi possivel salvar a preferencia {}: {}", key, e.toString());
        }
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node("dtm/ide/swingdesigner/viewer");
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

    private final class CanvasHooks implements SnapshotCanvas.Hooks {

        @Override
        public boolean editing() {
            return editor.editable() && current != null && current.root() != null;
        }

        @Override
        public Optional<DropPolicies.Preview> preview(Point imagePoint, Set<String> excluded, Dimension size) {
            return DropPolicies.resolve(current == null ? null : current.root(), imagePoint, excluded,
                    SwingViewerPanel.this::policyOf, size);
        }

        @Override
        public boolean accepts(DropPolicies.Preview preview) {
            if (!editor.editable()) {
                return false;
            }
            return editor.componentOf(preview.parentNodeId())
                    .map(component -> !component.locked() || component.kind() == FormComponent.Kind.FIELD)
                    .orElse(false);
        }

        @Override
        public void dropNew(String className, DropPolicies.Preview preview) {
            SwingViewerPanel.this.dropNew(className, preview);
        }

        @Override
        public void move(String nodeId, DropPolicies.Preview preview) {
            SnapshotNode root = current == null ? null : current.root();
            edit("Movendo", () -> editor.moveComponent(nodeId, placement(preview), root));
        }

        @Override
        public void bounds(String nodeId, Rectangle relative) {
            edit("Posicionando", () -> editor.setBounds(nodeId, relative));
        }

        @Override
        public void resize(String nodeId, Dimension size) {
            edit("Redimensionando", () -> editor.setPreferredSize(nodeId, size));
        }

        @Override
        public void delete(String nodeId) {
            SwingViewerPanel.this.delete(nodeId);
        }

        @Override
        public void nudge(String nodeId, int dx, int dy) {
            if (current == null || current.root() == null) {
                return;
            }
            Optional<SnapshotNode> node = current.root().find(nodeId);
            Optional<SnapshotNode> parent = current.root().parentOf(nodeId);
            if (node.isEmpty() || parent.isEmpty()) {
                return;
            }
            Rectangle area = node.get().bounds();
            Rectangle base = parent.get().bounds();
            edit("Posicionando", () -> editor.setBounds(nodeId, new Rectangle(area.x - base.x + dx,
                    area.y - base.y + dy, area.width, area.height)));
        }

        @Override
        public void activate(String nodeId) {
            SnapshotNode node = current == null || current.root() == null ? null
                    : current.root().find(nodeId).orElse(null);
            if (node == null) {
                return;
            }
            designInspector.showTab(DesignInspector.EVENTS);
            ensureInspectorVisible();
            refreshSideTabs();
            worker.submit(() -> {
                Optional<EventDescriptor> action = session.catalog().descriptor(node.className())
                        .map(ComponentDescriptor::eventsOrEmpty).orElse(List.of()).stream()
                        .filter(event -> event.simpleListenerName().equals("ActionListener"))
                        .findFirst();
                if (action.isEmpty() || action.get().methods().isEmpty()) {
                    return;
                }
                EventDescriptor event = action.get();
                EventDescriptor.EventMethod method = event.methods().getFirst();
                Optional<String> handler = editor.handlerOf(nodeId, event, method);
                SwingUtilities.invokeLater(() -> {
                    EventActions actions = new EventActions(nodeId);
                    if (handler.isPresent()) {
                        actions.open(handler.get());
                    } else if (editor.editable()) {
                        actions.add(event, method, editor.suggestHandler(nodeId, method));
                    }
                });
            });
        }

        @Override
        public void contextMenu(String nodeId, Component invoker, int x, int y) {
            SwingViewerPanel.this.contextMenu(nodeId, invoker, x, y);
        }
    }

    private final class EventActions implements EventsPanel.Actions {

        private final String nodeId;

        EventActions(String nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public Optional<String> handlerOf(EventDescriptor event, EventDescriptor.EventMethod method) {
            return editor.handlerOf(nodeId, event, method);
        }

        @Override
        public String suggest(EventDescriptor.EventMethod method) {
            try {
                return editor.suggestHandler(nodeId, method);
            } catch (RuntimeException e) {
                return method.name();
            }
        }

        @Override
        public void add(EventDescriptor event, EventDescriptor.EventMethod method, String handler) {
            edit("Evento " + method.name(), () -> editor.addHandler(nodeId, event, method, handler));
        }

        @Override
        public void remove(EventDescriptor event, EventDescriptor.EventMethod method) {
            edit("Removendo evento " + method.name(), () -> editor.removeHandler(nodeId, event, method, false));
        }

        @Override
        public void open(String handler) {
            Optional<Integer> line = editor.handlerLine(handler);
            if (line.isPresent()) {
                workspace.environment().openSource(file, line.get());
            } else {
                editor.declarationLine(nodeId).ifPresent(found -> workspace.environment().openSource(file, found));
            }
        }
    }

    private final class LayoutActions implements LayoutPanel.Actions {

        private final SnapshotNode node;

        LayoutActions(SnapshotNode node) {
            this.node = node;
        }

        @Override
        public void setLayout(String layoutClass, Map<String, String> values) {
            SnapshotNode root = current == null ? null : current.root();
            if (root == null) {
                return;
            }
            String target = "0".equals(node.id()) && !node.hasLayout() ? root.children().stream()
                    .filter(child -> "contentPane".equals(child.role())).map(SnapshotNode::id).findFirst()
                    .orElse(node.id()) : node.id();
            edit("Trocando o layout", () -> editor.setLayoutChoice(target, layoutClass, new LinkedHashMap<>(values),
                    root));
        }

        @Override
        public void setConstraints(String expression) {
            edit("Ajustando a posicao", () -> editor.setConstraints(node.id(), expression));
        }

        @Override
        public void setBounds(Rectangle bounds) {
            edit("Posicionando", () -> editor.setBounds(node.id(), bounds));
        }

        @Override
        public void setPreferredSize(Dimension size) {
            edit("Redimensionando", () -> editor.setPreferredSize(node.id(), size));
        }

        @Override
        public void reorder(int delta) {
            SnapshotNode root = current == null ? null : current.root();
            if (root == null) {
                return;
            }
            Optional<SnapshotNode> parent = root.parentOf(node.id());
            if (parent.isEmpty()) {
                return;
            }
            int target = delta < 0 ? node.index() - 1 : node.index() + 2;
            int count = parent.get().children().size();
            int index = target >= count ? -1 : Math.max(0, target);
            DropPolicies.Preview preview = new DropPolicies.Preview(parent.get().id(), DropPolicies.FLOW, index,
                    null, node.bounds(), true, null, null);
            edit("Reordenando", () -> editor.moveComponent(node.id(), placement(preview), root));
        }
    }
}
