package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.run.MainClassScanner;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.i18n.I18n;

import javax.swing.JComboBox;
import java.awt.event.ItemEvent;
import java.util.List;
import java.util.Map;

/**
 * Formulario das configuracoes {@code java.run} e {@code java.springBoot}.
 *
 * <p>A secao "Spring Boot" aparece apenas para o tipo Spring Boot; as demais secoes sao
 * identicas nos dois casos.</p>
 */
public final class ApplicationRunForm extends RunConfigurationFormBase {

    private static String text(String key, String fallback) {
        return I18n.getText(ApplicationRunForm.class, key, fallback);
    }

    private final JComboBox<String> mainClass = RunFormUi.combo(true);
    private final MaskedTextField programArguments = RunFormUi.text("--modo rapido");
    private final MaskedTextField vmOptions = RunFormUi.text("-Xmx512m -Dfoo=bar");
    private final MaskedTextField springProfiles = RunFormUi.text("dev, local");
    private final MaskedTextField serverPort = RunFormUi.text("8080");

    private final boolean springBoot;
    private List<MainClassScanner.MainClass> candidates = List.of();

    public ApplicationRunForm(String type, RunFormContext context) {
        super(type, context);
        this.springBoot = JavaRunTypes.SPRING_BOOT.equals(type);
    }

    @Override
    protected void buildSections() {
        FormSection execution = section(text("section.execution", "Execucao"),
                text("section.execution.hint",
                        "Classe com o metodo main e argumentos passados a aplicacao."));
        execution.add(JavaRunTypes.MAIN_CLASS, field(JavaRunTypes.MAIN_CLASS,
                        text("field.mainClass", "Classe principal"), mainClass)
                .helper(text("field.mainClass.hint", "Exemplo: com.exemplo.Aplicacao")));
        execution.add(JavaRunTypes.MODULE, moduleCell());
        execution.addWide(JavaRunTypes.PROGRAM_ARGUMENTS, field(JavaRunTypes.PROGRAM_ARGUMENTS,
                        text("field.programArguments", "Argumentos do programa"), programArguments)
                .helper(text("field.programArguments.hint",
                        "Use aspas para agrupar valores com espacos.")));

        FormSection jvm = section(text("section.jvm", "JVM"),
                text("section.jvm.hint", "Opcoes da maquina virtual, JDK e build previo."));
        jvm.addWide(JavaRunTypes.VM_OPTIONS, field(JavaRunTypes.VM_OPTIONS,
                text("field.vmOptions", "Opcoes da JVM"), vmOptions));
        jvm.add(JavaRunTypes.JDK_HOME, jdkCell());
        jvm.add(JavaRunTypes.BUILD_BEFORE_RUN, buildBeforeRunCell());

        if (springBoot) {
            FormSection spring = section(text("section.spring", "Spring Boot"),
                    text("section.spring.hint",
                            "Perfis ativos e porta do servidor embarcado."));
            spring.add(JavaRunTypes.SPRING_PROFILES, field(JavaRunTypes.SPRING_PROFILES,
                            text("field.springProfiles", "Perfis ativos"), springProfiles)
                    .helper(text("field.springProfiles.hint", "Separados por virgula.")));
            spring.add(JavaRunTypes.SERVER_PORT, field(JavaRunTypes.SERVER_PORT,
                            text("field.serverPort", "Porta do servidor"), serverPort)
                    .helper(text("field.serverPort.hint",
                            "Vazio mantem a porta definida na aplicacao.")));
        }

        environmentSection();
        beforeLaunchSection();
        wireMainClassSelection();
        revalidateOnEdit(serverPort);
    }

    /** Ao escolher uma classe principal conhecida, seleciona automaticamente o modulo dela. */
    private void wireMainClassSelection() {
        mainClass.addItemListener(event -> {
            if (event.getStateChange() != ItemEvent.SELECTED) {
                return;
            }
            String selected = RunFormUi.valueOf(mainClass);
            candidates.stream()
                    .filter(candidate -> candidate.qualifiedName().equals(selected))
                    .findFirst()
                    .ifPresent(candidate -> selectModule(candidate.module().name()));
            revalidateFields();
        });
    }

    @Override
    protected void applyChoices(RunFormChoices choices) {
        super.applyChoices(choices);
        candidates = choices.mainClasses();
        String selected = RunFormUi.valueOf(mainClass);
        RunFormUi.fill(mainClass, choices.mainClassNames(), selected);
        mainClass.setSelectedItem(selected);
        if (springBoot && !choices.springProfiles().isEmpty()) {
            springProfiles.setPlaceholder(String.join(", ", choices.springProfiles()));
        }
    }

    @Override
    protected void collect(Map<String, Object> properties) {
        collectShared(properties);
        properties.put(JavaRunTypes.MAIN_CLASS, RunFormUi.valueOf(mainClass));
        properties.put(JavaRunTypes.PROGRAM_ARGUMENTS, programArguments.getText().trim());
        properties.put(JavaRunTypes.VM_OPTIONS, vmOptions.getText().trim());
        properties.put(JavaRunTypes.SPRING_PROFILES, springProfiles.getText().trim());
        properties.put(JavaRunTypes.SERVER_PORT, serverPort.getText().trim());
    }

    @Override
    protected void apply(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        applyShared(configuration);
        mainClass.setSelectedItem(value(properties, JavaRunTypes.MAIN_CLASS));
        programArguments.setText(value(properties, JavaRunTypes.PROGRAM_ARGUMENTS));
        vmOptions.setText(value(properties, JavaRunTypes.VM_OPTIONS));
        springProfiles.setText(value(properties, JavaRunTypes.SPRING_PROFILES));
        serverPort.setText(value(properties, JavaRunTypes.SERVER_PORT));
    }
}
