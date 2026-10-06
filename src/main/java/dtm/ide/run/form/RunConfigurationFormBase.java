package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.run.JavaRunValidation;
import dtm.ide.run.RunPaths;
import dtm.ide.run.chain.RunChainStep;
import dtm.ide.sdk.JdkInstallation;
import dtm.stools.component.inputfields.textarea.TextAreaField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class RunConfigurationFormBase implements RunConfigurationForm {

    protected static String text(Class<?> owner, String key, String fallback) {
        return I18n.getText(owner, key, fallback);
    }

    protected final String type;
    protected final RunFormContext context;

    private final RunFormUi.Page page = RunFormUi.page();
    private final ScrollPanel scroll = new ScrollPanel(page);
    private final Map<String, FormFieldCell> cells = new LinkedHashMap<>();
    private final Map<String, Object> preserved = new LinkedHashMap<>();

    private final JComboBox<String> moduleField = RunFormUi.combo(false);
    private final ValueChoice jdkField = new ValueChoice(RunFormUi.combo(false));
    private final MaskedTextField workingDirectory = RunFormUi.text("");
    private final TextAreaField environment = RunFormUi.textArea("APP_ENV=dev", 3);
    private final JCheckBox buildBeforeRun = RunFormUi.checkBox(
            common("field.buildBeforeRun", "Compilar o projeto antes de executar"));

    private BeforeLaunchPanel beforeLaunchPanel;
    private String configurationId = "";

    private boolean built;
    private String moduleName = "";
    private String jdkHome = "";
    private RunFormChoices choices = RunFormChoices.empty();

    protected RunConfigurationFormBase(String type, RunFormContext context) {
        this.type = type;
        this.context = context == null ? RunFormContext.of(() -> null) : context;
        scroll.setHorizontalScrollEnabled(false);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        moduleField.addActionListener(event -> {
            Object selected = moduleField.getSelectedItem();
            if (selected != null) {
                moduleName = selected.toString().trim();
            }
        });
        jdkField.combo().addActionListener(event -> jdkHome = jdkField.value());
    }

    protected RunFormChoices choices() {
        return choices;
    }

    private static String common(String key, String fallback) {
        return I18n.getText(RunConfigurationFormBase.class, key, fallback);
    }

    protected abstract void buildSections();

    protected abstract void collect(Map<String, Object> properties);

    protected abstract void apply(RunConfigurationData configuration);

    @Override
    public JComponent getComponent() {
        ensureBuilt();
        return scroll;
    }

    @Override
    public final RunConfigurationData getData() {
        ensureBuilt();
        Map<String, Object> properties = new LinkedHashMap<>(preserved);
        collect(properties);
        if (beforeLaunchPanel != null) {
            properties.put(JavaRunTypes.BEFORE_LAUNCH_CHAIN,
                    RunChainStep.encodeAll(beforeLaunchPanel.steps()));
        }
        validate(properties);

        return RunConfigurationData.builder()
                .type(type)
                .title(null)
                .properties(properties)
                .build();
    }

    @Override
    public final void setData(RunConfigurationData configuration) {
        ensureBuilt();
        preserved.clear();
        if (configuration != null && configuration.getProperties() != null) {
            preserved.putAll(configuration.getProperties());
        }
        configurationId = configuration == null || configuration.getId() == null
                ? "" : configuration.getId();
        if (beforeLaunchPanel != null) {
            beforeLaunchPanel.setSteps(RunChainStep.decodeAll(
                    value(propertiesOf(configuration), JavaRunTypes.BEFORE_LAUNCH_CHAIN)));
        }
        apply(configuration);
        validate(collected());
    }

    private Map<String, Object> collected() {
        Map<String, Object> properties = new LinkedHashMap<>(preserved);
        collect(properties);
        if (beforeLaunchPanel != null) {
            properties.put(JavaRunTypes.BEFORE_LAUNCH_CHAIN,
                    RunChainStep.encodeAll(beforeLaunchPanel.steps()));
        }
        return properties;
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        built = true;
        buildSections();
        context.requestChoices(this::onChoicesReady);
    }

    private void onChoicesReady(RunFormChoices ready) {
        choices = ready == null ? RunFormChoices.empty() : ready;
        applyChoices(choices);
        revalidateFields();
    }

    protected void validate(Map<String, Object> properties) {
        validate(properties, true);
    }

    private void validate(Map<String, Object> properties, boolean checkFileSystem) {
        JavaRunValidation.Context validationContext = checkFileSystem
                ? JavaRunValidation.Context.of(context.descriptor())
                : JavaRunValidation.Context.lenient(context.descriptor());
        JavaRunValidation.Report report = JavaRunValidation.validate(type, properties,
                validationContext);
        Map<String, String> messages = report.byField();
        cells.forEach((name, cell) -> {
            String message = messages.get(name);
            if (message == null) {
                cell.clearError();
            } else {
                cell.setError(message);
            }
        });
    }

    protected void revalidateFields() {
        if (built) {
            validate(collected(), false);
        }
    }

    protected FormSection section(String title, String subtitle) {
        FormSection section = RunFormUi.section(title, subtitle);
        page.section(section);
        return section;
    }

    protected FormFieldCell register(String name, FormFieldCell cell) {
        cells.put(name, cell);
        return cell;
    }

    protected FormFieldCell field(String name, String label, JComponent control) {
        return register(name, RunFormUi.field(label, control));
    }

    FormFieldCell cell(String name) {
        return cells.get(name);
    }

    protected FormSection environmentSection() {
        FormSection section = section(
                common("section.environment", "Ambiente"),
                common("section.environment.hint",
                        "Diretorio de trabalho e variaveis de ambiente do processo."));

        section.addWide(JavaRunTypes.WORKING_DIRECTORY, register(JavaRunTypes.WORKING_DIRECTORY,
                RunFormUi.fieldWithButton(
                        common("field.workingDirectory", "Diretorio de trabalho"),
                        workingDirectory,
                        RunFormUi.browse(common("action.chooseDirectory", "Escolher diretorio"),
                                this::chooseWorkingDirectory))
                        .helper(common("field.workingDirectory.hint",
                                "Vazio usa a raiz do modulo. Caminhos relativos partem do modulo."))));

        section.addWide(JavaRunTypes.ENVIRONMENT, register(JavaRunTypes.ENVIRONMENT,
                RunFormUi.field(common("field.environment", "Variaveis de ambiente"), environment)
                        .helper(common("field.environment.hint",
                                "Uma por linha, no formato NOME=valor."))));
        return section;
    }

    protected FormSection beforeLaunchSection() {
        FormSection section = section(
                common("section.beforeLaunch", "Antes de executar"),
                common("section.beforeLaunch.hint",
                        "Configuracoes ja salvas executadas em ordem antes desta."));
        beforeLaunchPanel = new BeforeLaunchPanel(
                () -> context.configurations(), () -> configurationId, this::revalidateFields,
                context::dialogBuilder, context::componentDialogBuilder);
        section.addComponent(beforeLaunchPanel);
        return section;
    }

    protected FormFieldCell moduleCell() {
        return field(JavaRunTypes.MODULE, common("field.module", "Modulo"), moduleField);
    }

    protected FormFieldCell jdkCell() {
        return field(JavaRunTypes.JDK_HOME, common("field.jdk", "JDK"), jdkField.combo())
                .helper(common("field.jdk.hint",
                        "A JDK do projeto e usada quando nenhuma outra e escolhida."));
    }

    protected FormFieldCell buildBeforeRunCell() {
        return register(JavaRunTypes.BUILD_BEFORE_RUN,
                RunFormUi.field(common("field.beforeLaunch", "Antes de executar"), buildBeforeRun));
    }

    protected void collectShared(Map<String, Object> properties) {
        properties.put(JavaRunTypes.MODULE, moduleName);
        properties.put(JavaRunTypes.JDK_HOME, jdkHome);
        properties.put(JavaRunTypes.WORKING_DIRECTORY, workingDirectory.getText().trim());
        properties.put(JavaRunTypes.ENVIRONMENT, environment.getText().trim());
        properties.put(JavaRunTypes.BUILD_BEFORE_RUN,
                Boolean.toString(buildBeforeRun.isSelected()));
    }

    protected void applyShared(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        moduleName = value(properties, JavaRunTypes.MODULE);
        jdkHome = value(properties, JavaRunTypes.JDK_HOME);
        moduleField.setSelectedItem(moduleName);
        jdkField.select(jdkHome);
        workingDirectory.setText(value(properties, JavaRunTypes.WORKING_DIRECTORY));
        environment.setText(normalizeEnvironment(value(properties, JavaRunTypes.ENVIRONMENT)));
        buildBeforeRun.setSelected(JavaRunValidation.flag(properties,
                JavaRunTypes.BUILD_BEFORE_RUN, JavaRunTypes.buildBeforeRunDefault(type)));
    }

    static String normalizeEnvironment(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\n")) {
            return raw == null ? "" : raw;
        }
        List<String> entries = new ArrayList<>();
        for (String part : raw.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("=") || entries.isEmpty()) {
                entries.add(entry);
            } else {
                entries.set(entries.size() - 1, entries.getLast() + "," + entry);
            }
        }
        return String.join(System.lineSeparator(), entries);
    }

    protected void applyChoices(RunFormChoices choices) {
        if (beforeLaunchPanel != null) {
            beforeLaunchPanel.refreshLabels();
        }
        RunFormUi.fill(moduleField, choices.modules(), moduleName);
        jdkField.setOptions(choices.jdks());
        jdkField.select(jdkHome);
    }

    protected String selectedModuleName() {
        return moduleName;
    }

    protected Optional<JavaModule> selectedModule() {
        JavaProjectDescriptor descriptor = context.descriptor();
        if (descriptor == null) {
            return Optional.empty();
        }
        String name = selectedModuleName();
        if (name.isBlank()) {
            return Optional.ofNullable(descriptor.rootModule());
        }
        return descriptor.modules().stream()
                .filter(module -> module.name().equals(name))
                .findFirst();
    }

    protected void selectModule(String name) {
        if (name != null && !name.isBlank()) {
            moduleName = name;
            moduleField.setSelectedItem(name);
        }
    }

    protected void clearModuleSelection() {
        moduleName = "";
        moduleField.setSelectedItem(null);
    }

    protected JComboBox<String> moduleControl() {
        return moduleField;
    }

    protected Path base() {
        return RunPaths.base(selectedModule().orElse(null), context.descriptor()).orElse(null);
    }

    protected String relativize(Path path) {
        return RunPaths.relativize(path, selectedModule().orElse(null), context.descriptor());
    }

    private void chooseWorkingDirectory() {
        RunFormUi.chooseDirectory(page, common("action.chooseDirectory", "Escolher diretorio"),
                base(), path -> {
                    workingDirectory.setText(relativize(path));
                    revalidateFields();
                });
    }

    protected void revalidateOnEdit(javax.swing.text.JTextComponent field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                revalidateFields();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                revalidateFields();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                revalidateFields();
            }
        });
    }

    protected static Map<String, Object> propertiesOf(RunConfigurationData configuration) {
        return configuration == null || configuration.getProperties() == null
                ? Map.of() : configuration.getProperties();
    }

    protected static String value(Map<String, Object> properties, String key) {
        Object raw = properties.get(key);
        return raw == null ? "" : raw.toString().trim();
    }

    protected MaskedTextField workingDirectoryField() {
        return workingDirectory;
    }

    protected TextAreaField environmentField() {
        return environment;
    }

    protected JCheckBox buildBeforeRunField() {
        return buildBeforeRun;
    }

    protected RunFormUi.Page page() {
        return page;
    }

    protected int gap() {
        return UiTokens.space(2);
    }
}
