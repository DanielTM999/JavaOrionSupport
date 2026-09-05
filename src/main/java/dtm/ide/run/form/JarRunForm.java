package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.run.JavaRunTypes;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.i18n.I18n;

import javax.swing.JComboBox;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class JarRunForm extends RunConfigurationFormBase {

    private static String text(String key, String fallback) {
        return I18n.getText(JarRunForm.class, key, fallback);
    }

    private final JComboBox<String> jarPath = RunFormUi.combo(true);
    private final MaskedTextField programArguments = RunFormUi.text("--server.port=8080");
    private final MaskedTextField vmOptions = RunFormUi.text("-Xmx512m");
    private final SegmentedField<String> jarSource = new SegmentedField<>();

    private FormFieldCell jarPathCell;
    private FormFieldCell moduleCell;
    private FormFieldCell buildBeforeRunCell;

    public JarRunForm(RunFormContext context) {
        super(JavaRunTypes.JAR, context);
    }

    @Override
    protected void buildSections() {
        FormSection execution = section(text("section.execution", "Execucao"),
                text("section.execution.hint", "Arquivo JAR executavel e seus argumentos."));

        jarSource.addSegment(text("source.project", "Do projeto"), JavaRunTypes.JAR_SOURCE_PROJECT)
                .addSegment(text("source.external", "Externo"), JavaRunTypes.JAR_SOURCE_EXTERNAL);
        RunFormUi.sized(jarSource);
        execution.addWide(JavaRunTypes.JAR_SOURCE, register(JavaRunTypes.JAR_SOURCE,
                RunFormUi.field(text("field.jarSource", "Origem do JAR"), jarSource)));

        jarPathCell = register(JavaRunTypes.JAR_PATH,
                RunFormUi.fieldWithButton(text("field.jarPath", "Arquivo JAR"), jarPath,
                        RunFormUi.browse(text("action.chooseJar", "Escolher arquivo JAR"),
                                this::chooseJar)));
        execution.addWide(JavaRunTypes.JAR_PATH, jarPathCell);

        moduleCell = moduleCell();
        execution.add(JavaRunTypes.MODULE, moduleCell);
        buildBeforeRunCell = buildBeforeRunCell();
        execution.add(JavaRunTypes.BUILD_BEFORE_RUN, buildBeforeRunCell);
        execution.addWide(JavaRunTypes.PROGRAM_ARGUMENTS, field(JavaRunTypes.PROGRAM_ARGUMENTS,
                text("field.programArguments", "Argumentos do programa"), programArguments));

        FormSection jvm = section(text("section.jvm", "JVM"),
                text("section.jvm.hint", "Opcoes da maquina virtual e JDK usada."));
        jvm.addWide(JavaRunTypes.VM_OPTIONS, field(JavaRunTypes.VM_OPTIONS,
                text("field.vmOptions", "Opcoes da JVM"), vmOptions));
        jvm.add(JavaRunTypes.JDK_HOME, jdkCell());

        buildBeforeRunField().setText(text("field.buildBeforeRun",
                "Empacotar o modulo antes de executar"));
        buildBeforeRunField().addActionListener(event -> revalidateFields());

        environmentSection();
        beforeLaunchSection();

        jarSource.addEventListener(SegmentedField.SEGMENT_SELECTED, event -> {
            updateSourceFields();
            revalidateFields();
        });
        updateSourceFields();
    }

    private boolean isExternal() {
        return JavaRunTypes.JAR_SOURCE_EXTERNAL.equals(jarSource.getSelectedValue());
    }

    private void updateSourceFields() {
        boolean external = isExternal();
        moduleCell.setVisible(!external);
        buildBeforeRunCell.setVisible(!external);
        if (external) {
            clearModuleSelection();
            buildBeforeRunField().setSelected(false);
        }
        jarPathCell.helper(external
                ? text("field.jarPath.externalHint",
                        "Caminho completo de um JAR fora do projeto.")
                : text("field.jarPath.hint",
                        "Sugestoes vindas de target e build/libs; caminhos relativos "
                                + "partem do modulo."));
        applyJarSuggestions();
        page().revalidate();
        page().repaint();
    }

    private void applyJarSuggestions() {
        String selected = RunFormUi.valueOf(jarPath);
        List<String> suggestions = isExternal() ? List.of()
                : choices().jars().stream().map(this::relativize).toList();
        RunFormUi.fill(jarPath, suggestions, selected);
        jarPath.setSelectedItem(selected);
    }

    private void chooseJar() {
        RunFormUi.chooseFile(page(), text("action.chooseJar", "Escolher arquivo JAR"),
                base(), "jar", path -> {
                    jarPath.setSelectedItem(isExternal()
                            ? path.toAbsolutePath().normalize().toString()
                            : relativize(path));
                    revalidateFields();
                });
    }

    @Override
    protected Path base() {
        return isExternal() ? null : super.base();
    }

    @Override
    protected void applyChoices(RunFormChoices choices) {
        super.applyChoices(choices);
        applyJarSuggestions();
    }

    @Override
    protected void collect(Map<String, Object> properties) {
        collectShared(properties);
        properties.put(JavaRunTypes.JAR_SOURCE, jarSource.getSelectedValue());
        properties.put(JavaRunTypes.JAR_PATH, RunFormUi.valueOf(jarPath));
        properties.put(JavaRunTypes.PROGRAM_ARGUMENTS, programArguments.getText().trim());
        properties.put(JavaRunTypes.VM_OPTIONS, vmOptions.getText().trim());
        if (isExternal()) {
            properties.put(JavaRunTypes.MODULE, "");
            properties.put(JavaRunTypes.BUILD_BEFORE_RUN, Boolean.FALSE.toString());
        }
    }

    @Override
    protected void apply(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        applyShared(configuration);
        jarSource.setSelectedValue(JavaRunTypes.isExternalJar(properties)
                ? JavaRunTypes.JAR_SOURCE_EXTERNAL : JavaRunTypes.JAR_SOURCE_PROJECT, false);
        jarPath.setSelectedItem(value(properties, JavaRunTypes.JAR_PATH));
        programArguments.setText(value(properties, JavaRunTypes.PROGRAM_ARGUMENTS));
        vmOptions.setText(value(properties, JavaRunTypes.VM_OPTIONS));
        updateSourceFields();
    }
}
