package dtm.ide.ui;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencyHealthSnapshot;
import dtm.ide.deps.DependencyInventorySnapshot;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.DependencyVulnerability;
import dtm.ide.deps.DependencyVersionOrigin;
import dtm.ide.deps.ManagedDependency;
import dtm.ide.deps.MavenVersionOrder;
import dtm.ide.deps.ResolvedDependency;
import dtm.ide.project.JavaModule;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.skeleton.SkeletonPanel;
import dtm.stools.component.panels.split.SplitPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.component.popup.ModernDialog;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DependencyManagerPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(DependencyManagerPanel.class, key, fallback);
    }

    public interface Host {

        List<JavaModule> modules();

        List<DependencyCoordinate> declaredDependencies(JavaModule module);

        void search(String query, boolean includePreReleases, Consumer<SearchOutcome> onResult);

        void versions(DependencyCoordinate coordinate, boolean includePreReleases,
                      Consumer<List<DependencyVersionChoice>> onResult);

        boolean canInstallLocal(JavaModule module);

        void onLocalRepositoryChanged(Runnable listener);

        void latestVersions(List<DependencyCoordinate> coordinates,
                            Consumer<Map<String, String>> onResult);

        void inventory(JavaModule module, List<DependencyCoordinate> declared,
                       Consumer<DependencyInventorySnapshot> onResult);

        void health(JavaModule module, DependencyInventorySnapshot inventory,
                    Consumer<DependencyHealthSnapshot> onResult);

        void add(JavaModule module, DependencyCoordinate coordinate, Consumer<Boolean> onDone);

        void remove(JavaModule module, DependencyCoordinate coordinate, Consumer<Boolean> onDone);

        void updateVersion(JavaModule module, ManagedDependency dependency, String version,
                           Consumer<Boolean> onDone);

        default void refreshDependencies(JavaModule module, DependencyCoordinate dependency,
                                         Consumer<Boolean> onDone) {
            onDone.accept(false);
        }
    }

    public enum RemoteStatus { OK, FAILED, SKIPPED }

    public record SearchOutcome(List<DependencySearchResult> results, RemoteStatus remote,
                                boolean localUnavailable) {

        public SearchOutcome {
            results = results == null ? List.of() : List.copyOf(results);
            remote = remote == null ? RemoteStatus.OK : remote;
        }
    }

    private enum Tab { BROWSE, INSTALLED, UPDATES, HEALTH }

    private static final List<String> SCOPES =
            List.of("compile", "test", "provided", "runtime", "annotationProcessor");

    private static final int SEARCH_DEBOUNCE_MS = 350;

    private static final String CARD_LIST = "list";
    private static final String CARD_LOADING = "loading";
    private static final String CARD_EMPTY = "empty";

    private final Host host;
    private final Supplier<ModernDialog.ModernDialogBuilder> dialogBuilder;

    private final JComboBox<ModuleItem> moduleSelector = new JComboBox<>();
    private final SegmentedField<Tab> tabs = new SegmentedField<>();
    private final MaskedTextField searchField = new MaskedTextField();
    private final JCheckBox prereleaseCheck =
            new JCheckBox(text("search.includePrerelease", "Incluir pre-releases"));

    private final DefaultListModel<PackageRowRenderer.Row> rows = new DefaultListModel<>();
    private final JList<PackageRowRenderer.Row> packageList = new JList<>(rows);
    private final CardLayout listCards = new CardLayout();
    private final JPanel listArea = new JPanel(listCards);
    private final EmptyStatePanel emptyState = new EmptyStatePanel();

    private final JLabel detailTitle = new JLabel();
    private final JLabel detailCoordinate = new JLabel();
    private final JLabel detailVersions = new JLabel();
    private final JLabel detailPublished = new JLabel();
    private final JLabel detailVersionOrigin = new JLabel();
    private final JTextArea detailHealth = wrappingText();
    private final JTextArea detailPath = wrappingText();
    private final JComboBox<DependencyVersionChoice> versionSelector = new JComboBox<>();
    private final JComboBox<String> scopeSelector = new JComboBox<>(SCOPES.toArray(String[]::new));
    private final JPanel detailBody = new JPanel(new BorderLayout(0, UiTokens.space(2)));
    private final JPanel editForm = new JPanel();
    private final JLabel detailPlaceholder = new JLabel();

    private final JButton addButton =
            PillButtons.primary(text("action.add", "Adicionar"), JavaIcons.create(JavaIcons.SMALL));
    private final JButton updateButton =
            PillButtons.primary(text("action.update", "Atualizar versao"), null);
    private final JButton removeButton =
            PillButtons.secondary(text("action.remove", "Remover"), null);
    private final JButton updateAllButton =
            PillButtons.secondary(text("action.updateAll", "Atualizar todas"), null);

    private final BadgeLabel status = new BadgeLabel(" ", BadgeLabel.Tone.NEUTRAL);
    private final JButton refreshSelectedButton =
            PillButtons.secondary(text("action.redownload", "Baixar novamente"), null);
    private final JButton refreshProjectButton =
            PillButtons.secondary(text("action.refreshDependencies", "Atualizar pacotes"), null);
    private final Timer searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, event -> runSearch());

    private List<DependencySearchResult> searchResults = List.of();
    private SearchOutcome lastSearchOutcome =
            new SearchOutcome(List.of(), RemoteStatus.OK, false);
    private List<DependencyCoordinate> installed = List.of();
    private Map<String, String> latestVersions = Map.of();
    private Map<String, DependencyCoordinate> installedByKey = Map.of();
    private DependencyInventorySnapshot inventory = DependencyInventorySnapshot.empty();
    private DependencyHealthSnapshot health = DependencyHealthSnapshot.empty();
    private boolean inventoryLoaded;
    private boolean updatesLoaded;
    private boolean healthLoaded;
    private long searchGeneration;

    public DependencyManagerPanel(Host host) {
        this(host, ModernDialog::builder);
    }

    public DependencyManagerPanel(Host host,
                                  Supplier<ModernDialog.ModernDialogBuilder> dialogBuilder) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        this.dialogBuilder = dialogBuilder;
        versionSelector.setRenderer(new DependencyVersionChoiceRenderer());
        host.onLocalRepositoryChanged(() -> onUi(this::onLocalRepositoryChanged));
        int pad = UiTokens.space(2);
        setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        setBackground(UiTokens.background());

        add(header(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);

        wireActions();
        UiSupport.quietFocus(this);
        searchField.setFocusable(true);
        packageList.setFocusable(true);
        reloadModules();
    }

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout(0, UiTokens.space(2)));
        header.setOpaque(false);
        header.add(toolBar(), BorderLayout.NORTH);
        header.add(searchRow(), BorderLayout.SOUTH);
        return header;
    }

    private JComponent toolBar() {
        tabs.addSegment(text("tab.browse", "Buscar"), Tab.BROWSE);
        tabs.addSegment(text("tab.installed", "Instaladas"), Tab.INSTALLED);
        tabs.addSegment(text("tab.updates", "Atualizacoes"), Tab.UPDATES);
        tabs.addSegment(text("tab.health", "Saude"), Tab.HEALTH);
        tabs.setAnimated(true);
        tabs.setArc(UiTokens.radius(UiTokens.Radius.SM));
        tabs.setPreferredHeight(UiTokens.scale(PillButtons.FIELD_HEIGHT));
        tabs.setSegmentPadding(UiTokens.space(4));
        tabs.setSelectedIndex(0, false);

        JLabel moduleLabel = new JLabel(text("label.module", "Modulo:"));
        moduleLabel.setFont(UiTokens.fontSmall());
        moduleLabel.setForeground(UiTokens.muted());
        moduleSelector.setPreferredSize(
                new Dimension(UiTokens.scale(180), UiTokens.scale(PillButtons.FIELD_HEIGHT)));

        ToolBarPanel bar = new ToolBarPanel();
        bar.setPaintSurface(true);
        bar.setArc(UiTokens.radius(UiTokens.Radius.MD));
        bar.setItemGap(UiTokens.space(2));
        bar.addItem(new JLabel(JavaIcons.dependency(JavaIcons.SMALL)));
        bar.addItem(tabs);
        bar.addSpacer();
        bar.addItem(moduleLabel);
        bar.addItem(moduleSelector);
        return bar;
    }

    private JComponent searchRow() {
        searchField.putClientProperty("JTextField.placeholderText",
                text("search.placeholder", "Nome da biblioteca ou grupo:artefato"));
        searchField.putClientProperty("JTextField.leadingIcon", JavaIcons.search(JavaIcons.SMALL));
        searchField.setPreferredSize(
                new Dimension(UiTokens.scale(320), UiTokens.scale(PillButtons.FIELD_HEIGHT)));

        prereleaseCheck.setOpaque(false);
        prereleaseCheck.setFont(UiTokens.fontSmall());
        prereleaseCheck.setForeground(UiTokens.muted());

        JPanel row = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        row.setOpaque(false);
        row.add(searchField, BorderLayout.CENTER);
        row.add(prereleaseCheck, BorderLayout.EAST);
        return row;
    }

    private JComponent body() {
        JComponent list = listPane();
        JComponent details = detailsPane();
        list.setMinimumSize(new Dimension(UiTokens.scale(340), UiTokens.scale(240)));
        details.setMinimumSize(new Dimension(UiTokens.scale(380), UiTokens.scale(240)));
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, list, details);
        split.setResizeWeight(0.50);
        split.setDividerLocation(0.50);
        split.setDividerThickness(UiTokens.space(2));
        split.setCollapseOnDoubleClick(true);
        split.setPreferredSize(new Dimension(UiTokens.scale(900), UiTokens.scale(480)));
        split.setBorder(BorderFactory.createEmptyBorder());
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.50));
        return split;
    }

    private JComponent listPane() {
        PackageRowRenderer.install(packageList);
        packageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        ScrollPanel scroll = new ScrollPanel(packageList);
        scroll.setScrollBarThickness(UiTokens.scale(9));
        scroll.setBorder(BorderFactory.createEmptyBorder());

        emptyState.setIcon(JavaIcons.dependency(UiTokens.scale(32)));
        emptyState.setDashedBorder(true);
        emptyState.setArc(UiTokens.radius(UiTokens.Radius.MD));

        listArea.setOpaque(false);
        listArea.add(scroll, CARD_LIST);
        listArea.add(loadingPane(), CARD_LOADING);
        listArea.add(emptyState, CARD_EMPTY);
        return listArea;
    }

    private JComponent loadingPane() {
        SkeletonPanel skeleton = new SkeletonPanel();
        skeleton.clearBlocks();
        for (int line = 0; line < 6; line++) {
            skeleton.addAvatarWithLines(UiTokens.scale(32), 2);
        }
        skeleton.setBlockGap(UiTokens.space(3));

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), UiTokens.space(2),
                UiTokens.space(2), UiTokens.space(2)));
        holder.add(skeleton, BorderLayout.NORTH);
        return holder;
    }

    private JComponent detailsPane() {
        detailTitle.setFont(UiTokens.fontTitle());
        detailTitle.setForeground(UiTokens.foreground());
        detailCoordinate.setFont(UiTokens.fontMono());
        detailCoordinate.setForeground(UiTokens.muted());
        detailVersions.setFont(UiTokens.fontSmall());
        detailVersions.setForeground(UiTokens.muted());
        detailPublished.setFont(UiTokens.fontSmall());
        detailPublished.setForeground(UiTokens.muted());
        detailVersionOrigin.setFont(UiTokens.fontSmall());
        detailVersionOrigin.setForeground(UiTokens.muted());
        detailHealth.setFont(UiTokens.fontSmall());
        detailHealth.setForeground(UiTokens.foreground());
        detailPath.setFont(UiTokens.fontSmall());
        detailPath.setForeground(UiTokens.muted());

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(leftAligned(detailTitle));
        heading.add(Box.createVerticalStrut(UiTokens.space(1)));
        heading.add(leftAligned(detailCoordinate));
        heading.add(Box.createVerticalStrut(UiTokens.space(2)));
        heading.add(leftAligned(detailVersions));
        heading.add(leftAligned(detailPublished));
        heading.add(leftAligned(detailVersionOrigin));
        heading.add(Box.createVerticalStrut(UiTokens.space(1)));
        heading.add(leftAligned(detailHealth));
        heading.add(leftAligned(detailPath));

        detailBody.setOpaque(false);
        detailBody.add(heading, BorderLayout.NORTH);
        detailBody.add(form(), BorderLayout.SOUTH);
        detailBody.setVisible(false);

        detailPlaceholder.setText(text("details.placeholder",
                "Selecione uma dependencia para ver os detalhes."));
        detailPlaceholder.setFont(UiTokens.fontSmall());
        detailPlaceholder.setForeground(UiTokens.muted());

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(4), UiTokens.space(2), UiTokens.space(2)));
        content.add(detailBody, BorderLayout.NORTH);
        content.add(detailPlaceholder, BorderLayout.CENTER);
        ScrollPanel scroll = new ScrollPanel(content);
        scroll.setScrollBarThickness(UiTokens.scale(9));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static JTextArea wrappingText() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(true);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setRows(1);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    private JComponent form() {
        JPanel form = editForm;
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        form.add(field(text("label.version", "Versao:"), versionSelector));
        form.add(Box.createVerticalStrut(UiTokens.space(2)));
        form.add(field(text("label.scope", "Escopo:"), scopeSelector));
        form.add(Box.createVerticalStrut(UiTokens.space(3)));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(addButton);
        actions.add(updateButton);
        actions.add(removeButton);
        actions.add(refreshSelectedButton);
        actions.add(refreshProjectButton);
        actions.add(updateAllButton);
        capHeight(actions);
        form.add(actions);
        return form;
    }

    private static JPanel field(String label, JComponent input) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel caption = new JLabel(label);
        caption.setFont(UiTokens.fontSmall());
        caption.setForeground(UiTokens.muted());
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        capHeight(input);

        row.add(caption);
        row.add(Box.createVerticalStrut(Math.max(2, UiTokens.space(1) / 2)));
        row.add(input);
        capHeight(row);
        return row;
    }

    private static void capHeight(JComponent component) {
        component.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private JComponent statusBar() {
        status.setStyle(BadgeLabel.Style.SOFT);
        status.setSize(BadgeLabel.Size.SM);
        status.setShowDot(true);
        status.setVisible(false);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bar.setOpaque(false);
        bar.add(status);
        return bar;
    }

    private void wireActions() {
        searchDebounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                onQueryChanged();
            }

            @Override public void removeUpdate(DocumentEvent event) {
                onQueryChanged();
            }

            @Override public void changedUpdate(DocumentEvent event) {
                onQueryChanged();
            }
        });
        searchField.addActionListener(event -> {
            searchDebounce.stop();
            onQueryChanged();
            if (currentTab() == Tab.BROWSE) {
                runSearch();
            }
        });

        tabs.addEventListener(SegmentedField.SEGMENT_SELECTED, event -> onUi(this::onTabChanged));
        packageList.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                onRowSelected();
            }
        });
        moduleSelector.addActionListener(event -> reloadInstalled());
        prereleaseCheck.addActionListener(event -> {
            updatesLoaded = false;
            if (currentTab() == Tab.BROWSE) {
                runSearch();
            } else if (currentTab() == Tab.UPDATES) {
                loadUpdates();
            } else {
                onRowSelected();
            }
        });
        versionSelector.addActionListener(event -> refreshAddAvailability());

        addButton.addActionListener(event -> addSelected());
        removeButton.addActionListener(event -> removeSelected());
        updateButton.addActionListener(event -> updateSelected());
        updateAllButton.addActionListener(event -> updateAll());

        refreshSelectedButton.addActionListener(event -> refreshSelectedArtifact());
        refreshProjectButton.addActionListener(event -> refreshProjectArtifacts());
        applyTabState();
    }

    private Tab currentTab() {
        Tab selected = tabs.getSelectedValue();
        return selected == null ? Tab.BROWSE : selected;
    }

    private void onQueryChanged() {
        if (currentTab() == Tab.BROWSE) {
            searchDebounce.restart();
        } else {
            refreshRows();
        }
    }

    private void onTabChanged() {
        searchDebounce.stop();
        applyTabState();
        if (currentTab() != Tab.BROWSE && !inventoryLoaded) {
            showCard(CARD_LOADING);
            setStatus(text("status.resolvingVersions", "Resolvendo versoes efetivas..."),
                    BadgeLabel.Tone.INFO);
            return;
        }
        if (currentTab() == Tab.UPDATES && !updatesLoaded) {
            loadUpdates();
        } else if (currentTab() == Tab.HEALTH && !healthLoaded) {
            loadHealth();
        } else {
            refreshRows();
        }
    }

    private void applyTabState() {
        Tab tab = currentTab();
        addButton.setVisible(tab == Tab.BROWSE);
        removeButton.setVisible(tab == Tab.INSTALLED);
        updateButton.setVisible(tab == Tab.INSTALLED || tab == Tab.UPDATES);
        updateAllButton.setVisible(tab == Tab.UPDATES);
        scopeSelector.setEnabled(tab == Tab.BROWSE);
        editForm.setVisible(tab != Tab.HEALTH);
        updateAllButton.setEnabled(tab == Tab.UPDATES && hasApplicableUpdates());
        refreshSelectedButton.setVisible(tab == Tab.INSTALLED || tab == Tab.UPDATES);
    }

    public void reloadModules() {
        onUi(() -> {
            List<JavaModule> modules = host.modules();
            DefaultComboBoxModel<ModuleItem> model = new DefaultComboBoxModel<>();
            modules.forEach(module -> model.addElement(new ModuleItem(module)));
            moduleSelector.setModel(model);
            moduleSelector.setEnabled(modules.size() > 1);
            reloadInstalled();
        });
    }

    private void reloadInstalled() {
        JavaModule module = selectedModule();
        installed = module == null ? List.of() : host.declaredDependencies(module);
        Map<String, DependencyCoordinate> byKey = new LinkedHashMap<>();
        installed.forEach(coordinate -> byKey.put(coordinate.key(), coordinate));
        installedByKey = byKey;
        inventory = DependencyInventorySnapshot.empty();
        inventoryLoaded = module == null;
        updatesLoaded = false;
        healthLoaded = false;
        health = DependencyHealthSnapshot.empty();
        refreshRows();
        if (module != null) {
            loadInventory(module, List.copyOf(installed));
        }
    }

    private void loadInventory(JavaModule module, List<DependencyCoordinate> declared) {
        if (currentTab() != Tab.BROWSE) {
            showCard(CARD_LOADING);
            setStatus(text("status.resolvingVersions", "Resolvendo versoes efetivas..."),
                    BadgeLabel.Tone.INFO);
        }
        host.inventory(module, declared, snapshot -> onUi(() -> {
            if (!module.equals(selectedModule())) {
                return;
            }
            inventory = snapshot == null
                    ? new DependencyInventorySnapshot(List.of(), List.of(), true) : snapshot;
            inventoryLoaded = true;
            if (currentTab() == Tab.HEALTH) {
                loadHealth();
            } else if (currentTab() == Tab.UPDATES) {
                loadUpdates();
            } else {
                refreshRows();
                if (currentTab() == Tab.INSTALLED) {
                    setStatus(inventory.resolutionFailed()
                                    ? text("status.resolutionPartial",
                                            "Algumas versoes nao puderam ser resolvidas.")
                                    : text("status.versionsResolved", "Versoes efetivas resolvidas."),
                            inventory.resolutionFailed()
                                    ? BadgeLabel.Tone.WARNING : BadgeLabel.Tone.NEUTRAL);
                }
            }
        }));
    }

    private void runSearch() {
        String query = searchField.getText();
        long request = ++searchGeneration;
        if (query == null || query.isBlank()) {
            searchResults = List.of();
            refreshRows();
            return;
        }
        showCard(CARD_LOADING);
        setStatus(text("status.searching", "Buscando dependencias locais e na web..."),
                BadgeLabel.Tone.INFO);
        host.search(query, acceptablePreRelease(), outcome -> onUi(() -> {
            if (request != searchGeneration) {
                return;
            }
            searchResults = outcome.results();
            lastSearchOutcome = outcome;
            applySearchStatus(outcome);
            refreshRows();
        }));
    }

    private void applySearchStatus(SearchOutcome outcome) {
        if (outcome.remote() == RemoteStatus.SKIPPED) {
            setStatus(text("status.searchLocalOnly",
                            "Exibindo apenas o repositorio Maven local."),
                    BadgeLabel.Tone.NEUTRAL);
        } else if (outcome.remote() == RemoteStatus.FAILED && !outcome.results().isEmpty()) {
            setStatus(text("status.searchPartial",
                    "Maven Central indisponivel; exibindo resultados locais."),
                    BadgeLabel.Tone.WARNING);
        } else if (outcome.remote() == RemoteStatus.FAILED) {
            setStatus(text("status.searchFailed",
                            "Nao foi possivel consultar o Maven Central. Verifique a conexao."),
                    BadgeLabel.Tone.DANGER);
        } else if (outcome.localUnavailable()) {
            setStatus(text("status.localUnavailable",
                            "Repositorio Maven local indisponivel; exibindo resultados da web."),
                    BadgeLabel.Tone.WARNING);
        } else {
            setStatus(outcome.results().size() + " "
                    + text("status.results", "resultado(s)"), BadgeLabel.Tone.NEUTRAL);
        }
    }

    private void onLocalRepositoryChanged() {
        if (currentTab() == Tab.BROWSE && searchField.getText() != null
                && !searchField.getText().isBlank()) {
            runSearch();
        }
    }

    private void loadUpdates() {
        if (!inventoryLoaded) {
            showCard(CARD_LOADING);
            return;
        }
        if (installed.isEmpty()) {
            latestVersions = Map.of();
            updatesLoaded = true;
            refreshRows();
            return;
        }
        showCard(CARD_LOADING);
        setStatus(text("status.checkingVersions", "Consultando versoes..."), BadgeLabel.Tone.INFO);
        host.latestVersions(inventory.effectiveDeclared(), versions -> onUi(() -> {
            latestVersions = versions == null ? Map.of() : versions;
            updatesLoaded = true;
            refreshRows();
            setStatus(text("status.updatesChecked", "Versoes conferidas."), BadgeLabel.Tone.NEUTRAL);
        }));
    }

    private void loadHealth() {
        JavaModule module = selectedModule();
        if (module == null) {
            health = DependencyHealthSnapshot.empty();
            healthLoaded = true;
            refreshRows();
            return;
        }
        if (!inventoryLoaded) {
            showCard(CARD_LOADING);
            return;
        }
        showCard(CARD_LOADING);
        setStatus(text("status.checkingHealth", "Analisando dependencias..."),
                BadgeLabel.Tone.INFO);
        host.health(module, inventory, snapshot -> onUi(() -> {
            if (!module.equals(selectedModule())) {
                return;
            }
            health = snapshot == null ? DependencyHealthSnapshot.empty() : snapshot;
            healthLoaded = true;
            refreshRows();
            if (health.graphFailed() || health.vulnerabilityLookupFailed()) {
                setStatus(text("status.healthPartial",
                        "Analise parcial; verifique a conexao e a ferramenta de build."),
                        BadgeLabel.Tone.WARNING);
            } else {
                setStatus(text("status.healthChecked", "Saude das dependencias verificada."),
                        BadgeLabel.Tone.NEUTRAL);
            }
        }));
    }

    private void refreshRows() {
        DependencyCoordinate previous = selectedCoordinate();
        List<PackageRowRenderer.Row> built = switch (currentTab()) {
            case BROWSE -> browseRows();
            case INSTALLED -> installedRows();
            case UPDATES -> updateRows();
            case HEALTH -> healthRows();
        };

        rows.clear();
        built.forEach(rows::addElement);
        applyTabState();

        if (built.isEmpty()) {
            applyEmptyState();
            showCard(CARD_EMPTY);
            onRowSelected();
            return;
        }
        showCard(CARD_LIST);
        restoreSelection(previous, built);
    }

    private List<PackageRowRenderer.Row> browseRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        for (DependencySearchResult result : searchResults) {
            DependencyCoordinate coordinate = result.coordinate();
            DependencyCoordinate declared = installedByKey.get(coordinate.key());
            String meta = coordinate.groupId()
                    + separator() + coordinate.version()
                    + separator() + result.versionCount() + " "
                    + text("label.versionsShort", "versoes")
                    + publishedSuffix(result.lastUpdated());
            String primary = result.local() ? text("badge.local", "local")
                    : declared == null ? "" : text("badge.installed", "instalada");
            String secondary = result.local() && declared != null
                    ? text("badge.installed", "instalada") : "";
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(), meta,
                    primary, BadgeLabel.Tone.SUCCESS, secondary, BadgeLabel.Tone.NEUTRAL));
        }
        return built;
    }

    private List<PackageRowRenderer.Row> installedRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        for (DependencyCoordinate coordinate : filtered(installed)) {
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(),
                    coordinate.groupId() + separator() + displayVersion(coordinate),
                    coordinate.scope(),
                    coordinate.isTestScope() ? BadgeLabel.Tone.INFO : BadgeLabel.Tone.NEUTRAL));
        }
        return built;
    }

    private List<PackageRowRenderer.Row> updateRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        for (DependencyCoordinate coordinate : filtered(installed)) {
            ManagedDependency managed = managedOf(coordinate);
            String current = resolvedVersion(coordinate);
            String latest = latestVersions.get(coordinate.key());
            if (managed == null || !managed.versionEditable() || current.isBlank()
                    || !isNewerVersion(latest, current)) {
                continue;
            }
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(),
                    coordinate.groupId() + separator()
                            + current + "  →  " + latest,
                    text("badge.updateAvailable", "atualizacao"), BadgeLabel.Tone.WARNING));
        }
        return built;
    }

    private List<PackageRowRenderer.Row> healthRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        Map<String, ResolvedDependency> unique = new LinkedHashMap<>();
        for (ResolvedDependency dependency : health.dependencies()) {
            if (dependency.coordinate() != null) {
                unique.putIfAbsent(dependency.coordinate().notation(), dependency);
            }
        }
        for (ResolvedDependency dependency : unique.values()) {
            DependencyCoordinate coordinate = dependency.coordinate();
            if (!matches(coordinate)) {
                continue;
            }
            List<DependencyVulnerability> vulnerabilities = health.vulnerabilitiesOf(coordinate);
            String badge;
            BadgeLabel.Tone tone;
            if (!vulnerabilities.isEmpty()) {
                badge = vulnerabilities.size() + " " + text("badge.vulnerabilities", "vulnerabilidade(s)");
                tone = BadgeLabel.Tone.DANGER;
            } else if (dependency.conflict()) {
                badge = text("badge.conflict", "conflito");
                tone = BadgeLabel.Tone.WARNING;
            } else if (dependency.transitive()) {
                badge = text("badge.transitive", "transitiva");
                tone = BadgeLabel.Tone.INFO;
            } else {
                badge = text("badge.direct", "direta");
                tone = BadgeLabel.Tone.NEUTRAL;
            }
            String meta = coordinate.groupId() + separator()
                    + (coordinate.hasVersion() ? coordinate.version()
                    : text("value.inherited", "(herdada)"));
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(), meta,
                    badge, tone));
        }
        return built;
    }

    private boolean matches(DependencyCoordinate coordinate) {
        String query = searchField.getText();
        return query == null || query.isBlank()
                || coordinate.key().toLowerCase(Locale.ROOT)
                .contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private List<DependencyCoordinate> filtered(List<DependencyCoordinate> coordinates) {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            return coordinates;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return coordinates.stream()
                .filter(coordinate -> coordinate.key().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private void applyEmptyState() {
        switch (currentTab()) {
            case BROWSE -> {
                boolean searched = searchField.getText() != null
                        && !searchField.getText().isBlank();
                emptyState.setTitle(searched
                        ? text("empty.noResults.title", "Nenhum resultado")
                        : text("empty.search.title", "Busque uma biblioteca"));
                emptyState.setDescription(searched
                        ? text("empty.noResults.description",
                                "Tente outro termo, ou cole a coordenada grupo:artefato.")
                        : text("empty.search.description",
                                "Digite o nome para consultar o repositorio local e o Maven Central."));
            }
            case INSTALLED -> {
                emptyState.setTitle(text("empty.installed.title", "Nenhuma dependencia declarada"));
                emptyState.setDescription(text("empty.installed.description",
                        "Adicione uma biblioteca pela aba de busca."));
            }
            case UPDATES -> {
                emptyState.setTitle(text("empty.updates.title", "Tudo atualizado"));
                emptyState.setDescription(text("empty.updates.description",
                        "Nenhuma dependencia declarada tem versao estavel mais nova."));
            }
            case HEALTH -> {
                emptyState.setTitle(text("empty.health.title", "Nenhuma dependencia resolvida"));
                emptyState.setDescription(text("empty.health.description",
                        "Execute a resolucao novamente ou confira a ferramenta de build."));
            }
        }
    }

    private void restoreSelection(DependencyCoordinate previous,
                                  List<PackageRowRenderer.Row> built) {
        if (previous == null) {
            onRowSelected();
            return;
        }
        for (int index = 0; index < built.size(); index++) {
            if (built.get(index).coordinate().sameArtifact(previous)) {
                packageList.setSelectedIndex(index);
                return;
            }
        }
        packageList.clearSelection();
        onRowSelected();
    }

    private void showCard(String card) {
        listCards.show(listArea, card);
    }

    private void onRowSelected() {
        DependencyCoordinate coordinate = selectedCoordinate();
        boolean hasSelection = coordinate != null;
        ManagedDependency managed = hasSelection ? managedOf(coordinate) : null;
        DependencySearchResult searchResult = hasSelection ? searchResultOf(coordinate) : null;
        detailBody.setVisible(hasSelection);
        detailPlaceholder.setVisible(!hasSelection);
        addButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
        boolean updateAllowed = hasSelection && (currentTab() == Tab.BROWSE
                || managed != null && managed.versionEditable());
        updateButton.setEnabled(updateAllowed);
        updateButton.setToolTipText(hasSelection && !updateAllowed
                && (currentTab() == Tab.INSTALLED || currentTab() == Tab.UPDATES)
                ? text("details.externallyManaged",
                        "Versao gerenciada por um parent ou BOM externo; altere-a na origem.")
                : null);
        if (!hasSelection) {
            versionSelector.setModel(new DefaultComboBoxModel<>());
            detailVersionOrigin.setText("");
            detailHealth.setText("");
            detailPath.setText("");
            return;
        }

        detailTitle.setText(coordinate.artifactId());
        detailCoordinate.setText(coordinate.key());
        detailVersions.setText(versionSummary(coordinate));
        detailPublished.setText(publishedSummary(coordinate));
        detailVersionOrigin.setText(currentTab() == Tab.BROWSE
                ? localRepositorySummary(searchResult) : versionOriginSummary(managed));
        updateHealthDetails(coordinate);
        scopeSelector.setSelectedItem(SCOPES.contains(coordinate.scope())
                ? coordinate.scope() : SCOPES.get(0));

        String current = currentTab() == Tab.BROWSE
                ? safeLiteralVersion(coordinate.version()) : resolvedVersion(coordinate);
        boolean currentLocal = searchResult != null
                && searchResult.localVersions().contains(current);
        boolean currentRemote = searchResult != null && searchResult.remote() && !currentLocal;
        versionSelector.setModel(current.isBlank()
                ? new DefaultComboBoxModel<>()
                : new DefaultComboBoxModel<>(new DependencyVersionChoice[]{
                        new DependencyVersionChoice(current, currentLocal, currentRemote)}));
        if (currentTab() == Tab.HEALTH) {
            return;
        }
        host.versions(coordinate, acceptablePreRelease(), versions -> onUi(() -> {
            DependencyCoordinate stillSelected = selectedCoordinate();
            if (stillSelected == null || !stillSelected.sameArtifact(coordinate)) {
                return;
            }
            List<DependencyVersionChoice> offered = currentTab() == Tab.BROWSE
                    ? versions : versions.stream().filter(DependencyVersionChoice::remote).toList();
            if (offered.isEmpty()) {
                refreshAddAvailability();
                return;
            }
            versionSelector.setModel(new DefaultComboBoxModel<>(
                    offered.toArray(DependencyVersionChoice[]::new)));
            String preferred = currentTab() == Tab.UPDATES
                    ? latestVersions.getOrDefault(coordinate.key(), offered.getFirst().version())
                    : offered.stream().anyMatch(choice -> choice.version().equals(current))
                    ? current : offered.getFirst().version();
            selectVersion(preferred);
            refreshAddAvailability();
        }));
        refreshAddAvailability();
    }

    private void updateHealthDetails(DependencyCoordinate coordinate) {
        if (currentTab() != Tab.HEALTH) {
            setWrappedText(detailHealth, "");
            setWrappedText(detailPath, "");
            return;
        }
        ResolvedDependency resolved = health.dependencies().stream()
                .filter(dependency -> dependency.coordinate() != null
                        && dependency.coordinate().notation().equals(coordinate.notation()))
                .findFirst().orElse(null);
        List<DependencyVulnerability> vulnerabilities = health.vulnerabilitiesOf(coordinate);
        if (!vulnerabilities.isEmpty()) {
            setWrappedText(detailHealth, text("details.vulnerabilities", "Vulnerabilidades:")
                    + "\n" + vulnerabilities.stream().map(DependencyVulnerability::id)
                    .distinct().map(id -> "• " + id)
                    .reduce((left, right) -> left + "\n" + right).orElse(""));
        } else if (resolved != null && resolved.conflict()) {
            setWrappedText(detailHealth, text("details.conflict", "Conflito de versao detectado")
                    + (resolved.requestedVersion().isBlank() ? ""
                    : ": " + resolved.requestedVersion() + " → " + coordinate.version()));
        } else {
            setWrappedText(detailHealth, text("details.noKnownVulnerability",
                    "Nenhuma vulnerabilidade conhecida encontrada."));
        }
        setWrappedText(detailPath, resolved == null || resolved.path().isEmpty() ? ""
                : text("details.path", "Caminho:") + "\n"
                + String.join("\n  → ", resolved.path()));
    }

    private ManagedDependency managedOf(DependencyCoordinate coordinate) {
        return coordinate == null ? null : inventory.dependency(coordinate.key());
    }

    private String resolvedVersion(DependencyCoordinate coordinate) {
        ManagedDependency managed = managedOf(coordinate);
        if (managed != null && managed.versionResolved()) {
            return managed.resolvedVersion();
        }
        return inventoryLoaded ? safeLiteralVersion(coordinate.version()) : "";
    }

    private String displayVersion(DependencyCoordinate coordinate) {
        String version = resolvedVersion(coordinate);
        if (!version.isBlank()) {
            ManagedDependency managed = managedOf(coordinate);
            return version + (managed != null && managed.managed()
                    ? " " + text("value.managed", "(gerenciada)") : "");
        }
        return inventoryLoaded
                ? text("value.unresolved", "(nao resolvida)")
                : text("value.resolving", "(resolvendo...)");
    }

    private static String safeLiteralVersion(String version) {
        return version == null || ManagedDependency.isPlaceholder(version) ? "" : version;
    }

    private String versionOriginSummary(ManagedDependency managed) {
        if (managed == null || currentTab() == Tab.BROWSE || currentTab() == Tab.HEALTH) {
            return "";
        }
        String description = switch (managed.versionOrigin()) {
            case DIRECT -> text("details.origin.direct", "declarada diretamente");
            case LOCAL_PROPERTY -> text("details.origin.property", "propriedade local")
                    + (managed.propertyName().isBlank() ? "" : " “" + managed.propertyName() + "”");
            case LOCAL_MANAGEMENT -> text("details.origin.management",
                    "dependencyManagement local");
            case EXTERNAL_MANAGEMENT -> text("details.origin.external",
                    "parent ou BOM externo (somente leitura)");
            case UNRESOLVED -> text("details.origin.unresolved", "origem nao resolvida");
        };
        String file = managed.sourceFile() == null ? ""
                : " · " + managed.sourceFile().getFileName();
        return text("details.versionOrigin", "Origem da versao:") + " " + description + file;
    }

    private static void setWrappedText(JTextArea area, String value) {
        String content = value == null ? "" : value;
        area.setText(content);
        area.setRows(Math.max(1, Math.min(8, (int) content.lines().count())));
        area.revalidate();
    }

    private String versionSummary(DependencyCoordinate coordinate) {
        for (DependencySearchResult result : searchResults) {
            if (result.coordinate().sameArtifact(coordinate)) {
                return result.versionCount() + " "
                        + text("label.versionsAvailable", "versoes publicadas");
            }
        }
        String latest = latestVersions.get(coordinate.key());
        return latest == null || latest.isBlank()
                ? ""
                : text("label.latest", "Mais recente:") + " " + latest;
    }

    private String publishedSummary(DependencyCoordinate coordinate) {
        for (DependencySearchResult result : searchResults) {
            if (result.coordinate().sameArtifact(coordinate) && result.lastUpdated() > 0) {
                return text("label.published", "Publicada em:") + " "
                        + formatDate(result.lastUpdated());
            }
        }
        return "";
    }

    private DependencySearchResult searchResultOf(DependencyCoordinate coordinate) {
        return searchResults.stream().filter(result -> result.coordinate().sameArtifact(coordinate))
                .findFirst().orElse(null);
    }

    private String localRepositorySummary(DependencySearchResult result) {
        if (result == null || !result.local() || result.localRepository() == null) {
            return "";
        }
        return text("details.localRepository", "Repositorio local:") + " "
                + result.localRepository();
    }

    private void selectVersion(String version) {
        for (int index = 0; index < versionSelector.getItemCount(); index++) {
            DependencyVersionChoice choice = versionSelector.getItemAt(index);
            if (choice != null && choice.version().equals(version)) {
                versionSelector.setSelectedIndex(index);
                return;
            }
        }
    }

    private void refreshAddAvailability() {
        if (currentTab() != Tab.BROWSE) {
            return;
        }
        DependencyVersionChoice choice = selectedVersionChoice();
        JavaModule module = selectedModule();
        boolean blocked = choice != null && choice.localOnly()
                && !host.canInstallLocal(module);
        addButton.setEnabled(selectedCoordinate() != null && choice != null
                && !choice.version().isBlank() && !blocked);
        addButton.setToolTipText(blocked ? text("details.gradleMavenLocalRequired",
                "Adicione mavenLocal() aos repositorios do Gradle para usar esta versao local.")
                : null);
        if (blocked) {
            setStatus(text("status.gradleMavenLocalRequired",
                            "Esta versao existe apenas localmente; configure mavenLocal() no Gradle."),
                    BadgeLabel.Tone.WARNING);
        } else if (currentTab() == Tab.BROWSE && !searchResults.isEmpty()) {
            applySearchStatus(lastSearchOutcome);
        }
    }

    private DependencyVersionChoice selectedVersionChoice() {
        return (DependencyVersionChoice) versionSelector.getSelectedItem();
    }

    private String publishedSuffix(long lastUpdated) {
        return lastUpdated <= 0 ? "" : separator() + formatDate(lastUpdated);
    }

    private static String formatDate(long epochMillis) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(epochMillis));
    }

    private static String separator() {
        return "  ·  ";
    }

    private boolean acceptablePreRelease() {
        return prereleaseCheck.isSelected();
    }

    private JavaModule selectedModule() {
        ModuleItem item = (ModuleItem) moduleSelector.getSelectedItem();
        return item == null ? null : item.module();
    }

    private DependencyCoordinate selectedCoordinate() {
        PackageRowRenderer.Row row = packageList.getSelectedValue();
        return row == null ? null : row.coordinate();
    }

    private void addSelected() {
        DependencyCoordinate selected = selectedCoordinate();
        JavaModule module = selectedModule();
        if (selected == null || module == null) {
            return;
        }
        DependencyVersionChoice choice = selectedVersionChoice();
        if (choice == null || choice.version().isBlank()
                || choice.localOnly() && !host.canInstallLocal(module)) {
            refreshAddAvailability();
            return;
        }
        String version = choice.version();
        String scope = (String) scopeSelector.getSelectedItem();
        DependencyCoordinate coordinate = selected.withVersion(version).withScope(scope);

        addButton.setEnabled(false);
        host.add(module, coordinate, changed -> onUi(() -> {
            addButton.setEnabled(true);
            reportChange(changed, text("status.added", "Adicionada:") + " "
                    + coordinate.notation());
        }));
    }

    private void removeSelected() {
        DependencyCoordinate selected = selectedCoordinate();
        JavaModule module = selectedModule();
        if (selected == null || module == null) {
            return;
        }
        host.remove(module, selected, changed -> onUi(() ->
                reportChange(changed, text("status.removed", "Removida:") + " "
                        + selected.key())));
    }

    private void updateSelected() {
        DependencyCoordinate selected = selectedCoordinate();
        JavaModule module = selectedModule();
        if (selected == null || module == null) {
            return;
        }
        ManagedDependency managed = managedOf(selected);
        if (managed == null || !managed.versionEditable()) {
            setStatus(text("status.managedExternally",
                    "A versao e gerenciada por um parent ou BOM externo e nao pode ser alterada aqui."),
                    BadgeLabel.Tone.WARNING);
            return;
        }
        String current = resolvedVersion(selected);
        DependencyVersionChoice choice = selectedVersionChoice();
        String target = choice == null ? "" : choice.version();
        if (target == null || target.isBlank() || target.equals(current)) {
            setStatus(text("status.upToDate", "Ja esta na versao mais recente."),
                    BadgeLabel.Tone.NEUTRAL);
            return;
        }
        String preview = selected.key() + "\n" + current + "  →  " + target
                + updateOriginPreview(managed);
        if (!confirmUpdate(preview)) {
            return;
        }
        host.updateVersion(module, managed, target, changed -> onUi(() ->
                reportChange(changed, selected.key() + " → " + target)));
    }

    private void updateAll() {
        JavaModule module = selectedModule();
        if (module == null) {
            return;
        }
        List<Map.Entry<ManagedDependency, String>> pending = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            DependencyCoordinate coordinate = rows.get(index).coordinate();
            ManagedDependency managed = managedOf(coordinate);
            if (managed == null || !managed.versionEditable()) {
                continue;
            }
            String latest = latestVersions.get(coordinate.key());
            if (isNewerVersion(latest, managed.resolvedVersion())) {
                pending.add(Map.entry(managed, latest));
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        String preview = pending.stream()
                .map(entry -> entry.getKey().key() + ": " + entry.getKey().resolvedVersion()
                        + " → " + entry.getValue() + updateOriginPreview(entry.getKey()))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        if (!confirmUpdate(preview)) {
            return;
        }
        updateAllButton.setEnabled(false);
        applyNextUpdate(module, pending, 0, 0);
    }

    private void refreshSelectedArtifact() {
        DependencyCoordinate dependency = selectedCoordinate();
        JavaModule module = selectedModule();
        if (dependency == null || module == null) {
            return;
        }
        refreshSelectedButton.setEnabled(false);
        host.refreshDependencies(module, dependency, successful -> onUi(() -> {
            refreshSelectedButton.setEnabled(true);
            reportRefresh(successful, text("status.redownloaded", "Dependencia baixada novamente:")
                    + " " + dependency.notation());
        }));
    }

    private void refreshProjectArtifacts() {
        refreshProjectButton.setEnabled(false);
        host.refreshDependencies(null, null, successful -> onUi(() -> {
            refreshProjectButton.setEnabled(true);
            reportRefresh(successful,
                    text("status.dependenciesRefreshed", "Pacotes do projeto atualizados"));
        }));
    }

    private void reportRefresh(boolean successful, String message) {
        setStatus(successful ? message
                        : text("status.refreshDependenciesFailed", "Falha ao atualizar os pacotes"),
                successful ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.DANGER);
        if (successful) {
            reloadInstalled();
        }
    }

    private boolean confirmUpdate(String preview) {
        int answer = dialogBuilder.get()
                .type(ModernDialog.Type.QUESTION)
                .title(text("confirm.update.title", "Confirmar atualizacao"))
                .message(text("confirm.update.message", "As seguintes versoes serao alteradas:")
                        + "\n\n" + preview)
                .option(text("confirm.update.apply", "Atualizar"), 0)
                .option(text("confirm.update.cancel", "Cancelar"), 1)
                .show();
        return answer == 0;
    }

    private void applyNextUpdate(JavaModule module,
                                 List<Map.Entry<ManagedDependency, String>> pending,
                                 int index, int applied) {
        if (index >= pending.size()) {
            onUi(() -> {
                updateAllButton.setEnabled(true);
                setStatus(applied + " " + text("status.updated", "atualizada(s)"),
                        applied > 0 ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.NEUTRAL);
                reloadInstalled();
            });
            return;
        }
        Map.Entry<ManagedDependency, String> target = pending.get(index);
        host.updateVersion(module, target.getKey(), target.getValue(), changed ->
                applyNextUpdate(module, pending, index + 1,
                        Boolean.TRUE.equals(changed) ? applied + 1 : applied));
    }

    private String updateOriginPreview(ManagedDependency managed) {
        if (managed.versionOrigin() == DependencyVersionOrigin.LOCAL_PROPERTY) {
            return "\n  " + text("confirm.update.property", "Propriedade atualizada:")
                    + " " + managed.propertyName();
        }
        if (managed.versionOrigin() == DependencyVersionOrigin.LOCAL_MANAGEMENT) {
            return "\n  " + text("confirm.update.management",
                    "Entrada atualizada no dependencyManagement");
        }
        return "";
    }

    private boolean hasApplicableUpdates() {
        for (DependencyCoordinate coordinate : installed) {
            ManagedDependency managed = managedOf(coordinate);
            if (managed != null && managed.versionEditable()
                    && isNewerVersion(latestVersions.get(coordinate.key()),
                    managed.resolvedVersion())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNewerVersion(String candidate, String current) {
        return candidate != null && !candidate.isBlank()
                && current != null && !current.isBlank()
                && MavenVersionOrder.compare(candidate, current) > 0;
    }

    private void reportChange(Boolean changed, String done) {
        if (Boolean.TRUE.equals(changed)) {
            setStatus(done, BadgeLabel.Tone.SUCCESS);
        } else {
            setStatus(text("status.unchanged", "Nada mudou no arquivo de build."),
                    BadgeLabel.Tone.WARNING);
        }
        reloadInstalled();
    }

    private void setStatus(String message, BadgeLabel.Tone tone) {
        boolean visible = message != null && !message.isBlank();
        status.setVisible(visible);
        if (visible) {
            status.setTone(tone);
            status.setText(message);
        }
    }

    private static void onUi(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private record ModuleItem(JavaModule module) {
        @Override
        public String toString() {
            return module.name();
        }
    }
}
