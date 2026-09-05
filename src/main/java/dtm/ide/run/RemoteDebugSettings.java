package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.debug.JavaAttachTarget;

import java.util.Map;

/**
 * Leitura das propriedades de uma configuracao {@code java.remote}.
 *
 * <p>No modo Attach a Orion conecta em {@code host:porta}; no modo Listen o mesmo campo passa
 * a ser o endereco de bind local em que a Orion espera a JVM alvo.</p>
 */
public record RemoteDebugSettings(boolean listen, String host, int port, int timeoutMillis) {

    public RemoteDebugSettings {
        host = host == null || host.isBlank() ? JavaRunTypes.DEFAULT_REMOTE_HOST : host.trim();
        port = port <= 0 ? JavaRunTypes.DEFAULT_REMOTE_PORT : port;
        timeoutMillis = timeoutMillis < 0 ? JavaRunTypes.DEFAULT_REMOTE_TIMEOUT : timeoutMillis;
    }

    public static RemoteDebugSettings from(RunConfigurationData configuration) {
        Map<String, Object> properties = JavaRunSupport.propertiesOf(configuration);
        return new RemoteDebugSettings(
                JavaRunTypes.REMOTE_MODE_LISTEN.equalsIgnoreCase(
                        JavaRunValidation.value(properties, JavaRunTypes.REMOTE_MODE)),
                JavaRunValidation.value(properties, JavaRunTypes.REMOTE_HOST),
                number(properties, JavaRunTypes.REMOTE_PORT, JavaRunTypes.DEFAULT_REMOTE_PORT),
                number(properties, JavaRunTypes.REMOTE_TIMEOUT,
                        JavaRunTypes.DEFAULT_REMOTE_TIMEOUT));
    }

    /** Alvo de attach direto; no modo Listen o attach acontece contra a porta do relay. */
    public JavaAttachTarget attachTarget() {
        return JavaAttachTarget.remote(host, port, timeoutMillis);
    }

    /** Alvo de attach contra a porta loopback exposta pelo relay do modo Listen. */
    public JavaAttachTarget relayTarget(int relayPort) {
        return JavaAttachTarget.remote(JavaAttachTarget.LOCALHOST, relayPort, timeoutMillis);
    }

    private static int number(Map<String, Object> properties, String key, int fallback) {
        try {
            String raw = JavaRunValidation.value(properties, key);
            return raw.isBlank() ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
