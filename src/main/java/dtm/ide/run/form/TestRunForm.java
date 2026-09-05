package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.test.TestScope;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.i18n.I18n;

import javax.swing.JComboBox;
import java.awt.event.ItemEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formulario da configuracao {@code java.test} (JUnit/TestNG).
 *
 * <p>O escopo define o que sera executado -- todos os testes do modulo, um pacote, uma
 * classe, um metodo ou um padrao livre -- e o alvo se adapta ao escopo escolhido.</p>
 */
public final class TestRunForm extends RunConfigurationFormBase {

    private static String text(String key, String fallback) {
        return I18n.getText(TestRunForm.class, key, fallback);
    }

    private final ValueChoice scope = new ValueChoice(RunFormUi.combo(false));
    private final MaskedTextField target = RunFormUi.text("com.exemplo.MinhaClasseTest");
    private final MaskedTextField runnerArguments = RunFormUi.text("-DfailIfNoTests=false");

    private FormFieldCell targetCell;

    public TestRunForm(RunFormContext context) {
        super(JavaRunTypes.TEST, context);
    }

    @Override
    protected void buildSections() {
        FormSection tests = section(text("section.tests", "Testes"),
                text("section.tests.hint",
                        "Escopo executado pelo Surefire ou pelo Gradle Test do projeto."));

        scope.setOptions(scopeOptions());
        tests.add(JavaRunTypes.TEST_SCOPE, field(JavaRunTypes.TEST_SCOPE,
                text("field.scope", "Escopo"), scope.combo()));
        tests.add(JavaRunTypes.MODULE, moduleCell());

        targetCell = field(JavaRunTypes.TEST_TARGET, text("field.target", "Alvo"), target);
        tests.addWide(JavaRunTypes.TEST_TARGET, targetCell);

        tests.addWide(JavaRunTypes.RUNNER_ARGUMENTS, field(JavaRunTypes.RUNNER_ARGUMENTS,
                        text("field.runnerArguments", "Argumentos adicionais"), runnerArguments)
                .helper(text("field.runnerArguments.hint",
                        "Repassados ao comando de teste do build tool.")));

        FormSection jvm = section(text("section.jvm", "JVM"),
                text("section.jvm.hint", "JDK usada para compilar e executar os testes."));
        jvm.add(JavaRunTypes.JDK_HOME, jdkCell());

        environmentSection();
        beforeLaunchSection();

        scope.combo().addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                updateTargetField();
                revalidateFields();
            }
        });
        revalidateOnEdit(target);
        updateTargetField();
    }

    private Map<String, String> scopeOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(text("scope.all", "Todos os testes do modulo"), TestScope.ALL.id());
        options.put(text("scope.package", "Pacote"), TestScope.PACKAGE.id());
        options.put(text("scope.class", "Classe"), TestScope.CLASS.id());
        options.put(text("scope.method", "Metodo"), TestScope.METHOD.id());
        options.put(text("scope.pattern", "Padrao"), TestScope.PATTERN.id());
        return options;
    }

    /** O alvo so faz sentido fora do escopo "todos"; o texto de ajuda segue o escopo. */
    private void updateTargetField() {
        TestScope selected = TestScope.parse(scope.value());
        boolean enabled = selected.requiresTarget();
        target.setEnabled(enabled);
        targetCell.helper(switch (selected) {
            case ALL -> text("field.target.all", "Nao se aplica ao escopo selecionado.");
            case PACKAGE -> text("field.target.package", "Exemplo: com.exemplo.servico");
            case CLASS -> text("field.target.class", "Exemplo: com.exemplo.MinhaClasseTest");
            case METHOD -> text("field.target.method",
                    "Exemplo: com.exemplo.MinhaClasseTest#deveSomar");
            case PATTERN -> text("field.target.pattern",
                    "Padrao repassado ao build tool, como *IntegrationTest");
        });
    }

    @Override
    protected void collect(Map<String, Object> properties) {
        collectShared(properties);
        properties.put(JavaRunTypes.TEST_SCOPE, scope.value());
        properties.put(JavaRunTypes.TEST_TARGET, target.getText().trim());
        properties.put(JavaRunTypes.RUNNER_ARGUMENTS, runnerArguments.getText().trim());
        properties.put(JavaRunTypes.USE_TEST_CLASSPATH, Boolean.TRUE.toString());
    }

    @Override
    protected void apply(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        applyShared(configuration);
        scope.select(TestScope.parse(value(properties, JavaRunTypes.TEST_SCOPE)).id());
        target.setText(value(properties, JavaRunTypes.TEST_TARGET));
        runnerArguments.setText(value(properties, JavaRunTypes.RUNNER_ARGUMENTS));
        updateTargetField();
    }
}
