package dtm.ide.debug;

/**
 * Alvo de attach de uma sessao de debug Java.
 *
 * <p>Substitui o host, a porta e o timeout que antes eram fixos em
 * {@link JavaDebugSession}. {@code terminateOnDisconnect} distingue os dois casos: um
 * processo iniciado pela IDE deve morrer junto com a sessao, enquanto uma JVM remota apenas
 * perde o depurador e continua rodando.</p>
 */
public record JavaAttachTarget(String host, int port, int timeoutMillis,
                               boolean terminateOnDisconnect) {

    public static final String LOCALHOST = "127.0.0.1";
    public static final int DEFAULT_TIMEOUT = 30_000;

    public JavaAttachTarget {
        host = host == null || host.isBlank() ? LOCALHOST : host.trim();
        timeoutMillis = timeoutMillis <= 0 ? DEFAULT_TIMEOUT : timeoutMillis;
    }

    /** Processo iniciado pela propria IDE: encerra o debuggee ao desconectar. */
    public static JavaAttachTarget local(int port) {
        return new JavaAttachTarget(LOCALHOST, port, DEFAULT_TIMEOUT, true);
    }

    /** JVM remota: desconectar nunca encerra o processo do outro lado. */
    public static JavaAttachTarget remote(String host, int port, int timeoutMillis) {
        return new JavaAttachTarget(host, port, timeoutMillis, false);
    }
}
