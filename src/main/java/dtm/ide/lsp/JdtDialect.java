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
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
final class JdtDialect implements ServerDialect {
    private static final long SERVICE_READY_TIMEOUT_MS = 300_000;
    private static final long SERVICE_READY_POLL_MS = 250;
    private static final long SERVICE_READY_AFTER_PROJECTS_MS = 30_000;
    private final LspProgressAggregator progressAggregator;
    private final Consumer<LspProgressAggregator.Snapshot> publishProgress;
    private final Supplier<CountDownLatch> serviceReadyLatch;
    private volatile boolean debugBundleLoaded;
    private volatile boolean testBundleLoaded;

    JdtDialect(LspProgressAggregator progressAggregator,
               Consumer<LspProgressAggregator.Snapshot> publishProgress,
               Supplier<CountDownLatch> serviceReadyLatch) {
        this.progressAggregator = progressAggregator;
        this.publishProgress = publishProgress;
        this.serviceReadyLatch = serviceReadyLatch;
    }

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

    @Override
    public void onLanguageStatus(JsonNode params) {
        if (params == null) {
            return;
        }
        String message = params.path("message").asText("");
        if (isReadyStatus(params)) {
            serviceReadyLatch.get().countDown();
            return;
        }
        if (!message.isBlank()) {
            publishProgress.accept(progressAggregator.status(message));
        }
    }

    @Override
    public void onProgressReport(JsonNode params) {
        if (params == null) {
            return;
        }
        String token = "report:" + params.path("id").asText("");
        String task = params.path("task").asText("");
        String status = params.path("status").asText("");
        if (status.isBlank()) {
            status = params.path("subTask").asText("");
        }
        long total = params.path("totalWork").asLong(0);
        long done = params.path("workDone").asLong(0);
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
        publishProgress.accept(params.path("complete").asBoolean(false)
                ? progressAggregator.end(token)
                : progressAggregator.report(token, task, status, percent));
    }
}
