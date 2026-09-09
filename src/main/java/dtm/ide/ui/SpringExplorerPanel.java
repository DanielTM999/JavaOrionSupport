package dtm.ide.ui;

import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringEndpoint;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaField;
import dtm.ide.spring.jpa.JpaQueryMethod;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import dtm.ide.spring.SpringInjection;
import dtm.ide.spring.SpringStereotype;
import dtm.ide.spring.live.SpringActuatorClient;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.loading.LoadingPanel;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class SpringExplorerPanel extends JPanel {

    private static String text(String key, String fallback) {
        return I18n.getText(SpringExplorerPanel.class, key, fallback);
    }

    public interface Host {

        SpringIndexSnapshot snapshot();

        void openFile(Path file, int line);

        void openInBrowser(String url);

        String applicationBaseUrl();

        void loadLive(Consumer<LiveData> onResult);

        void refreshIndex(Runnable onDone);
    }

    public record LiveData(
            boolean available,
            String health,
            List<SpringActuatorClient.LiveBean> beans,
            List<SpringActuatorClient.LiveProperty> properties,
            List<SpringActuatorClient.LiveMapping> mappings
    ) {

        public LiveData {
            beans = beans == null ? List.of() : List.copyOf(beans);
            properties = properties == null ? List.of() : List.copyOf(properties);
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }

        public static LiveData unavailable() {
            return new LiveData(false, "", List.of(), List.of(), List.of());
        }
    }

    private enum Tab { BEANS, ENDPOINTS, JPA, LIVE }

    private enum LiveTab { BEANS, PROPERTIES, MAPPINGS }

    private static final String CARD_BEANS = "beans";
    private static final String CARD_ENDPOINTS = "endpoints";
    private static final String CARD_JPA = "jpa";
    private static final String CARD_LIVE = "live";
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_CONTENT = "content";

    private final Host host;
    private final SegmentedField<Tab> tabs = new SegmentedField<>();
    private final MaskedTextField filter = new MaskedTextField();
    private final JButton refreshButton = new JButton(text("action.refresh", "Reindexar"),
            JavaIcons.sync(JavaIcons.SMALL));
    private final BadgeLabel status = badge(" ", BadgeLabel.Tone.NEUTRAL);
    private final CardLayout contentCards = new CardLayout();
    private final JPanel content = new JPanel(contentCards);

    private final TreeView<Object> beansTree = new TreeView<>();
    private final TreeView<Object> endpointTree = new TreeView<>();
    private final TreeView<Object> liveMappingTree = new TreeView<>();
    private final TreeView<Object> jpaTree = new TreeView<>();
    private final CollectionPane beansList;
    private final CollectionPane endpointList;
    private final CollectionPane mappingList;
    private final CollectionPane jpaList;

    private final CardLayout beanDetailCards = new CardLayout();
    private final JPanel beanDetailBody = new JPanel(beanDetailCards);
    private final JPanel beanDetail = verticalPanel();
    private final CardLayout endpointDetailCards = new CardLayout();
    private final JPanel endpointDetailBody = new JPanel(endpointDetailCards);
    private final JPanel endpointDetail = verticalPanel();
    private final JButton openSourceButton = PillButtons.primary(
            text("action.openSource", "Ir para o codigo"), JavaIcons.java(JavaIcons.SMALL));
    private final JButton openInBrowserButton = PillButtons.secondary(
            text("action.openInBrowser", "Abrir no navegador"), null);

    private final SegmentedField<LiveTab> liveTabs = new SegmentedField<>();
    private final JButton connectButton = PillButtons.primary(
            text("action.connect", "Conectar"), JavaIcons.run(JavaIcons.SMALL));
    private final BadgeLabel liveStatus = badge(
            text("live.disconnected.short", "Desconectado"), BadgeLabel.Tone.NEUTRAL);
    private final JLabel liveBaseUrl = mutedLabel("");
    private final LiveBeanTableModel liveBeanModel = new LiveBeanTableModel();
    private final JTable liveBeanTable = new JTable(liveBeanModel);
    private final LivePropertyTableModel livePropertyModel = new LivePropertyTableModel();
    private final JTable livePropertyTable = new JTable(livePropertyModel);
    private final CollectionPane liveBeansList;
    private final CollectionPane livePropertiesList;
    private final CardLayout liveContentCards = new CardLayout();
    private final JPanel liveContent = new JPanel(liveContentCards);
    private final LoadingPanel liveLoading;

    private SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(null);
    private LiveData liveData = LiveData.unavailable();
    private SpringEndpoint selectedEndpoint;
    private final Set<TreeView<Object>> activatedTrees =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public SpringExplorerPanel(Host host) {
        super(new BorderLayout(0, UiTokens.space(2)));
        this.host = host;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));

        configureTree(beansTree);
        configureTree(endpointTree);
        configureTree(liveMappingTree);
        configureLiveTables();

        beansList = new CollectionPane(scroll(beansTree), JavaIcons.spring(UiTokens.scale(30)));
        endpointList = new CollectionPane(scroll(endpointTree), JavaIcons.spring(UiTokens.scale(30)));
        jpaList = new CollectionPane(scroll(jpaTree), JavaIcons.spring(UiTokens.scale(30)));
        mappingList = new CollectionPane(scroll(liveMappingTree), JavaIcons.spring(UiTokens.scale(30)));
        liveBeansList = new CollectionPane(tableScroll(liveBeanTable),
                JavaIcons.spring(UiTokens.scale(30)));
        livePropertiesList = new CollectionPane(tableScroll(livePropertyTable),
                JavaIcons.spring(UiTokens.scale(30)));

        liveContent.setOpaque(false);
        liveContent.add(liveBeansList, LiveTab.BEANS.name());
        liveContent.add(livePropertiesList, LiveTab.PROPERTIES.name());
        liveContent.add(mappingList, LiveTab.MAPPINGS.name());
        liveLoading = new LoadingPanel(liveContent).setShowMessage(true).setBlockInput(true);
        liveLoading.stop();

        content.setOpaque(false);
        content.add(beansTab(), CARD_BEANS);
        content.add(endpointsTab(), CARD_ENDPOINTS);
        content.add(jpaTab(), CARD_JPA);
        content.add(liveTab(), CARD_LIVE);

        add(toolbar(), BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);

        wireActions();
        refreshButton.setFocusable(false);
        connectButton.setFocusable(false);
        openSourceButton.setFocusable(false);
        openInBrowserButton.setFocusable(false);
        filter.setFocusable(true);
        beansTree.setFocusable(true);
        endpointTree.setFocusable(true);
        liveMappingTree.setFocusable(true);
        jpaTree.setFocusable(true);
        contentCards.show(content, CARD_BEANS);
        reload();
    }

    private ToolBarPanel toolbar() {
        tabs.addSegment(text("tab.beans", "Beans"), Tab.BEANS)
                .addSegment(text("tab.endpoints", "Endpoints"), Tab.ENDPOINTS)
                .addSegment(text("tab.jpa", "JPA"), Tab.JPA)
                .addSegment(text("tab.live", "Ao vivo"), Tab.LIVE)
                .setAnimated(true)
                .setArc(UiTokens.radius(UiTokens.Radius.SM))
                .setPreferredHeight(UiTokens.scale(PillButtons.FIELD_HEIGHT))
                .setSegmentPadding(UiTokens.space(4))
                .setSelectedIndex(0, false);

        filter.setPlaceholder(text("filter.beans", "Filtrar beans..."));
        filter.putClientProperty("JTextField.leadingIcon", JavaIcons.search(JavaIcons.SMALL));
        filter.setPreferredSize(new Dimension(UiTokens.scale(240),
                UiTokens.scale(PillButtons.FIELD_HEIGHT)));

        ToolBarPanel bar = new ToolBarPanel().setPaintSurface(true)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setItemGap(UiTokens.space(2));
        bar.addItem(new JLabel(JavaIcons.spring(JavaIcons.SMALL))).addItem(tabs)
                .addSpacer().addItem(filter).addItem(refreshButton);
        return bar;
    }

    private JPanel statusBar() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        footer.setOpaque(false);
        footer.add(status);
        return footer;
    }

    private JComponent beansTab() {
        beanDetailBody.setOpaque(false);
        beanDetailBody.add(detailEmpty(text("beans.emptySelection.title", "Nenhum bean selecionado"),
                text("beans.emptySelection.description",
                        "Selecione um bean para ver dependencias, perfis e qualificadores.")), CARD_EMPTY);
        beanDetailBody.add(scroll(beanDetail), CARD_CONTENT);

        CardPanel listCard = card(text("beans.card.title", "Componentes Spring"),
                text("beans.card.subtitle", "Beans agrupados por estereotipo"), beansList);
        CardPanel detailCard = card(text("details.card.title", "Detalhes"),
                text("beans.details.subtitle", "Registro e relacionamentos do bean"), beanDetailBody);
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, listCard, detailCard)
                .setDividerThickness(UiTokens.space(2)).setCollapseOnDoubleClick(true);
        split.setResizeWeight(0.46);
        split.setPreferredSize(new Dimension(880, 340));
        return split;
    }

    private JComponent endpointsTab() {
        endpointDetailBody.setOpaque(false);
        endpointDetailBody.add(detailEmpty(
                text("endpoints.emptySelection.title", "Nenhum endpoint selecionado"),
                text("endpoints.emptySelection.description",
                        "Expanda um controlador e selecione uma rota para ver os detalhes.")), CARD_EMPTY);
        endpointDetailBody.add(scroll(endpointDetail), CARD_CONTENT);

        CardPanel listCard = card(text("endpoints.card.title", "Rotas por controlador"),
                text("endpoints.card.subtitle", "Controladores expansivos e seus handlers"),
                endpointList);
        CardPanel detailCard = card(text("details.card.title", "Detalhes"),
                text("endpoints.details.subtitle", "Contrato e navegacao da rota"),
                endpointDetailBody);
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, listCard, detailCard)
                .setDividerThickness(UiTokens.space(2)).setCollapseOnDoubleClick(true);
        split.setResizeWeight(0.53);
        return split;
    }

    private JComponent jpaTab() {
        return card(text("jpa.card.title", "Modelo de persistencia"),
                text("jpa.card.subtitle", "Entidades, campos e repositorios do projeto"), jpaList);
    }

    private JComponent liveTab() {
        liveTabs.addSegment(text("live.tab.beans", "Beans"), LiveTab.BEANS)
                .addSegment(text("live.tab.properties", "Propriedades"), LiveTab.PROPERTIES)
                .addSegment(text("live.tab.mappings", "Mappings"), LiveTab.MAPPINGS)
                .setAnimated(true)
                .setArc(UiTokens.radius(UiTokens.Radius.SM))
                .setPreferredHeight(UiTokens.scale(PillButtons.FIELD_HEIGHT))
                .setSegmentPadding(UiTokens.space(3))
                .setSelectedIndex(0, false);

        liveBaseUrl.setFont(UiTokens.fontMono());
        JPanel connection = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), 0));
        connection.setOpaque(false);
        connection.add(connectButton);
        connection.add(liveStatus);
        connection.add(liveBaseUrl);

        JPanel bar = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        bar.setOpaque(false);
        bar.add(connection, BorderLayout.WEST);
        bar.add(liveTabs, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, UiTokens.space(2)));
        body.setOpaque(false);
        body.add(bar, BorderLayout.NORTH);
        body.add(liveLoading, BorderLayout.CENTER);
        return card(text("live.card.title", "Aplicacao em execucao"),
                text("live.card.subtitle", "Estado efetivo consultado pelo Spring Boot Actuator"), body);
    }

    private void configureLiveTables() {
        UiSupport.modernReadOnlyTable(liveBeanTable);
        UiSupport.modernReadOnlyTable(livePropertyTable);
        liveBeanTable.getColumnModel().getColumn(1).setCellRenderer(UiSupport.monoColumn());
        liveBeanTable.getColumnModel().getColumn(2)
                .setCellRenderer(UiSupport.badgeColumn(SpringExplorerPanel::beanScopeTone));
        livePropertyTable.getColumnModel().getColumn(1).setCellRenderer(UiSupport.monoColumn());
        livePropertyTable.getColumnModel().getColumn(2).setCellRenderer(UiSupport.monoColumn());
    }

    private void wireActions() {
        tabs.addEventListener(SegmentedField.SEGMENT_SELECTED,
                event -> SwingUtilities.invokeLater(this::onTabChanged));
        liveTabs.addEventListener(SegmentedField.SEGMENT_SELECTED,
                event -> SwingUtilities.invokeLater(this::onLiveTabChanged));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        refreshButton.addActionListener(event -> {
            refreshButton.setEnabled(false);
            status.setText(text("status.indexing", "Reindexando..."))
                    .setTone(BadgeLabel.Tone.INFO);
            host.refreshIndex(() -> SwingUtilities.invokeLater(() -> {
                refreshButton.setEnabled(true);
                reload();
            }));
        });
        connectButton.addActionListener(event -> loadLive());
        beansTree.addTreeSelectionListener(event -> showBeanDetails());
        endpointTree.addTreeSelectionListener(event -> showEndpointDetails());
        beansTree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelectedBean());
        beansTree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelectedBean());
        endpointTree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelectedEndpoint());
        endpointTree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelectedEndpoint());
        jpaTree.onTreeEvent(EventTreeView.NODE_DOUBLE_CLICK, event -> openSelectedJpaNode());
        jpaTree.onTreeEvent(EventTreeView.NODE_ACTIVATE, event -> openSelectedJpaNode());
        openSourceButton.addActionListener(event -> openSelectedEndpoint());
        openInBrowserButton.addActionListener(event -> {
            SpringEndpoint endpoint = selectedEndpoint;
            if (endpoint != null && !endpoint.hasPathVariables()) {
                host.openInBrowser(endpoint.urlOn(host.applicationBaseUrl()));
            }
        });
    }

    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        SpringIndexSnapshot next = host.snapshot();
        snapshot = next == null ? SpringIndexSnapshot.empty(null) : next;
        rebuildBeans();
        rebuildEndpoints();
        rebuildJpa();
        rebuildAllLiveViews();
        updateOverallStatus();
    }

    private void onTabChanged() {
        Tab selected = tabs.getSelectedValue();
        selected = selected == null ? Tab.BEANS : selected;
        contentCards.show(content, switch (selected) {
            case BEANS -> CARD_BEANS;
            case ENDPOINTS -> CARD_ENDPOINTS;
            case JPA -> CARD_JPA;
            case LIVE -> CARD_LIVE;
        });
        filter.setPlaceholder(switch (selected) {
            case BEANS -> text("filter.beans", "Filtrar beans...");
            case ENDPOINTS -> text("filter.endpoints", "Filtrar rotas ou controladores...");
            case JPA -> text("filter.jpa", "Filtrar entidades, tabelas ou repositorios...");
            case LIVE -> liveFilterPlaceholder();
        });
        clearContextFilter();
        applyFilter();
        TreeView<Object> selectedTree = switch (selected) {
            case BEANS -> beansTree;
            case ENDPOINTS -> endpointTree;
            case JPA -> jpaTree;
            case LIVE -> selectedLiveTab() == LiveTab.MAPPINGS ? liveMappingTree : null;
        };
        if (selectedTree != null) {
            SwingUtilities.invokeLater(() -> revealTree(selectedTree));
        }
    }

    private void onLiveTabChanged() {
        LiveTab selected = selectedLiveTab();
        liveContentCards.show(liveContent, selected.name());
        filter.setPlaceholder(liveFilterPlaceholder());
        clearContextFilter();
        rebuildLiveView();
        if (selected == LiveTab.MAPPINGS) {
            SwingUtilities.invokeLater(() -> revealTree(liveMappingTree));
        }
    }

    private void clearContextFilter() {
        if (!filter.getText().isBlank()) {
            filter.setText("");
        }
    }

    private String liveFilterPlaceholder() {
        return switch (selectedLiveTab()) {
            case BEANS -> text("filter.liveBeans", "Filtrar beans em execucao...");
            case PROPERTIES -> text("filter.properties", "Filtrar propriedades...");
            case MAPPINGS -> text("filter.mappings", "Filtrar mappings...");
        };
    }

    private LiveTab selectedLiveTab() {
        LiveTab selected = liveTabs.getSelectedValue();
        return selected == null ? LiveTab.BEANS : selected;
    }

    private void applyFilter() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyFilter);
            return;
        }
        Tab selected = tabs.getSelectedValue();
        if (selected == Tab.ENDPOINTS) {
            rebuildEndpoints();
        } else if (selected == Tab.JPA) {
            rebuildJpa();
        } else if (selected == Tab.LIVE) {
            rebuildLiveView();
        } else {
            rebuildBeans();
        }
    }

    private void rebuildBeans() {
        Set<String> expansion = beansTree.snapshotExpansion();
        TreeNode<Object> root = UiSupport.treeNode(null, "spring-beans-root");
        Map<SpringStereotype, List<SpringBean>> groups =
                SpringExplorerModel.beansByStereotype(snapshot.beans(), filter.getText());
        int visible = 0;
        for (Map.Entry<SpringStereotype, List<SpringBean>> entry : groups.entrySet()) {
            SpringStereotype stereotype = entry.getKey();
            TreeNode<Object> group = UiSupport.treeNode(new BeanGroup(stereotype),
                    "stereotype|" + stereotype.name());
            group.setLabel(stereotype.displayName() + "   " + entry.getValue().size());
            group.setIcon(JavaIcons.spring(JavaIcons.SMALL));
            group.setForeground(UiTokens.muted());
            for (SpringBean bean : entry.getValue()) {
                TreeNode<Object> node = UiSupport.treeNode(new BeanNode(bean),
                        "bean|" + bean.type() + "|" + bean.name());
                node.setLabel(bean.simpleName() + "   " + bean.name());
                node.setIcon(JavaIcons.java(JavaIcons.SMALL));
                node.setTooltip(bean.type());
                group.addChild(node);
                visible++;
            }
            root.addChild(group);
        }
        beansTree.setRoot(root);
        restoreExpansion(beansTree, expansion);
        beansList.show(visible > 0,
                filter.getText().isBlank()
                        ? text("beans.empty.title", "Nenhum bean encontrado")
                        : text("empty.filtered.title", "Nenhum resultado"),
                filter.getText().isBlank()
                        ? text("beans.empty.description",
                                "Nenhum componente Spring foi encontrado no projeto.")
                        : text("empty.filtered.description", "Tente outro termo de busca."));
        if (visible == 0) {
            clearBeanDetails();
        }
    }

    private void rebuildEndpoints() {
        Set<String> expansion = endpointTree.snapshotExpansion();
        TreeNode<Object> root = UiSupport.treeNode(null, "spring-endpoints-root");
        List<SpringExplorerModel.ControllerEndpoints> groups =
                SpringExplorerModel.endpointsByController(snapshot.endpoints(), filter.getText());
        int visible = 0;
        for (SpringExplorerModel.ControllerEndpoints controller : groups) {
            TreeNode<Object> group = UiSupport.treeNode(new ControllerNode(controller),
                    "controller|" + controller.handlerType());
            group.setLabel(controller.displayName() + "   " + controller.endpoints().size());
            group.setIcon(JavaIcons.spring(JavaIcons.SMALL));
            group.setTooltip(controller.handlerType());
            for (SpringEndpoint endpoint : controller.endpoints()) {
                TreeNode<Object> node = UiSupport.treeNode(new EndpointNode(endpoint),
                        "endpoint|" + endpoint.handlerType() + "|" + endpoint.handlerName()
                                + "|" + endpoint.method() + "|" + endpoint.path());
                node.setLabel(endpoint.method() + "   " + endpoint.path()
                        + "   → " + endpoint.handlerName() + "()");
                node.setIcon(JavaIcons.run(JavaIcons.SMALL));
                node.setForeground(httpVerbColor(endpoint.method()));
                node.setTooltip(endpoint.handlerType() + "#" + endpoint.handlerName());
                group.addChild(node);
                visible++;
            }
            root.addChild(group);
        }
        endpointTree.setRoot(root);
        restoreExpansion(endpointTree, expansion);
        endpointList.show(visible > 0,
                filter.getText().isBlank()
                        ? text("endpoints.empty.title", "Nenhuma rota encontrada")
                        : text("empty.filtered.title", "Nenhum resultado"),
                filter.getText().isBlank()
                        ? text("endpoints.empty.description",
                                "Adicione mappings a um @Controller ou @RestController.")
                        : text("empty.filtered.description", "Tente outro termo de busca."));
        if (visible == 0) {
            clearEndpointDetails();
        }
    }

    private void rebuildJpa() {
        Set<String> expansion = jpaTree.snapshotExpansion();
        TreeNode<Object> root = UiSupport.treeNode(null, "spring-jpa-root");
        List<JpaEntity> entities =
                SpringExplorerModel.filterEntities(snapshot.entities(), filter.getText());
        List<JpaRepositoryInfo> repositories =
                SpringExplorerModel.filterRepositories(snapshot.repositories(), filter.getText());
        int visible = 0;

        if (!entities.isEmpty()) {
            TreeNode<Object> group = UiSupport.treeNode(null, "jpa-entities");
            group.setLabel(text("jpa.entities", "Entidades") + "   " + entities.size());
            group.setIcon(JavaIcons.spring(JavaIcons.SMALL));
            group.setForeground(UiTokens.muted());
            for (JpaEntity entity : entities) {
                TreeNode<Object> node = UiSupport.treeNode(new JpaEntityNode(entity),
                        "jpa-entity|" + entity.type());
                node.setLabel(entity.simpleName() + "   " + entity.effectiveTable());
                node.setIcon(JavaIcons.java(JavaIcons.SMALL));
                node.setTooltip(entity.type());
                for (JpaField field : entity.fields()) {
                    TreeNode<Object> child = UiSupport.treeNode(new JpaFieldNode(entity, field),
                            "jpa-field|" + entity.type() + "|" + field.name());
                    child.setLabel(fieldLabel(field));
                    child.setForeground(UiTokens.muted());
                    node.addChild(child);
                }
                group.addChild(node);
                visible++;
            }
            root.addChild(group);
        }

        if (!repositories.isEmpty()) {
            TreeNode<Object> group = UiSupport.treeNode(null, "jpa-repositories");
            group.setLabel(text("jpa.repositories", "Repositorios") + "   " + repositories.size());
            group.setIcon(JavaIcons.spring(JavaIcons.SMALL));
            group.setForeground(UiTokens.muted());
            for (JpaRepositoryInfo repository : repositories) {
                TreeNode<Object> node = UiSupport.treeNode(new JpaRepositoryNode(repository),
                        "jpa-repository|" + repository.type());
                node.setLabel(repository.simpleName() + "   " + repository.entitySimpleName());
                node.setIcon(JavaIcons.java(JavaIcons.SMALL));
                node.setTooltip(repository.type());
                for (JpaQueryMethod method : repository.methods()) {
                    TreeNode<Object> child = UiSupport.treeNode(
                            new JpaMethodNode(repository, method),
                            "jpa-method|" + repository.type() + "|" + method.name());
                    child.setLabel(method.name() + "()");
                    child.setForeground(UiTokens.muted());
                    node.addChild(child);
                }
                group.addChild(node);
                visible++;
            }
            root.addChild(group);
        }

        jpaTree.setRoot(root);
        restoreExpansion(jpaTree, expansion);
        jpaList.show(visible > 0,
                filter.getText().isBlank()
                        ? text("jpa.empty", "Nenhuma entidade JPA encontrada")
                        : text("empty.filtered.title", "Nenhum resultado"),
                filter.getText().isBlank()
                        ? text("jpa.empty.description",
                                "Anote uma classe com @Entity ou crie um repositorio Spring Data.")
                        : text("empty.filtered.description", "Tente outro termo de busca."));
    }

    private static String fieldLabel(JpaField field) {
        StringBuilder label = new StringBuilder(field.name())
                .append("   ")
                .append(field.effectiveColumn());
        if (field.id()) {
            label.append("   @Id");
        }
        if (field.relation() != JpaField.Relation.NONE) {
            label.append("   ").append(field.relation().name().toLowerCase(java.util.Locale.ROOT));
        }
        return label.toString();
    }

    private void openSelectedJpaNode() {
        Object data = selectedTreeData(jpaTree);
        if (data instanceof JpaEntityNode node) {
            host.openFile(node.entity().file(), node.entity().line());
        } else if (data instanceof JpaFieldNode node) {
            host.openFile(node.entity().file(), node.field().line());
        } else if (data instanceof JpaRepositoryNode node) {
            host.openFile(node.repository().file(), node.repository().line());
        } else if (data instanceof JpaMethodNode node) {
            host.openFile(node.repository().file(), node.method().line());
        }
    }

    private void showBeanDetails() {
        SpringBean bean = selectedBean();
        if (bean == null) {
            clearBeanDetails();
            return;
        }
        beanDetail.removeAll();
        beanDetail.add(title(bean.simpleName()));
        beanDetail.add(mono(bean.type()));
        beanDetail.add(Box.createVerticalStrut(UiTokens.space(2)));

        JPanel badges = badgeRow();
        badges.add(badge(bean.stereotype().displayName(), BadgeLabel.Tone.PRIMARY));
        if (bean.primary()) {
            badges.add(badge("@Primary", BadgeLabel.Tone.SUCCESS));
        }
        if (bean.conditional()) {
            badges.add(badge(text("details.conditional.short", "Condicional"),
                    BadgeLabel.Tone.WARNING));
        }
        bean.profiles().forEach(profile -> badges.add(badge(profile, BadgeLabel.Tone.INFO)));
        capHeight(badges);
        beanDetail.add(badges);
        beanDetail.add(Box.createVerticalStrut(UiTokens.space(3)));
        beanDetail.add(keyValue(text("details.name", "Nome do bean"), bean.name()));
        if (bean.hasQualifier()) {
            beanDetail.add(keyValue("@Qualifier", bean.qualifier()));
        }

        List<SpringInjection> dependencies = snapshot.dependenciesOf(bean);
        beanDetail.add(section(text("details.injects", "Dependencias"), dependencies.stream()
                .map(injection -> injection.targetSimpleName() + "  ·  "
                        + injection.memberName()).toList(),
                text("details.noneDependencies", "Nenhuma dependencia detectada.")));
        List<SpringInjection> usages = snapshot.injectionsOf(bean);
        beanDetail.add(section(text("details.injectedBy", "Injetado por"), usages.stream()
                .map(injection -> SpringBean.simpleNameOf(injection.ownerType())).toList(),
                text("details.noneUsages", "Nenhum consumidor detectado.")));
        beanDetail.add(Box.createVerticalGlue());
        beanDetail.revalidate();
        beanDetail.repaint();
        beanDetailCards.show(beanDetailBody, CARD_CONTENT);
    }

    private void showEndpointDetails() {
        Object selected = selectedTreeData(endpointTree);
        if (selected instanceof EndpointNode node) {
            showEndpoint(node.endpoint());
            return;
        }
        if (selected instanceof ControllerNode node) {
            showController(node.controller());
            return;
        }
        clearEndpointDetails();
    }

    private void showEndpoint(SpringEndpoint endpoint) {
        selectedEndpoint = endpoint;
        endpointDetail.removeAll();
        endpointDetail.add(title(endpoint.path()));
        endpointDetail.add(Box.createVerticalStrut(UiTokens.space(1)));
        JPanel badges = badgeRow();
        badges.add(badge(endpoint.method(), httpVerbTone(endpoint.method())));
        endpoint.produces().forEach(value -> badges.add(badge(value, BadgeLabel.Tone.NEUTRAL)));
        capHeight(badges);
        endpointDetail.add(badges);
        endpointDetail.add(Box.createVerticalStrut(UiTokens.space(3)));
        endpointDetail.add(keyValue(text("column.handler", "Controlador"),
                endpoint.handlerType()));
        endpointDetail.add(keyValue(text("column.action", "Metodo Java"),
                endpoint.handlerName() + "()"));
        endpointDetail.add(keyValue(text("details.source", "Origem"),
                endpoint.file() + ":" + endpoint.line()));
        if (endpoint.hasPathVariables()) {
            endpointDetail.add(hint(text("endpoints.pathVariables",
                    "Preencha as variaveis da rota antes de abri-la no navegador.")));
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        openSourceButton.setEnabled(true);
        openInBrowserButton.setEnabled(!endpoint.hasPathVariables());
        actions.add(openSourceButton);
        actions.add(openInBrowserButton);
        endpointDetail.add(Box.createVerticalStrut(UiTokens.space(3)));
        endpointDetail.add(actions);
        endpointDetail.add(Box.createVerticalGlue());
        endpointDetail.revalidate();
        endpointDetail.repaint();
        endpointDetailCards.show(endpointDetailBody, CARD_CONTENT);
    }

    private void showController(SpringExplorerModel.ControllerEndpoints controller) {
        selectedEndpoint = null;
        endpointDetail.removeAll();
        endpointDetail.add(title(controller.displayName()));
        endpointDetail.add(mono(controller.handlerType()));
        endpointDetail.add(Box.createVerticalStrut(UiTokens.space(3)));
        endpointDetail.add(badge(controller.endpoints().size() + " "
                + text("status.endpoints", "endpoint(s)"), BadgeLabel.Tone.PRIMARY));
        endpointDetail.add(Box.createVerticalGlue());
        openSourceButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
        endpointDetail.revalidate();
        endpointDetail.repaint();
        endpointDetailCards.show(endpointDetailBody, CARD_CONTENT);
    }

    private void loadLive() {
        connectButton.setEnabled(false);
        liveStatus.setText(text("live.connecting", "Consultando o Actuator..."))
                .setTone(BadgeLabel.Tone.INFO);
        liveLoading.setMessage(text("live.connecting", "Consultando o Actuator..."))
                .setIndeterminate().start();
        host.loadLive(data -> SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(true);
            liveLoading.stop();
            liveData = data == null ? LiveData.unavailable() : data;
            if (!liveData.available()) {
                liveStatus.setText(text("live.disconnected.short", "Desconectado"))
                        .setTone(BadgeLabel.Tone.DANGER);
            } else {
                liveStatus.setText(text("live.connected", "Conectado") + "  ·  "
                                + liveData.health())
                        .setTone("UP".equalsIgnoreCase(liveData.health())
                                ? BadgeLabel.Tone.SUCCESS : BadgeLabel.Tone.WARNING);
            }
            rebuildAllLiveViews();
            updateOverallStatus();
        }));
    }

    private void rebuildAllLiveViews() {
        rebuildLiveBeans();
        rebuildLiveProperties();
        rebuildLiveMappings();
    }

    private void rebuildLiveView() {
        switch (selectedLiveTab()) {
            case BEANS -> rebuildLiveBeans();
            case PROPERTIES -> rebuildLiveProperties();
            case MAPPINGS -> rebuildLiveMappings();
        }
    }

    private void rebuildLiveBeans() {
        List<SpringActuatorClient.LiveBean> rows = SpringExplorerModel.filterLiveBeans(
                liveData.beans(), filter.getText());
        liveBeanModel.setRows(rows);
        liveBeansList.show(liveData.available() && !rows.isEmpty(),
                liveEmptyTitle(rows.isEmpty()), liveEmptyDescription());
    }

    private void rebuildLiveProperties() {
        List<SpringActuatorClient.LiveProperty> rows = SpringExplorerModel.filterLiveProperties(
                liveData.properties(), filter.getText());
        livePropertyModel.setRows(rows);
        livePropertiesList.show(liveData.available() && !rows.isEmpty(),
                liveEmptyTitle(rows.isEmpty()), liveEmptyDescription());
    }

    private void rebuildLiveMappings() {
        Set<String> expansion = liveMappingTree.snapshotExpansion();
        List<SpringExplorerModel.MappingGroup> groups =
                SpringExplorerModel.mappingsByController(liveData.mappings(), filter.getText(),
                        text("live.framework", "Framework / Outros"));
        TreeNode<Object> root = UiSupport.treeNode(null, "live-mappings-root");
        int visible = 0;
        for (SpringExplorerModel.MappingGroup mappingGroup : groups) {
            TreeNode<Object> group = UiSupport.treeNode(new MappingGroupNode(mappingGroup),
                    "live-controller|" + mappingGroup.key());
            group.setLabel(mappingGroup.displayName() + "   " + mappingGroup.mappings().size());
            group.setIcon(JavaIcons.spring(JavaIcons.SMALL));
            group.setTooltip(mappingGroup.key());
            for (SpringActuatorClient.LiveMapping mapping : mappingGroup.mappings()) {
                TreeNode<Object> node = UiSupport.treeNode(new MappingNode(mapping),
                        "mapping|" + mapping.method() + "|" + mapping.path() + "|"
                                + mapping.handler());
                node.setLabel((mapping.method().isBlank() ? "ANY" : mapping.method())
                        + "   " + mapping.path());
                node.setIcon(JavaIcons.run(JavaIcons.SMALL));
                node.setForeground(httpVerbColor(mapping.method()));
                node.setTooltip(mapping.handler());
                group.addChild(node);
                visible++;
            }
            root.addChild(group);
        }
        liveMappingTree.setRoot(root);
        restoreExpansion(liveMappingTree, expansion);
        mappingList.show(liveData.available() && visible > 0,
                liveEmptyTitle(visible == 0), liveEmptyDescription());
    }

    private String liveEmptyTitle(boolean empty) {
        if (!liveData.available()) {
            return text("live.empty.disconnected.title", "Aplicacao desconectada");
        }
        return !filter.getText().isBlank() && empty
                ? text("empty.filtered.title", "Nenhum resultado")
                : text("live.empty.title", "Nenhum dado retornado");
    }

    private String liveEmptyDescription() {
        if (!liveData.available()) {
            return text("live.empty.disconnected.description",
                    "Execute a aplicacao com o Actuator exposto e clique em Conectar.");
        }
        return filter.getText().isBlank()
                ? text("live.empty.description", "O Actuator nao retornou itens nesta categoria.")
                : text("empty.filtered.description", "Tente outro termo de busca.");
    }

    private void updateOverallStatus() {
        String caption = snapshot.beans().size() + " " + text("status.beans", "bean(s)")
                + "  ·  " + snapshot.endpoints().size() + " "
                + text("status.endpoints", "endpoint(s)");
        if (liveData.available()) {
            caption += "  ·  " + liveData.mappings().size() + " "
                    + text("status.liveMappings", "mapping(s) ao vivo");
        }
        status.setText(caption).setTone(BadgeLabel.Tone.NEUTRAL);
        liveBaseUrl.setText(host.applicationBaseUrl());
    }

    private SpringBean selectedBean() {
        Object data = selectedTreeData(beansTree);
        return data instanceof BeanNode node ? node.bean() : null;
    }

    private SpringEndpoint selectedEndpointFromTree() {
        Object data = selectedTreeData(endpointTree);
        return data instanceof EndpointNode node ? node.endpoint() : null;
    }

    private void openSelectedBean() {
        SpringBean bean = selectedBean();
        if (bean != null) {
            host.openFile(bean.file(), bean.line());
        }
    }

    private void openSelectedEndpoint() {
        SpringEndpoint endpoint = selectedEndpointFromTree();
        if (endpoint == null) {
            endpoint = selectedEndpoint;
        }
        if (endpoint != null) {
            host.openFile(endpoint.file(), endpoint.line());
        }
    }

    private void clearBeanDetails() {
        beanDetailCards.show(beanDetailBody, CARD_EMPTY);
    }

    private void clearEndpointDetails() {
        selectedEndpoint = null;
        openSourceButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
        endpointDetailCards.show(endpointDetailBody, CARD_EMPTY);
    }

    private static Object selectedTreeData(TreeView<Object> tree) {
        TreeNode<Object> selected = tree.getSelectedNode();
        return selected == null ? null : selected.getData();
    }

    private static void configureTree(TreeView<Object> tree) {
        tree.setCellRenderer(new FlatTreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UiTokens.scale(28));
        tree.setOpaque(false);
        tree.setMode(TreeViewMode.DISCONTIGUOUS);
        tree.setShowCheckBoxPlaceholder(false);
        tree.setExpandOnDoubleClick(false);
        tree.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(1), UiTokens.space(1), UiTokens.space(1), UiTokens.space(1)));
    }

    private static void restoreExpansion(TreeView<Object> tree, Set<String> expansion) {
        if (expansion == null || expansion.isEmpty()) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
    }

    private void revealTree(TreeView<Object> tree) {
        boolean firstActivation = activatedTrees.add(tree);
        Set<String> expansion = firstActivation ? Set.of() : tree.snapshotExpansion();
        tree.updateUI();
        if (firstActivation) {
            tree.expandAll();
        } else {
            tree.restoreExpansion(expansion);
        }
        tree.revalidate();
        tree.repaint();
    }

    private static ScrollPanel scroll(JComponent component) {
        return new ScrollPanel(component).setScrollBarThickness(UiTokens.scale(8))
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(26));
    }

    private static ScrollPanel tableScroll(JTable table) {
        return new ScrollPanel(table).setScrollBarThickness(UiTokens.scale(8))
                .setPaintTrack(false).setUnitIncrement(UiTokens.scale(30));
    }

    private static CardPanel card(String title, String subtitle, JComponent body) {
        return new CardPanel(title, subtitle).setVariant(CardPanel.Variant.FILLED)
                .setArc(UiTokens.radius(UiTokens.Radius.MD)).setContent(body);
    }

    private static EmptyStatePanel detailEmpty(String title, String description) {
        return new EmptyStatePanel(title, description).setIcon(JavaIcons.spring(UiTokens.scale(30)))
                .setDashedBorder(false).setArc(UiTokens.radius(UiTokens.Radius.MD));
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(3), UiTokens.space(2), UiTokens.space(3)));
        return panel;
    }

    private static JLabel title(String value) {
        JLabel label = new JLabel(value);
        label.setFont(UiTokens.fontTitle());
        label.setForeground(UiTokens.foreground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel mono(String value) {
        JLabel label = new JLabel(value);
        label.setFont(UiTokens.fontMono());
        label.setForeground(UiTokens.muted());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel mutedLabel(String value) {
        JLabel label = new JLabel(value);
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        return label;
    }

    private static JPanel badgeRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(1), 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static BadgeLabel badge(String value, BadgeLabel.Tone tone) {
        return new BadgeLabel(value, tone).setStyle(BadgeLabel.Style.SOFT)
                .setShowDot(true).setSize(BadgeLabel.Size.SM);
    }

    private static JComponent keyValue(String key, String value) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel keyLabel = mutedLabel(key.toUpperCase(Locale.ROOT));
        keyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel valueLabel = mono(value == null || value.isBlank() ? "—" : value);
        row.add(keyLabel);
        row.add(Box.createVerticalStrut(Math.max(2, UiTokens.space(1) / 2)));
        row.add(valueLabel);
        row.add(Box.createVerticalStrut(UiTokens.space(2)));
        capHeight(row);
        return row;
    }

    private static JComponent section(String title, List<String> values, String emptyText) {
        JPanel section = new JPanel();
        section.setOpaque(false);
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), 0, 0, 0));
        JLabel heading = new JLabel(title + "   " + values.size());
        heading.setFont(UiTokens.fontBold());
        heading.setForeground(UiTokens.foreground());
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(heading);
        section.add(Box.createVerticalStrut(UiTokens.space(1)));
        if (values.isEmpty()) {
            JLabel empty = mutedLabel(emptyText);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(empty);
        } else {
            values.forEach(value -> {
                JLabel item = new JLabel("•  " + value);
                item.setFont(UiTokens.font());
                item.setForeground(UiTokens.foreground());
                item.setAlignmentX(Component.LEFT_ALIGNMENT);
                section.add(item);
            });
        }
        capHeight(section);
        return section;
    }

    private static JLabel hint(String value) {
        JLabel label = new JLabel("<html>" + value + "</html>");
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.warning());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void capHeight(JComponent component) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                component.getPreferredSize().height));
    }

    private static BadgeLabel.Tone httpVerbTone(Object verb) {
        return switch (String.valueOf(verb).trim().toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> BadgeLabel.Tone.INFO;
            case "POST" -> BadgeLabel.Tone.SUCCESS;
            case "PUT", "PATCH" -> BadgeLabel.Tone.WARNING;
            case "DELETE" -> BadgeLabel.Tone.DANGER;
            default -> BadgeLabel.Tone.NEUTRAL;
        };
    }

    private static Color httpVerbColor(String verb) {
        return switch (httpVerbTone(verb)) {
            case INFO -> UiTokens.info();
            case SUCCESS -> UiTokens.success();
            case WARNING -> UiTokens.warning();
            case DANGER -> UiTokens.danger();
            case PRIMARY -> UiTokens.primary();
            default -> UiTokens.foreground();
        };
    }

    private static BadgeLabel.Tone beanScopeTone(Object scope) {
        return "singleton".equalsIgnoreCase(String.valueOf(scope).trim())
                ? BadgeLabel.Tone.NEUTRAL : BadgeLabel.Tone.PRIMARY;
    }

    private record BeanGroup(SpringStereotype stereotype) {
    }

    private record BeanNode(SpringBean bean) {
    }

    private record ControllerNode(SpringExplorerModel.ControllerEndpoints controller) {
    }

    private record EndpointNode(SpringEndpoint endpoint) {
    }

    private record MappingGroupNode(SpringExplorerModel.MappingGroup group) {
    }

    private record JpaEntityNode(JpaEntity entity) {
    }

    private record JpaFieldNode(JpaEntity entity, JpaField field) {
    }

    private record JpaRepositoryNode(JpaRepositoryInfo repository) {
    }

    private record JpaMethodNode(JpaRepositoryInfo repository, JpaQueryMethod method) {
    }

    private record MappingNode(SpringActuatorClient.LiveMapping mapping) {
    }

    private static final class CollectionPane extends JPanel {

        private final CardLayout cards = new CardLayout();
        private final EmptyStatePanel empty = new EmptyStatePanel();

        private CollectionPane(JComponent content, javax.swing.Icon icon) {
            setLayout(cards);
            setOpaque(false);
            empty.setIcon(icon).setDashedBorder(false)
                    .setArc(UiTokens.radius(UiTokens.Radius.MD));
            add(content, CARD_CONTENT);
            add(empty, CARD_EMPTY);
        }

        private void show(boolean hasContent, String title, String description) {
            if (hasContent) {
                cards.show(this, CARD_CONTENT);
            } else {
                empty.setTitle(title).setDescription(description);
                cards.show(this, CARD_EMPTY);
            }
        }
    }

    private static final class LiveBeanTableModel extends AbstractTableModel {

        private final String[] columns = {
                text("column.bean", "Bean"), text("column.type", "Tipo"),
                text("column.scope", "Escopo"), text("column.dependencies", "Dependencias")
        };
        private List<SpringActuatorClient.LiveBean> rows = List.of();

        void setRows(List<SpringActuatorClient.LiveBean> beans) {
            rows = beans == null ? List.of() : new ArrayList<>(beans);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            SpringActuatorClient.LiveBean row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.name();
                case 1 -> row.simpleType();
                case 2 -> row.scope();
                default -> String.join(", ", row.dependencies());
            };
        }
    }

    private static final class LivePropertyTableModel extends AbstractTableModel {

        private final String[] columns = {
                text("column.property", "Propriedade"), text("column.value", "Valor efetivo"),
                text("column.source", "Origem")
        };
        private List<SpringActuatorClient.LiveProperty> rows = List.of();

        void setRows(List<SpringActuatorClient.LiveProperty> properties) {
            rows = properties == null ? List.of() : new ArrayList<>(properties);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            SpringActuatorClient.LiveProperty row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.key();
                case 1 -> row.value();
                default -> row.source();
            };
        }
    }
}
