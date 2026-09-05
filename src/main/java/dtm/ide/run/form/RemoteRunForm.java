package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.run.JavaRunTypes;
import dtm.stools.component.inputfields.textarea.TextAreaField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.i18n.I18n;

import javax.swing.JComponent;
import java.awt.event.ItemEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formulario da configuracao {@code java.remote}.
 *
 * <p>No modo Attach a Orion conecta em uma JVM que ja esta escutando ({@code server=y}); no
 * modo Listen a Orion abre a porta e espera a JVM alvo conectar ({@code server=n}). O comando
 * JDWP correspondente e exibido pronto para copiar.</p>
 */
public final class RemoteRunForm extends RunConfigurationFormBase {

    private static String text(String key, String fallback) {
        return I18n.getText(RemoteRunForm.class, key, fallback);
    }

    private final ValueChoice mode = new ValueChoice(RunFormUi.combo(false));
    private final MaskedTextField host = RunFormUi.text(JavaRunTypes.DEFAULT_REMOTE_HOST);
    private final MaskedTextField port = RunFormUi.text(
            String.valueOf(JavaRunTypes.DEFAULT_REMOTE_PORT));
    private final MaskedTextField timeout = RunFormUi.text(
            String.valueOf(JavaRunTypes.DEFAULT_REMOTE_TIMEOUT));
    private final TextAreaField jdwpCommand = RunFormUi.textArea("", 2);

    private FormFieldCell hostCell;

    public RemoteRunForm(RunFormContext context) {
        super(JavaRunTypes.REMOTE, context);
    }

    @Override
    protected void buildSections() {
        FormSection remote = section(text("section.remote", "Conexao remota"),
                text("section.remote.hint",
                        "Depuracao de uma JVM ja em execucao, local ou em outra maquina."));

        mode.setOptions(modeOptions());
        remote.add(JavaRunTypes.REMOTE_MODE, field(JavaRunTypes.REMOTE_MODE,
                text("field.mode", "Modo"), mode.combo()));

        hostCell = field(JavaRunTypes.REMOTE_HOST, text("field.host", "Host"), host);
        remote.add(JavaRunTypes.REMOTE_HOST, hostCell);

        remote.add(JavaRunTypes.REMOTE_PORT, field(JavaRunTypes.REMOTE_PORT,
                        text("field.port", "Porta"), port)
                .helper(text("field.port.hint", "Porta JDWP. Padrao: 5005.")));
        remote.add(JavaRunTypes.REMOTE_TIMEOUT, field(JavaRunTypes.REMOTE_TIMEOUT,
                        text("field.timeout", "Timeout (ms)"), timeout)
                .helper(text("field.timeout.hint", "Tempo maximo de espera pela conexao.")));
        remote.add(JavaRunTypes.MODULE, moduleCell());

        FormSection target = section(text("section.target", "JVM alvo"),
                text("section.target.hint",
                        "Inicie a aplicacao a ser depurada com o argumento abaixo."));
        jdwpCommand.getTextArea().setEditable(false);
        target.addWide("jdwpCommand", RunFormUi.field(
                text("field.jdwpCommand", "Argumento JDWP"), jdwpCommand));
        target.addComponent(securityNotice());

        beforeLaunchSection();

        mode.combo().addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                updateModeFields();
                revalidateFields();
            }
        });
        revalidateOnEdit(host);
        revalidateOnEdit(port);
        revalidateOnEdit(timeout);
        updateModeFields();
    }

    private JComponent securityNotice() {
        return RunFormUi.notice(text("notice.security",
                "O JDWP nao possui autenticacao nem criptografia. Exponha a porta apenas em "
                        + "redes confiaveis e nunca diretamente na internet."));
    }

    private Map<String, String> modeOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(text("mode.attach", "Attach - conectar a uma JVM em execucao"),
                JavaRunTypes.REMOTE_MODE_ATTACH);
        options.put(text("mode.listen", "Listen - esperar a JVM conectar na Orion"),
                JavaRunTypes.REMOTE_MODE_LISTEN);
        return options;
    }

    private boolean isListen() {
        return JavaRunTypes.REMOTE_MODE_LISTEN.equals(mode.value());
    }

    private void updateModeFields() {
        boolean listen = isListen();
        hostCell.helper(listen
                ? text("field.host.listenHint",
                        "Endereco de escuta. 127.0.0.1 aceita apenas conexoes locais.")
                : text("field.host.attachHint", "Endereco da JVM que ja esta escutando."));
        updateJdwpCommand();
    }

    /** Mostra o {@code -agentlib:jdwp} exato que a JVM alvo precisa usar. */
    private void updateJdwpCommand() {
        boolean listen = isListen();
        String address = listen
                ? host.getText().trim() + ":" + port.getText().trim()
                : "*:" + port.getText().trim();
        jdwpCommand.setText("-agentlib:jdwp=transport=dt_socket,server="
                + (listen ? "n" : "y") + ",suspend=y,address=" + address);
    }

    @Override
    protected void revalidateFields() {
        updateJdwpCommand();
        super.revalidateFields();
    }

    @Override
    protected void collect(Map<String, Object> properties) {
        properties.put(JavaRunTypes.MODULE, selectedModuleName());
        properties.put(JavaRunTypes.REMOTE_MODE, mode.value());
        properties.put(JavaRunTypes.REMOTE_HOST, host.getText().trim());
        properties.put(JavaRunTypes.REMOTE_PORT, port.getText().trim());
        properties.put(JavaRunTypes.REMOTE_TIMEOUT, timeout.getText().trim());
    }

    @Override
    protected void apply(RunConfigurationData configuration) {
        Map<String, Object> properties = propertiesOf(configuration);
        moduleControl().setSelectedItem(value(properties, JavaRunTypes.MODULE));
        mode.select(defaulted(value(properties, JavaRunTypes.REMOTE_MODE),
                JavaRunTypes.REMOTE_MODE_ATTACH));
        host.setText(defaulted(value(properties, JavaRunTypes.REMOTE_HOST),
                JavaRunTypes.DEFAULT_REMOTE_HOST));
        port.setText(defaulted(value(properties, JavaRunTypes.REMOTE_PORT),
                String.valueOf(JavaRunTypes.DEFAULT_REMOTE_PORT)));
        timeout.setText(defaulted(value(properties, JavaRunTypes.REMOTE_TIMEOUT),
                String.valueOf(JavaRunTypes.DEFAULT_REMOTE_TIMEOUT)));
        updateModeFields();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
