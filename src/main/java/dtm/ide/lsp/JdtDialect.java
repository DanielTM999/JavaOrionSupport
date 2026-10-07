package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Slf4j
final class JdtDialect implements ServerDialect {
    private static final long SERVICE_READY_TIMEOUT_MS = 300_000;
    private static final long SERVICE_READY_POLL_MS = 250;
    private static final long SERVICE_READY_AFTER_PROJECTS_MS = 30_000;
    private volatile boolean debugBundleLoaded;
    private volatile boolean testBundleLoaded;

    @Override
    public Map<String, Object> initializationOptions(Map<String, Object> settings,
                                                      List<String> bundlePaths) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("settings", settings);
        options.put("extendedClientCapabilities", Map.of(
                "progressReportProvider", true,
                "classFileContentsSupport", true,
                "overrideMethodsPromptSupport", true,
                "advancedOrganizeImportsSupport", true,
                "advancedGenerateAccessorsSupport", true,
                "generateConstructorsPromptSupport", true,
                "generateToStringPromptSupport", true,
                "hashCodeEqualsPromptSupport", true,
                "generateDelegateMethodsPromptSupport", true));

        debugBundleLoaded = bundlePaths.stream().anyMatch(path ->
                path.contains("com.microsoft.java.debug.plugin"));
        testBundleLoaded = bundlePaths.stream().anyMatch(path ->
                path.contains("com.microsoft.java.test.plugin"));
        if (!bundlePaths.isEmpty()) {
            options.put("bundles", bundlePaths);
            log.info("Bundles carregados no jdtls: {}", bundlePaths.size());
        }
        return options;
    }

    boolean debugBundleLoaded() {
        return debugBundleLoaded;
    }

    boolean testBundleLoaded() {
        return testBundleLoaded;
    }

    @Override
    public void awaitWorkspaceReady(LspJsonRpcClient rpc, CountDownLatch ready,
                                    BooleanSupplier current) throws Exception {
        if (rpc == null) {
            throw new IllegalStateException("Cliente LSP indisponivel durante a indexacao");
        }
        CompletableFuture<JsonNode> projects = rpc.request("workspace/executeCommand", Map.of(
                "command", "java.project.getAll",
                "arguments", List.of()));
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(SERVICE_READY_TIMEOUT_MS);
        long projectsListedAt = 0L;
        try {
            while (System.nanoTime() < deadline) {
                if (ready.await(SERVICE_READY_POLL_MS, TimeUnit.MILLISECONDS)) {
                    projects.cancel(false);
                    return;
                }
                if (!current.getAsBoolean()) {
                    projects.cancel(false);
                    return;
                }
                if (projects.isDone()) {
                    if (projectsListedAt == 0L) {
                        projects.get();
                        projectsListedAt = System.nanoTime();
                    } else if (System.nanoTime() - projectsListedAt
                            >= TimeUnit.MILLISECONDS.toNanos(SERVICE_READY_AFTER_PROJECTS_MS)) {
                        log.info("JDT LS listou os projetos mas nao enviou ServiceReady em {} ms",
                                SERVICE_READY_AFTER_PROJECTS_MS);
                        return;
                    }
                }
            }
            projects.cancel(false);
            log.info("JDT LS ainda indexando apos {} ms; liberando o IntelliSense", SERVICE_READY_TIMEOUT_MS);
        } catch (Exception commandFailure) {
            if (ready.getCount() > 0) {
                throw commandFailure;
            }
        }
    }

    @Override
    public boolean isReadyStatus(JsonNode params) {
        return "ServiceReady".equalsIgnoreCase(params.path("type").asText(""));
    }
}
