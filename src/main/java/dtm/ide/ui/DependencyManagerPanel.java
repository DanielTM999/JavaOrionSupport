package dtm.ide.ui;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.project.JavaModule;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.skeleton.SkeletonPanel;
import dtm.stools.component.panels.split.SplitPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
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

public final class DependencyManagerPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(DependencyManagerPanel.class, key, fallback);
    }

    public interface Host {

        List<JavaModule> modules();

        List<DependencyCoordinate> declaredDependencies(JavaModule module);

        void search(String query, Consumer<SearchOutcome> onResult);

        void versions(DependencyCoordinate coordinate, Consumer<List<String>> onResult);

        void latestVersions(List<DependencyCoordinate> coordinates,
                            Consumer<Map<String, String>> onResult);

        void add(JavaModule module, DependencyCoordinate coordinate, Consumer<Boolean> onDone);

        void remove(JavaModule module, DependencyCoordinate coordinate, Consumer<Boolean> onDone);

        void updateVersion(JavaModule module, DependencyCoordinate coordinate, String version,
                           Consumer<Boolean> onDone);
    }

    public record SearchOutcome(List<MavenCentralClient.SearchResult> results, boolean failed) {

        public static SearchOutcome of(List<MavenCentralClient.SearchResult> results) {
            return new SearchOutcome(results == null ? List.of() : results, false);
        }

        public static SearchOutcome failure() {
            return new SearchOutcome(List.of(), true);
        }
    }

    private enum Tab { BROWSE, INSTALLED, UPDATES }

    private static final List<String> SCOPES =
            List.of("compile", "test", "provided", "runtime", "annotationProcessor");

    private static final int SEARCH_DEBOUNCE_MS = 350;

    private static final String CARD_LIST = "list";
    private static final String CARD_LOADING = "loading";
    private static final String CARD_EMPTY = "empty";

    private final Host host;

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
    private final JComboBox<String> versionSelector = new JComboBox<>();
    private final JComboBox<String> scopeSelector = new JComboBox<>(SCOPES.toArray(String[]::new));
    private final JPanel detailBody = new JPanel(new BorderLayout(0, UiTokens.space(2)));
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
    private final Timer searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, event -> runSearch());

    private List<MavenCentralClient.SearchResult> searchResults = List.of();
    private List<DependencyCoordinate> installed = List.of();
    private Map<String, String> latestVersions = Map.of();
    private Map<String, DependencyCoordinate> installedByKey = Map.of();
    private boolean updatesLoaded;

    public DependencyManagerPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
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
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, listPane(), detailsPane());
        split.setResizeWeight(0.62);
        split.setDividerThickness(UiTokens.space(2));
        split.setBorder(BorderFactory.createEmptyBorder());
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

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(leftAligned(detailTitle));
        heading.add(Box.createVerticalStrut(UiTokens.space(1)));
        heading.add(leftAligned(detailCoordinate));
        heading.add(Box.createVerticalStrut(UiTokens.space(2)));
        heading.add(leftAligned(detailVersions));
        heading.add(leftAligned(detailPublished));

        detailBody.setOpaque(false);
        detailBody.add(heading, BorderLayout.NORTH);
        detailBody.add(form(), BorderLayout.SOUTH);
        detailBody.setVisible(false);

        detailPlaceholder.setText(text("details.placeholder",
                "Selecione uma dependencia para ver os detalhes."));
        detailPlaceholder.setFont(UiTokens.fontSmall());
        detailPlaceholder.setForeground(UiTokens.muted());

        JPanel details = new JPanel(new BorderLayout());
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(4), UiTokens.space(2), UiTokens.space(2)));
        details.add(detailBody, BorderLayout.NORTH);
        details.add(detailPlaceholder, BorderLayout.CENTER);
        return details;
    }

    private JComponent form() {
        JPanel form = new JPanel();
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
            onRowSelected();
            if (currentTab() == Tab.UPDATES) {
                loadUpdates();
            }
        });

        addButton.addActionListener(event -> addSelected());
        removeButton.addActionListener(event -> removeSelected());
        updateButton.addActionListener(event -> updateSelected());
        updateAllButton.addActionListener(event -> updateAll());

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
        if (currentTab() == Tab.UPDATES && !updatesLoaded) {
            loadUpdates();
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
        updateAllButton.setEnabled(tab == Tab.UPDATES && !latestVersions.isEmpty());
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
        updatesLoaded = false;
        refreshRows();
    }

    private void runSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            searchResults = List.of();
            refreshRows();
            return;
        }
        showCard(CARD_LOADING);
        setStatus(text("status.searching", "Buscando no Maven Central..."),
                BadgeLabel.Tone.INFO);
        host.search(query, outcome -> onUi(() -> {
            searchResults = outcome.results();
            if (outcome.failed()) {
                setStatus(text("status.searchFailed",
                        "Nao foi possivel consultar o Maven Central. Verifique a conexao."),
                        BadgeLabel.Tone.DANGER);
            } else {
                setStatus(outcome.results().size() + " "
                        + text("status.results", "resultado(s)"), BadgeLabel.Tone.NEUTRAL);
            }
            refreshRows();
        }));
    }

    private void loadUpdates() {
        if (installed.isEmpty()) {
            latestVersions = Map.of();
            updatesLoaded = true;
            refreshRows();
            return;
        }
        showCard(CARD_LOADING);
        setStatus(text("status.checkingVersions", "Consultando versoes..."), BadgeLabel.Tone.INFO);
        host.latestVersions(installed, versions -> onUi(() -> {
            latestVersions = versions == null ? Map.of() : versions;
            updatesLoaded = true;
            refreshRows();
            setStatus(text("status.updatesChecked", "Versoes conferidas."), BadgeLabel.Tone.NEUTRAL);
        }));
    }

    private void refreshRows() {
        DependencyCoordinate previous = selectedCoordinate();
        List<PackageRowRenderer.Row> built = switch (currentTab()) {
            case BROWSE -> browseRows();
            case INSTALLED -> installedRows();
            case UPDATES -> updateRows();
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
        for (MavenCentralClient.SearchResult result : searchResults) {
            DependencyCoordinate coordinate = result.coordinate();
            DependencyCoordinate declared = installedByKey.get(coordinate.key());
            String meta = coordinate.groupId()
                    + separator() + coordinate.version()
                    + separator() + result.versionCount() + " "
                    + text("label.versionsShort", "versoes")
                    + publishedSuffix(result.lastUpdated());
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(), meta,
                    declared == null ? "" : text("badge.installed", "instalada"),
                    BadgeLabel.Tone.SUCCESS));
        }
        return built;
    }

    private List<PackageRowRenderer.Row> installedRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        for (DependencyCoordinate coordinate : filtered(installed)) {
            String version = coordinate.hasVersion()
                    ? coordinate.version()
                    : text("value.inherited", "(herdada)");
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(),
                    coordinate.groupId() + separator() + version,
                    coordinate.scope(),
                    coordinate.isTestScope() ? BadgeLabel.Tone.INFO : BadgeLabel.Tone.NEUTRAL));
        }
        return built;
    }

    private List<PackageRowRenderer.Row> updateRows() {
        List<PackageRowRenderer.Row> built = new ArrayList<>();
        for (DependencyCoordinate coordinate : filtered(installed)) {
            String latest = latestVersions.get(coordinate.key());
            if (latest == null || latest.isBlank() || !coordinate.hasVersion()
                    || latest.equals(coordinate.version())) {
                continue;
            }
            built.add(new PackageRowRenderer.Row(coordinate, coordinate.artifactId(),
                    coordinate.groupId() + separator()
                            + coordinate.version() + "  →  " + latest,
                    text("badge.updateAvailable", "atualizacao"), BadgeLabel.Tone.WARNING));
        }
        return built;
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
                                "Digite o nome da biblioteca para consultar o Maven Central."));
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
        detailBody.setVisible(hasSelection);
        detailPlaceholder.setVisible(!hasSelection);
        addButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
        updateButton.setEnabled(hasSelection);
        if (!hasSelection) {
            versionSelector.setModel(new DefaultComboBoxModel<>());
            return;
        }

        detailTitle.setText(coordinate.artifactId());
        detailCoordinate.setText(coordinate.key());
        detailVersions.setText(versionSummary(coordinate));
        detailPublished.setText(publishedSummary(coordinate));
        scopeSelector.setSelectedItem(SCOPES.contains(coordinate.scope())
                ? coordinate.scope() : SCOPES.get(0));

        String current = coordinate.hasVersion() ? coordinate.version() : "";
        versionSelector.setModel(current.isBlank()
                ? new DefaultComboBoxModel<>()
                : new DefaultComboBoxModel<>(new String[]{current}));
        host.versions(coordinate, versions -> onUi(() -> {
            DependencyCoordinate stillSelected = selectedCoordinate();
            if (stillSelected == null || !stillSelected.sameArtifact(coordinate)) {
                return;
            }
            List<String> offered = acceptablePreRelease()
                    ? versions
                    : versions.stream().filter(MavenCentralClient::isStable).toList();
            if (offered.isEmpty()) {
                return;
            }
            versionSelector.setModel(new DefaultComboBoxModel<>(offered.toArray(String[]::new)));
            String preferred = currentTab() == Tab.UPDATES
                    ? latestVersions.getOrDefault(coordinate.key(), offered.get(0))
                    : offered.contains(current) ? current : offered.get(0);
            versionSelector.setSelectedItem(preferred);
        }));
    }

    private String versionSummary(DependencyCoordinate coordinate) {
        for (MavenCentralClient.SearchResult result : searchResults) {
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
        for (MavenCentralClient.SearchResult result : searchResults) {
            if (result.coordinate().sameArtifact(coordinate) && result.lastUpdated() > 0) {
                return text("label.published", "Publicada em:") + " "
                        + formatDate(result.lastUpdated());
            }
        }
        return "";
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
        String version = (String) versionSelector.getSelectedItem();
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
        String target = (String) versionSelector.getSelectedItem();
        if (target == null || target.isBlank() || target.equals(selected.version())) {
            setStatus(text("status.upToDate", "Ja esta na versao mais recente."),
                    BadgeLabel.Tone.NEUTRAL);
            return;
        }
        host.updateVersion(module, selected, target, changed -> onUi(() ->
                reportChange(changed, selected.key() + " → " + target)));
    }

    private void updateAll() {
        JavaModule module = selectedModule();
        if (module == null) {
            return;
        }
        List<Map.Entry<DependencyCoordinate, String>> pending = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            DependencyCoordinate coordinate = rows.get(index).coordinate();
            String latest = latestVersions.get(coordinate.key());
            if (latest != null && !latest.isBlank() && !latest.equals(coordinate.version())) {
                pending.add(Map.entry(coordinate, latest));
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        updateAllButton.setEnabled(false);
        applyNextUpdate(module, pending, 0, 0);
    }

    private void applyNextUpdate(JavaModule module,
                                 List<Map.Entry<DependencyCoordinate, String>> pending,
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
        Map.Entry<DependencyCoordinate, String> target = pending.get(index);
        host.updateVersion(module, target.getKey(), target.getValue(), changed ->
                applyNextUpdate(module, pending, index + 1,
                        Boolean.TRUE.equals(changed) ? applied + 1 : applied));
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
