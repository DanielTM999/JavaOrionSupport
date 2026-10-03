package dtm.ide.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

public final class JavaSettingsPage implements PluginSettingsPage {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaSettingsPage.class, key, fallback);
    }

    private static final List<String> MEMORY_OPTIONS = List.of("1G", "2G", "3G", "4G", "6G");
    private static final List<Integer> JDK_OPTIONS = List.of(8, 11, 17, 21, 25);

    private final JavaPluginSettings settings;
    private final dtm.ide.inspection.InspectionSuppressionStore suppressions;
    private final java.nio.file.Path projectRoot;
    private final java.util.Map<String, JCheckBox> hiddenOccurrences =
            new java.util.LinkedHashMap<>();
    private final Runnable onChanged;

    private final JComboBox<LanguageServerMode> languageServerMode =
            new JComboBox<>(LanguageServerMode.values());
    private final JComboBox<String> languageServerMemory =
            new JComboBox<>(MEMORY_OPTIONS.toArray(String[]::new));
    private final JComboBox<Integer> defaultJdk =
            new JComboBox<>(JDK_OPTIONS.toArray(Integer[]::new));
    private final JComboBox<HotReloadMode> hotReloadMode =
            new JComboBox<>(HotReloadMode.values());
    private final JComboBox<InlayHintsMode> inlayHints =
            new JComboBox<>(InlayHintsMode.values());
    private final JCheckBox breakOnCaughtExceptions =
            new JCheckBox(text("field.breakOnCaughtExceptions",
                    "Depurador: parar tambem em excecoes capturadas"));
    private final JComboBox<JdtBuildMode> jdtBuildMode =
            new JComboBox<>(JdtBuildMode.values());

    private final JCheckBox formatOnSave =
            new JCheckBox(text("field.formatOnSave", "Formatar ao salvar"));
    private final JCheckBox organizeImportsOnSave =
            new JCheckBox(text("field.organizeImports", "Organizar imports ao salvar"));
    private final JCheckBox buildOffline =
            new JCheckBox(text("field.offline", "Construir sem acessar a rede"));
    private final JCheckBox skipTestsOnRun =
            new JCheckBox(text("field.skipTests", "Pular testes ao compilar para executar"));
    private final JCheckBox incrementalBuild =
            new JCheckBox(text("field.incrementalBuild",
                    "Compilar so o que mudou antes de executar"));
    private final JCheckBox buildOnOpen =
            new JCheckBox(text("field.buildOnOpen", "Compilar o projeto ao abrir"));

    private final JCheckBox buildFileCompletion =
            new JCheckBox(text("field.buildFileCompletion",
                    "Completar dependencias no pom.xml e no build.gradle"));
    private final JCheckBox coverageGutter =
            new JCheckBox(text("field.coverageGutter",
                    "Marcar cobertura de codigo na barra lateral do editor"));
    private final JTextField todoMarkers = new JTextField();

    private final JCheckBox lombokSupport =
            new JCheckBox(text("field.lombokSupport",
                    "Ativar Lombok e annotation processors no IntelliSense"));

    private final JCheckBox springSupport =
            new JCheckBox(text("field.springSupport", "Ativar suporte a Spring"));
    private final JCheckBox springCodeLens =
            new JCheckBox(text("field.springCodeLens", "Mostrar contagem de injecoes no editor"));
    private final JCheckBox springLive =
            new JCheckBox(text("field.springLive", "Consultar o Actuator da aplicacao em execucao"));
    private final JCheckBox springNavigation =
            new JCheckBox(text("field.springNavigation",
                    "Navegar de injecoes e qualifiers para o bean"));
    private final JCheckBox springJpa =
            new JCheckBox(text("field.springJpa", "Analisar entidades JPA e repositorios"));
    private final java.util.Map<dtm.ide.inspection.JavaInspection, JCheckBox> inspections =
            new java.util.LinkedHashMap<>();
    private final JCheckBox springInfra =
            new JCheckBox(text("field.springInfra",
                    "Analisar @Scheduled, eventos, cache e seguranca"));
    private final JCheckBox springRuntimeBeans =
            new JCheckBox(text("field.springRuntimeBeans",
                    "Adotar os beans da aplicacao em execucao (Actuator)"));
    private final JCheckBox springConfigNavigation =
            new JCheckBox(text("field.springConfigNavigation",
                    "Ligar @Value e @ConfigurationProperties aos arquivos de configuracao"));
    private final JTextField springBaseUrl = new JTextField();

    private final JCheckBox dependencySearchLocalOnly =
            new JCheckBox(text("field.dependencySearchLocalOnly",
                    "Buscar dependencias somente no repositorio Maven local"));
    private final JSpinner dependencySearchTimeout = new JSpinner(new SpinnerNumberModel(
            JavaPluginSettings.DEFAULT_DEPENDENCY_SEARCH_TIMEOUT,
            JavaPluginSettings.MIN_DEPENDENCY_SEARCH_TIMEOUT,
            JavaPluginSettings.MAX_DEPENDENCY_SEARCH_TIMEOUT, 1));

    private final JPanel panel = new JPanel(new BorderLayout());

    public JavaSettingsPage(JavaPluginSettings settings, Runnable onChanged) {
        this(settings, onChanged, null, null);
    }

    public JavaSettingsPage(JavaPluginSettings settings, Runnable onChanged,
                            dtm.ide.inspection.InspectionSuppressionStore suppressions,
                            java.nio.file.Path projectRoot) {
        this.settings = settings;
        this.suppressions = suppressions;
        this.projectRoot = projectRoot;
        this.onChanged = onChanged == null ? () -> {
        } : onChanged;
        buildPanel();
        loadFromSettings();
    }

    @Override
    public String getTitle() {
        return text("title", "Java / Spring");
    }

    @Override
    public JComponent getView() {
        return panel;
    }

    @Override
    public void onApply() {
        settings.setLanguageServerMode((LanguageServerMode) languageServerMode.getSelectedItem());
        settings.setLanguageServerMemory((String) languageServerMemory.getSelectedItem());
        settings.setDefaultJdkVersion(defaultJdk.getSelectedItem() == null
                ? 21 : (Integer) defaultJdk.getSelectedItem());

        settings.setFormatOnSave(formatOnSave.isSelected());
        settings.setOrganizeImportsOnSave(organizeImportsOnSave.isSelected());
        settings.setBuildOffline(buildOffline.isSelected());
        settings.setSkipTestsOnRun(skipTestsOnRun.isSelected());
        settings.setIncrementalBuild(incrementalBuild.isSelected());
        settings.setBuildOnProjectOpen(buildOnOpen.isSelected());
        settings.setHotReloadMode((HotReloadMode) hotReloadMode.getSelectedItem());
        settings.setInlayHints((InlayHintsMode) inlayHints.getSelectedItem());
        settings.setBreakOnCaughtExceptions(breakOnCaughtExceptions.isSelected());
        settings.setJdtBuildMode((JdtBuildMode) jdtBuildMode.getSelectedItem());

        settings.setLombokSupport(lombokSupport.isSelected());
        settings.setBuildFileCompletion(buildFileCompletion.isSelected());
        settings.setCoverageGutter(coverageGutter.isSelected());
        settings.setTodoMarkers(parseMarkers(todoMarkers.getText()));
        settings.setSpringSupport(springSupport.isSelected());
        settings.setSpringCodeLens(springCodeLens.isSelected());
        settings.setSpringLive(springLive.isSelected());
        settings.setSpringNavigation(springNavigation.isSelected());
        settings.setSpringJpa(springJpa.isSelected());
        settings.setSpringConfigNavigation(springConfigNavigation.isSelected());
        settings.setSpringRuntimeBeans(springRuntimeBeans.isSelected());
        settings.setSpringInfra(springInfra.isSelected());
        inspections.forEach((inspection, box) ->
                settings.setInspectionDisabled(inspection.id(), !box.isSelected()));
        if (suppressions != null) {
            hiddenOccurrences.forEach((key, box) -> {
                if (!box.isSelected()) {
                    suppressions.restore(key);
                }
            });
        }
        settings.setSpringBaseUrl(springBaseUrl.getText());
        settings.setDependencySearchLocalOnly(dependencySearchLocalOnly.isSelected());
        settings.setDependencySearchTimeoutSeconds((Integer) dependencySearchTimeout.getValue());

        settings.save();
        onChanged.run();
    }

    @Override
    public void onRestoreDefaults() {
        settings.restoreDefaults();
        settings.save();
        loadFromSettings();
        onChanged.run();
    }

    private void buildPanel() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        content.add(section(text("section.intelliSense", "IntelliSense"),
                labeled(text("field.languageServerMode", "Modo:"), languageServerMode),
                labeled(text("field.languageServerMemory", "Memoria do servidor:"),
                        languageServerMemory),
                labeled(text("field.inlayHints", "Dicas inline (inlay hints):"), inlayHints),
                formatOnSave,
                organizeImportsOnSave));

        content.add(section(text("section.project", "Projeto"),
                labeled(text("field.defaultJdk", "JDK padrao:"), defaultJdk),
                lombokSupport,
                buildFileCompletion,
                labeled(text("field.todoMarkers", "Marcadores do painel TODO:"), todoMarkers)));

        content.add(section(text("section.buildRun", "Build e execucao"),
                buildOffline,
                skipTestsOnRun,
                incrementalBuild,
                buildOnOpen,
                coverageGutter,
                labeled(text("field.hotReload", "Hot reload:"), hotReloadMode),
                breakOnCaughtExceptions,
                labeled(text("field.jdtBuildMode", "Erros do projeto:"), jdtBuildMode)));

        content.add(section(text("section.dependencies", "Dependencias"),
                dependencySearchLocalOnly,
                labeled(text("field.dependencySearchTimeout",
                        "Tempo limite da busca web (s):"), dependencySearchTimeout)));

        dependencySearchLocalOnly.addActionListener(event -> updateDependencyControls());

        content.add(inspectionsSection());
        content.add(hiddenOccurrencesSection());

        content.add(section(text("section.spring", "Spring"),
                springSupport,
                springCodeLens,
                springNavigation,
                springJpa,
                springConfigNavigation,
                springRuntimeBeans,
                springInfra,
                springLive,
                labeled(text("field.springBaseUrl", "URL da aplicacao:"), springBaseUrl)));

        springSupport.addActionListener(event -> updateSpringControls());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
    }

    private JPanel inspectionsSection() {
        for (dtm.ide.inspection.JavaInspection inspection
                : dtm.ide.inspection.JavaInspection.all()) {
            JCheckBox box = new JCheckBox(inspectionLabel(inspection));
            box.setToolTipText(inspection.id());
            inspections.put(inspection, box);
        }
        return section(text("section.inspections", "Inspecoes"),
                inspections.values().toArray(new JComponent[0]));
    }

    private JPanel hiddenOccurrencesSection() {
        if (suppressions == null) {
            return section(text("section.hiddenOccurrences", "Avisos ocultados"),
                    new JLabel(text("hidden.unavailable", "Indisponivel sem projeto aberto.")));
        }
        java.util.List<dtm.ide.inspection.InspectionSuppressionStore.Entry> entries =
                suppressions.entriesOf(projectRoot);
        if (entries.isEmpty()) {
            return section(text("section.hiddenOccurrences", "Avisos ocultados"),
                    new JLabel(text("hidden.empty", "Nenhum aviso ocultado neste projeto.")));
        }
        java.util.List<JComponent> controls = new java.util.ArrayList<>();
        for (dtm.ide.inspection.InspectionSuppressionStore.Entry entry : entries) {
            String label = dtm.ide.inspection.JavaInspection.byId(entry.inspectionId())
                    .map(dtm.ide.inspection.JavaInspection::label)
                    .orElse(entry.inspectionId());
            JCheckBox box = new JCheckBox(label + "  -  " + entry.describe(), true);
            box.setToolTipText(entry.describe());
            hiddenOccurrences.put(entry.key(), box);
            controls.add(box);
        }
        return section(text("section.hiddenOccurrences", "Avisos ocultados"),
                controls.toArray(new JComponent[0]));
    }

    private String inspectionLabel(dtm.ide.inspection.JavaInspection inspection) {
        return text("inspection." + inspection.id(), inspection.label());
    }

    private JPanel section(String title, JComponent... controls) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (JComponent control : controls) {
            control.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(control);
            section.add(Box.createVerticalStrut(4));
        }
        return section;
    }

    private static java.util.List<String> parseMarkers(String raw) {
        java.util.List<String> markers = new java.util.ArrayList<>();
        for (String part : raw == null ? new String[0] : raw.split("[,;\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                markers.add(trimmed.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return markers;
    }

    private JPanel labeled(String label, JComponent field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel(label));
        field.setPreferredSize(new Dimension(240, field.getPreferredSize().height));
        row.add(field);
        return row;
    }

    private void loadFromSettings() {
        languageServerMode.setSelectedItem(settings.getLanguageServerMode());
        languageServerMemory.setSelectedItem(settings.getLanguageServerMemory());
        defaultJdk.setSelectedItem(settings.getDefaultJdkVersion());
        lombokSupport.setSelected(settings.isLombokSupport());
        buildFileCompletion.setSelected(settings.isBuildFileCompletion());
        coverageGutter.setSelected(settings.isCoverageGutter());
        todoMarkers.setText(String.join(", ", settings.getTodoMarkers()));

        formatOnSave.setSelected(settings.isFormatOnSave());
        organizeImportsOnSave.setSelected(settings.isOrganizeImportsOnSave());
        buildOffline.setSelected(settings.isBuildOffline());
        skipTestsOnRun.setSelected(settings.isSkipTestsOnRun());
        incrementalBuild.setSelected(settings.isIncrementalBuild());
        buildOnOpen.setSelected(settings.isBuildOnProjectOpen());
        hotReloadMode.setSelectedItem(settings.getHotReloadMode());
        inlayHints.setSelectedItem(settings.getInlayHints());
        breakOnCaughtExceptions.setSelected(settings.isBreakOnCaughtExceptions());
        jdtBuildMode.setSelectedItem(settings.getJdtBuildMode());

        springSupport.setSelected(settings.isSpringSupport());
        springCodeLens.setSelected(settings.isSpringCodeLens());
        springLive.setSelected(settings.isSpringLive());
        springNavigation.setSelected(settings.isSpringNavigation());
        springJpa.setSelected(settings.isSpringJpa());
        springConfigNavigation.setSelected(settings.isSpringConfigNavigation());
        springRuntimeBeans.setSelected(settings.isSpringRuntimeBeans());
        springInfra.setSelected(settings.isSpringInfra());
        inspections.forEach((inspection, box) ->
                box.setSelected(!settings.isInspectionDisabled(inspection.id())));
        springBaseUrl.setText(settings.getSpringBaseUrl());

        dependencySearchLocalOnly.setSelected(settings.isDependencySearchLocalOnly());
        dependencySearchTimeout.setValue(settings.getDependencySearchTimeoutSeconds());

        updateSpringControls();
        updateDependencyControls();
    }

    private void updateDependencyControls() {
        dependencySearchTimeout.setEnabled(!dependencySearchLocalOnly.isSelected());
    }

    private void updateSpringControls() {
        boolean enabled = springSupport.isSelected();
        springCodeLens.setEnabled(enabled);
        springLive.setEnabled(enabled);
        springNavigation.setEnabled(enabled);
        springJpa.setEnabled(enabled);
        springConfigNavigation.setEnabled(enabled);
        springRuntimeBeans.setEnabled(enabled);
        springInfra.setEnabled(enabled);
        springBaseUrl.setEnabled(enabled);
    }
}
