package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

interface ServerDialect {
    Map<String, Object> initializationOptions(Map<String, Object> settings, List<String> bundlePaths);
    void awaitWorkspaceReady(LspJsonRpcClient rpc, CountDownLatch ready,
                             BooleanSupplier current) throws Exception;
    boolean isReadyStatus(JsonNode params);
    void onLanguageStatus(JsonNode params);
    void onProgressReport(JsonNode params);
}
