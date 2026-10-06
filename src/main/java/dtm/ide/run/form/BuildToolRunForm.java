package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.run.BuildTargetSuggestions;
import dtm.ide.run.JavaRunTypes;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.i18n.I18n;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import java.util.List;
import java.util.Map;

public final class BuildToolRunForm extends RunConfigurationFormBase {

    private static String text(String key, String fallback) {
        return I18n.getText(BuildToolRunForm.class, key, fallback);
    }

    private final JComboBox<String> targets = RunFormUi.combo(true);
    private final JComboBox<String> profiles = RunFormUi.combo(true);
    private final MaskedTextField runnerArguments = RunFormUi.text("-DskipTests -X");
    private final JCheckBox offline = RunFormUi.checkBox(
            I18n.getText(BuildToolRunForm.class, "field.offline", "Modo offline"));

    private final boolean gradle;

    public BuildToolRunForm(String type, RunFormContext context) {
        super(type, context);
        this.gradle = JavaRunTypes.GRADLE.equals(type);
    }

    private String targetKey() {
        return gradle ? JavaRunTypes.TASKS : JavaRunTypes.GOALS;
    }

    @Override
    protected void buildSections() {
        FormSection build = section(text("section.build", "Build"),
                gradle ? text("section.build.gradleHint",
                        "Tasks executadas pelo Gradle do projeto.")
                        : text("section.build.mavenHint",
                                "Objetivos executados pelo Maven do projeto."));

        build.addWide(targetKey(), register(targetKey(), RunFormUi.field(gradle
                                ? text("field.tasks", "Tasks")
                                : text("field.goals", "Objetivos"), targets)
                .helper(gradle ? text("field.tasks.hint", "Exemplo: clean build")
                        : text("field.goals.hint", "Exemplo: clean install"))));
        build.add(JavaRunTypes.MODULE, moduleCell());
        build.add(JavaRunTypes.OFFLINE, register(JavaRunTypes.OFFLINE,
                RunFormUi.field(text("field.execution", "Execucao"), offline)));

        if (!gradle) {
            build.add(JavaRunTypes.PROFILES, field(JavaRunTypes.PROFILES,
                            text("field.profiles", "Profiles"), profiles)
                    .helper(text("field.profiles.hint", "Separados por virgula.")));
        }
        build.addWide(JavaRunTypes.RUNNER_ARGUMENTS, field(JavaRunTypes.RUNNER_ARGUMENTS,
                        text("field.runnerArguments", "Argumentos adicionais"), runnerArguments)
                .helper(text("field.runnerArguments.hint",
                        "Repassados diretamente a linha de comando da ferramenta.")));

        FormSection jvm = section(text("section.jvm", "JVM"),
                text("section.jvm.hint", "JDK usada pelo processo do build tool."));
        jvm.add(JavaRunTypes.JDK_HOME, jdkCell());
        jvm.addComponent(RunFormUi.notice(text("notice.debug",
                "Debug fica desabilitado: depurar o processo Maven/Gradle nao depura a "
                        + "aplicacao produzida. Use a configuracao Remote JVM para isso.")));

        environmentSection();
        beforeLaunchSection();
    }

    @Override
    protected void applyChoices(RunFormChoices choices) {
        super.applyChoices(choices);
        String selectedTarget = RunFormUi.valueOf(targets);
        RunFormUi.fill(targets, choices.buildTargets(), selectedTarget);
        targets.setSelectedItem(selectedTarget);
        if (!gradle) {
            String selectedProfile = RunFormUi.valueOf(profiles);
            RunFormUi.fill(profiles, choices.mavenProfiles(), selectedProfile);
            profiles.setSelectedItem(selectedProfile);
        }
    }

    @Override
    protected void collect(Map<String, Object> properties) {
        collectShared(properties);
        properties.put(targetKey(), RunFormUi.valueOf(targets));
        properties.put(JavaRunTypes.RUNNER_ARGUMENTS, runnerArguments.getText().trim());
        properties.put(JavaRunTypes.OFFLINE, Boolean.toString(offline.isSelected()));
        if (!gradle) {
            properties.put(JavaRunTypes.PROFILES, RunFormUi.valueOf(profiles));
        }
    }

    @Override
    protected void apply(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        applyShared(configuration);
        targets.setSelectedItem(value(properties, targetKey()));
        runnerArguments.setText(value(properties, JavaRunTypes.RUNNER_ARGUMENTS));
        offline.setSelected(Boolean.parseBoolean(value(properties, JavaRunTypes.OFFLINE)));
        if (!gradle) {
            profiles.setSelectedItem(value(properties, JavaRunTypes.PROFILES));
        }
    }
}
