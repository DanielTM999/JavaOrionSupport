package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.lsp.api.DebugAdapterSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
final class JdtDebugAdapterCommands implements DebugAdapterSupport {
    private static final int DEBUG_MAX_STRING_LENGTH = 1_000;
    private static final long DEBUG_ADAPTER_TIMEOUT_MS = 60_000;

    interface Host {
        LspJsonRpcClient client();
        boolean isInteractive();
        boolean debugBundleLoaded();
    }

    private final LspRequests requests;
    private final Host host;
    private final Object debugAdapterLock = new Object();
    private volatile LspJsonRpcClient debugAdapterPreparedFor;

    JdtDebugAdapterCommands(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
    }

    public boolean isDebugAdapterAvailable() {
        return host.debugBundleLoaded() && host.isInteractive();
    }

    public boolean prepareDebugAdapter() {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || !isDebugAdapterAvailable()) {
            return false;
        }
        synchronized (debugAdapterLock) {
            if (debugAdapterPreparedFor == rpc) {
                return true;
            }
            JsonNode result = requests.requestInteractive("workspace/executeCommand", Map.of(
                    "command", "vscode.java.updateDebugSettings",
                    "arguments", List.of("{\"maxStringLength\":" + DEBUG_MAX_STRING_LENGTH
                            + ",\"logLevel\":\"WARNING\",\"showStaticVariables\":true}")),
                    DEBUG_ADAPTER_TIMEOUT_MS);
            if (result == null) {
                log.info("O adaptador de debug Java nao confirmou as configuracoes");
                return false;
            }
            debugAdapterPreparedFor = rpc;
            return true;
        }
    }

    public int startDebugSession() {
        prepareDebugAdapter();
        JsonNode result = requests.requestInteractive("workspace/executeCommand", Map.of(
                "command", "vscode.java.startDebugSession",
                "arguments", List.of()), DEBUG_ADAPTER_TIMEOUT_MS);
        if (result == null || !result.canConvertToInt()) {
            return -1;
        }
        return result.asInt(-1);
    }

}
