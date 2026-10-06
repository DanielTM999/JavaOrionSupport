package dtm.ide.debug;

public record JavaAttachTarget(String host, int port, int timeoutMillis,
                               boolean terminateOnDisconnect) {

    public static final String LOCALHOST = "127.0.0.1";
    public static final int DEFAULT_TIMEOUT = 30_000;

    public JavaAttachTarget {
        host = host == null || host.isBlank() ? LOCALHOST : host.trim();
        timeoutMillis = timeoutMillis <= 0 ? DEFAULT_TIMEOUT : timeoutMillis;
    }

    public static JavaAttachTarget local(int port) {
        return new JavaAttachTarget(LOCALHOST, port, DEFAULT_TIMEOUT, true);
    }

    public static JavaAttachTarget remote(String host, int port, int timeoutMillis) {
        return new JavaAttachTarget(host, port, timeoutMillis, false);
    }
}
