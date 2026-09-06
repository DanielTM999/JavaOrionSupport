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
import javax.swing.JTextField;
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
    private final Runnable onChanged;

    private final JComboBox<LanguageServerMode> languageServerMode =
            new JComboBox<>(LanguageServerMode.values());
    private final JComboBox<String> languageServerMemory =
            new JComboBox<>(MEMORY_OPTIONS.toArray(String[]::new));
    private final JComboBox<Integer> defaultJdk =
            new JComboBox<>(JDK_OPTIONS.toArray(Integer[]::new));
    private final JComboBox<HotReloadMode> hotReloadMode =
            new JComboBox<>(HotReloadMode.values());
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

    private final JCheckBox buildFileCompletion =
            new JCheckBox(text("field.buildFileCompletion",
                    "Completar dependencias no pom.xml e no build.gradle"));
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
    private final JTextField springBaseUrl = new JTextField();

    private final JPanel panel = new JPanel(new BorderLayout());

    public JavaSettingsPage(JavaPluginSettings settings, Runnable onChanged) {
        this.settings = settings;
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
        settings.setHotReloadMode((HotReloadMode) hotReloadMode.getSelectedItem());
        settings.setJdtBuildMode((JdtBuildMode) jdtBuildMode.getSelectedItem());

        settings.setLombokSupport(lombokSupport.isSelected());
        settings.setBuildFileCompletion(buildFileCompletion.isSelected());
        settings.setTodoMarkers(parseMarkers(todoMarkers.getText()));
        settings.setSpringSupport(springSupport.isSelected());
        settings.setSpringCodeLens(springCodeLens.isSelected());
        settings.setSpringLive(springLive.isSelected());
        settings.setSpringBaseUrl(springBaseUrl.getText());

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
                labeled(text("field.hotReload", "Hot reload:"), hotReloadMode),
                labeled(text("field.jdtBuildMode", "Erros do projeto:"), jdtBuildMode)));

        content.add(section(text("section.spring", "Spring"),
                springSupport,
                springCodeLens,
                springLive,
                labeled(text("field.springBaseUrl", "URL da aplicacao:"), springBaseUrl)));

        springSupport.addActionListener(event -> updateSpringControls());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
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
        todoMarkers.setText(String.join(", ", settings.getTodoMarkers()));

        formatOnSave.setSelected(settings.isFormatOnSave());
        organizeImportsOnSave.setSelected(settings.isOrganizeImportsOnSave());
        buildOffline.setSelected(settings.isBuildOffline());
        skipTestsOnRun.setSelected(settings.isSkipTestsOnRun());
        hotReloadMode.setSelectedItem(settings.getHotReloadMode());
        jdtBuildMode.setSelectedItem(settings.getJdtBuildMode());

        springSupport.setSelected(settings.isSpringSupport());
        springCodeLens.setSelected(settings.isSpringCodeLens());
        springLive.setSelected(settings.isSpringLive());
        springBaseUrl.setText(settings.getSpringBaseUrl());

        updateSpringControls();
    }

    private void updateSpringControls() {
        boolean enabled = springSupport.isSelected();
        springCodeLens.setEnabled(enabled);
        springLive.setEnabled(enabled);
        springBaseUrl.setEnabled(enabled);
    }
}
