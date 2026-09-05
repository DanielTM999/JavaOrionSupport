package dtm.ide.run;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;

@Slf4j
public final class DebugPorts {

    private static final int FALLBACK_PORT = 5005;

    private DebugPorts() {
    }

    public static int allocate() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            log.debug("Nao foi possivel alocar porta dinamica: {}", e.getMessage());
            return FALLBACK_PORT;
        }
    }
}
