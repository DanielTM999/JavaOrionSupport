package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.inspection.DiagnosticRanges;
import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.lsp.api.DebugAdapterSupport;
import dtm.ide.lsp.api.ImportCandidateSupport;
import dtm.ide.lsp.api.ImportLookup;
import dtm.ide.lsp.api.JavaAgentSupport;
import dtm.ide.lsp.api.JavaCodeLens;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.lsp.api.LateCompletionListener;
import dtm.ide.lsp.api.PrepareRenameResult;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.lsp.api.ResolvedCodeAction;
import dtm.ide.lsp.api.SourceGenerationSupport;
import dtm.ide.lsp.api.StatusListener;
import dtm.ide.lsp.api.TestDiscoverySupport;
import dtm.ide.lsp.api.TypeMoveSupport;
import dtm.ide.lsp.api.TypeSymbol;
import dtm.ide.lsp.api.WorkListener;
import dtm.ide.inspection.JavaDiagnosticEdits;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.JdkVendor;
import dtm.ide.sdk.SdkDownloader;
import dtm.ide.test.JavaTest;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static dtm.ide.lsp.LspRequests.INDEXING_INTERACTIVE_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.INTERACTIVE_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.REQUEST_TIMEOUT_MS;
import static dtm.ide.lsp.LspRequests.documentId;
import static dtm.ide.lsp.LspRequests.positionParams;
import static dtm.ide.lsp.LspRequests.rangeParam;
import static dtm.ide.lsp.LspRequests.rootCause;
import static dtm.ide.lsp.LspRequests.rootMessage;
import static dtm.ide.lsp.LspText.offsetIn;

@Slf4j
public class JdtLsService implements JavaLanguageServer, SourceGenerationSupport, TypeMoveSupport,
        ClassFileSupport, ProjectModelSupport, DebugAdapterSupport, TestDiscoverySupport, ImportCandidateSupport,
        JavaAgentSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    enum ResyncMode {
        REOPEN,
        TOUCH
    }

    private static final long IMPORT_CANDIDATES_TIMEOUT_MS = 5_000;

    private static final long INITIALIZE_CEILING_MS = 900_000;
    private static final long INITIALIZE_WAIT_SLICE_MS = 5_000;
    private static final long SERVICE_READY_TIMEOUT_MS = 300_000;
    private static final long SERVICE_READY_POLL_MS = 250;
    private static final int MIN_AUTO_SHARED_ARCHIVE_MAJOR = 19;
    private static final long SERVICE_READY_AFTER_PROJECTS_MS = 30_000;
    private static final long SHUTDOWN_TIMEOUT_MS = 10_000;
    private static final long EXIT_TIMEOUT_MS = 5_000;
    private static final long UNLOAD_SHUTDOWN_TIMEOUT_MS = 500;
    private static final long UNLOAD_EXIT_TIMEOUT_MS = 500;
    private static final long UNLOAD_RETIRE_WAIT_MS = 3_000;
    private static final long EXIT_HOOK_SHUTDOWN_TIMEOUT_MS = 1_000;
    private static final long EXIT_HOOK_TOTAL_TIMEOUT_MS = 4_000;
    private static final long RETIRE_WAIT_MS = 20_000;
    private static final long[] CRASH_RESTART_DELAYS_MS = {1_000, 5_000, 30_000};
    private static final long CRASH_RESTART_WINDOW_MS = 600_000;
    private static final Set<JdtLsService> LIVE_SERVICES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final long DOCUMENT_RECOVERY_COOLDOWN_MS = 5_000;
    private static final long EXTERNAL_RESYNC_DELAY_MS = 350;
    private static final long EXTERNAL_RESYNC_MAX_DELAY_MS = 2_000;
    private static final long PROJECT_CONFIGURATION_COOLDOWN_MS = 2_000;
    private static final int MAX_PENDING_WATCHED_FILES = 50_000;
    private static final int WATCHED_FILES_PER_NOTIFICATION = 512;
    private static final int MAX_REOPEN_DOCUMENTS = 30;
    private static final long WARM_UP_TIMEOUT_MS = 8_000;

    private final JdkService jdkService;
    private final JdtLsProvisioner provisioner;
    private final JdtLsExtensionBundles bundles;
    private final Consumer<Path> onDiagnosticsPublished;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("jdtls-", 0).factory());

    private final JdtDocumentStore documents = new JdtDocumentStore();
    private final LspDiagnosticsStore diagnosticsStore;
    private final Set<String> workspaceWorkTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> openedDuringImport = ConcurrentHashMap.newKeySet();
    private final AtomicLong workspaceRevision = new AtomicLong();
    private final AtomicLong lastDocumentRecovery = new AtomicLong();
    private final AtomicLong lastProjectConfigurationUpdate = new AtomicLong();
    private final AtomicLong watchedFlushTicket = new AtomicLong();
    private final AtomicLong resyncTicket = new AtomicLong();
    private final WatchedFileBatch watchedFiles = new WatchedFileBatch(
            MAX_PENDING_WATCHED_FILES,
            TimeUnit.MILLISECONDS.toNanos(EXTERNAL_RESYNC_MAX_DELAY_MS));
    private volatile long externalResyncDelayMs = EXTERNAL_RESYNC_DELAY_MS;
    private final Object processLock = new Object();

    private volatile LanguageServerState state = LanguageServerState.NOT_STARTED;
    private volatile String lastError;
    private volatile String startupFailure;
    private volatile Path projectRoot;
    private volatile Path launchedMavenRepository;
    private volatile Process process;
    private volatile LspJsonRpcClient client;
    private volatile JdtLsWorkspaceLease workspaceLease;
    private volatile CompletableFuture<Void> retiring = CompletableFuture.completedFuture(null);
    private final Set<ProcessHandle> retiringProcesses = ConcurrentHashMap.newKeySet();
    private volatile boolean terminated;
    private volatile LaunchRequest lastLaunch;
    private final AtomicLong generation = new AtomicLong();
    private final List<Long> crashRestarts = new ArrayList<>();
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile CountDownLatch serviceReadyLatch = new CountDownLatch(1);
    private volatile ServerCapabilities capabilities = ServerCapabilities.none();
    private volatile StatusListener statusListener = (message, percent) -> {
    };
    private volatile StatusListener workspaceBuildProgress;
    private volatile WorkListener workListener = (message, percent, active) -> {
    };
    private final LspProgressAggregator progressAggregator = new LspProgressAggregator();
    private volatile String maxHeap = "2G";
    private volatile dtm.ide.settings.InlayHintsMode inlayHintsMode =
            dtm.ide.settings.InlayHintsMode.LITERALS;
    private volatile dtm.ide.settings.JdtBuildMode buildMode =
            dtm.ide.settings.JdtBuildMode.PROJECT_BUILD;
    private volatile Path lombokAgentJar;
    private volatile Path launchedLombokAgentJar;
    private volatile boolean springSupport;
    private volatile boolean debugBundleLoaded;
    private volatile boolean testBundleLoaded;
    private volatile Map<String, Object> effectiveSettings = Map.of();
    private volatile Consumer<Path> onCodeLensRefresh = path -> {
    };
    private volatile Consumer<Path> onDocumentUpgrade = path -> {
    };
    private volatile Runnable onWarmUpComplete = () -> {
    };
    private volatile LateCompletionListener lateCompletionListener = (path, line, col) -> {
    };
    private final AtomicBoolean warmingUp = new AtomicBoolean();
    private final AtomicBoolean warmUpWorkStarted = new AtomicBoolean();
    private volatile long warmUpDeadline;

    private final LspRequests requests = new LspRequests(new LspRequests.Host() {
        @Override
        public LspJsonRpcClient client() {
            return client;
        }

        @Override
        public boolean isInteractive() {
            return JdtLsService.this.isInteractive();
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public boolean isWarmingUp() {
            return JdtLsService.this.isWarmingUp();
        }

        @Override
        public boolean syncBeforeRequest(Path filePath, String text) {
            return JdtLsService.this.syncBeforeRequest(filePath, text);
        }
    });

    private final LspDecorations decorations = new LspDecorations(requests, executor, new LspDecorations.Host() {
        @Override
        public ServerCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public boolean isWarmingUp() {
            return JdtLsService.this.isWarmingUp();
        }

        @Override
        public boolean syncBeforeRequest(Path filePath, String text) {
            return JdtLsService.this.syncBeforeRequest(filePath, text);
        }

        @Override
        public boolean isCurrentText(Path filePath, String text) {
            return JdtLsService.this.isCurrentText(filePath, text);
        }

        @Override
        public long workspaceRevision() {
            return workspaceRevision.get();
        }

        @Override
        public JdtDocumentStore documents() {
            return documents;
        }

        @Override
        public LspJsonRpcClient client() {
            return client;
        }

        @Override
        public void codeLensRefreshed(Path path) {
            onCodeLensRefresh.accept(path);
        }

        @Override
        public JavaCodeLens unresolvedLens(JsonNode node, Status status) {
            return JdtLsService.unresolvedLens(node, status);
        }
    });

    private final LspNavigation navigation = new LspNavigation(requests, new LspNavigation.Host() {
        @Override
        public ServerCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public boolean isInteractive() {
            return JdtLsService.this.isInteractive();
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public boolean isWarmingUp() {
            return JdtLsService.this.isWarmingUp();
        }

        @Override
        public boolean hasWorkspaceWork() {
            return !workspaceWorkTokens.isEmpty();
        }

        @Override
        public boolean syncBeforeRequest(Path filePath, String text, boolean authoritative) {
            return JdtLsService.this.syncBeforeRequest(filePath, text, authoritative);
        }

        @Override
        public boolean isCurrentText(Path filePath, String text) {
            return JdtLsService.this.isCurrentText(filePath, text);
        }

        @Override
        public long workspaceRevision() {
            return workspaceRevision.get();
        }

        @Override
        public JdtDocumentStore documents() {
            return documents;
        }
    });

    private final LspCompletion completion = new LspCompletion(requests, executor, new LspCompletion.Host() {
        @Override
        public ServerCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public boolean isInteractive() {
            return JdtLsService.this.isInteractive();
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public boolean syncBeforeRequest(Path filePath, String text) {
            return JdtLsService.this.syncBeforeRequest(filePath, text);
        }

        @Override
        public JdtDocumentStore documents() {
            return documents;
        }

        @Override
        public LspJsonRpcClient client() {
            return client;
        }

        @Override
        public void lateCompletion(Path filePath, int line, int col) {
            lateCompletionListener.onLateCompletion(filePath, line, col);
        }
    });

    private final LspRefactoring refactoring = new LspRefactoring(requests, new LspRefactoring.Host() {
        @Override
        public LspJsonRpcClient client() {
            return client;
        }

        @Override
        public ServerCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public boolean isInteractive() {
            return JdtLsService.this.isInteractive();
        }

        @Override
        public boolean syncBeforeRequest(Path path, String text) {
            return JdtLsService.this.syncBeforeRequest(path, text);
        }

        @Override
        public boolean isCurrentText(Path path, String text) {
            return JdtLsService.this.isCurrentText(path, text);
        }

        @Override
        public void drainPendingWatchedFiles() {
            JdtLsService.this.drainPendingWatchedFiles();
        }

        @Override
        public JdtDocumentStore documents() {
            return documents;
        }

        @Override
        public List<JsonNode> rawDiagnostics(Path path) {
            return diagnosticsStore.raw(path);
        }

        @Override
        public String applyCodeActionCommand() {
            return APPLY_CODE_ACTION_COMMAND;
        }
    });

    private final JdtSourceGeneration sourceGeneration = new JdtSourceGeneration(requests,
            (path, text) -> syncBeforeRequest(path, text));

    private final JdtMove move = new JdtMove(requests, new JdtMove.Host() {
        @Override
        public LspJsonRpcClient client() {
            return client;
        }

        @Override
        public boolean isReady() {
            return JdtLsService.this.isReady();
        }

        @Override
        public void drainPendingWatchedFiles() {
            JdtLsService.this.drainPendingWatchedFiles();
        }
    });

    private final JdtProjectCommands projectCommands = new JdtProjectCommands(requests,
            new JdtProjectCommands.Host() {
                @Override
                public LspJsonRpcClient client() {
                    return client;
                }

                @Override
                public boolean isInteractive() {
                    return JdtLsService.this.isInteractive();
                }

                @Override
                public Path launchedMavenRepository() {
                    return launchedMavenRepository;
                }

                @Override
                public Path resolveMavenRepository() {
                    return JdtLsService.this.resolveMavenRepository();
                }

                @Override
                public Map<String, Object> effectiveSettings() {
                    return effectiveSettings;
                }

                @Override
                public void effectiveSettings(Map<String, Object> settings) {
                    effectiveSettings = settings;
                }

                @Override
                public void clearNavigationCache() {
                    JdtLsService.this.clearNavigationCache(null);
                }

                @Override
                public void resynchronizeAfterProjectUpdate() {
                    resynchronizeOpenDocuments(resyncMode(), false,
                            "Java: documentos sincronizados com o projeto");
                }

                @Override
                public void workspaceBuildProgress(StatusListener progress) {
                    workspaceBuildProgress = progress;
                }
            });

    private final JdtDebugAdapterCommands debugCommands = new JdtDebugAdapterCommands(requests,
            new JdtDebugAdapterCommands.Host() {
                @Override
                public LspJsonRpcClient client() {
                    return client;
                }

                @Override
                public boolean isInteractive() {
                    return JdtLsService.this.isInteractive();
                }

                @Override
                public boolean debugBundleLoaded() {
                    return debugBundleLoaded;
                }
            });

    public JdtLsService(JdkService jdkService, JdtLsProvisioner provisioner,
                        JdtLsExtensionBundles bundles, Consumer<Path> onDiagnosticsPublished) {
        this.jdkService = jdkService;
        this.provisioner = provisioner;
        this.bundles = bundles;
        this.onDiagnosticsPublished = onDiagnosticsPublished == null ? path -> {
        } : onDiagnosticsPublished;
        this.diagnosticsStore = new LspDiagnosticsStore(documents, this.onDiagnosticsPublished,
                executor, this, new LspDiagnosticsStore.Host() {
                    @Override
                    public boolean isReady() {
                        return state == LanguageServerState.READY;
                    }

                    @Override
                    public boolean progressIdle() {
                        return progressAggregator.snapshot().idle();
                    }

                    @Override
                    public void resynchronizeAfterSettle() {
                        resynchronizeOpenDocuments(resyncMode(), false, null);
                    }
                });
        LIVE_SERVICES.add(this);
        installShutdownHook();
    }

    private record LaunchRequest(Path root, JdkInstallation jdk, DownloadProgressListener progress) {
    }

    @Override
    public <T> T extension(Class<T> type) {
        return type.isInstance(this) ? type.cast(this) : null;
    }

    public LanguageServerState getState() {
        return state;
    }

    public boolean isRunning() {
        return isReady();
    }

    public boolean isWorkspaceSettled() {
        return isReady() && !isWarmingUp() && workspaceWorkTokens.isEmpty();
    }

    public boolean isInteractive() {
        LanguageServerState current = state;
        return isInteractiveState(current)
                && client != null && !client.isClosed();
    }

    static boolean isInteractiveState(LanguageServerState state) {
        return state == LanguageServerState.INDEXING || state == LanguageServerState.READY;
    }

    public boolean isReady() {
        return state == LanguageServerState.READY && client != null && !client.isClosed();
    }

    public String getLastError() {
        return lastError;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public Set<Character> completionTriggers() {
        return capabilities.completionTriggers();
    }

    public boolean setLombokAgentJar(Path jar) {
        Path previous = lombokAgentJar;
        if (Objects.equals(previous, jar)) {
            return false;
        }
        lombokAgentJar = jar;
        return true;
    }

    public boolean needsRestartForLombokAgent() {
        Process running = process;
        return running != null && running.isAlive()
                && !Objects.equals(launchedLombokAgentJar, lombokAgentJar);
    }

    public void setMaxHeap(String value) {
        this.maxHeap = value == null || value.isBlank() ? "2G" : value.trim();
    }

    public void setInlayHintsMode(dtm.ide.settings.InlayHintsMode value) {
        dtm.ide.settings.InlayHintsMode mode = value == null ? dtm.ide.settings.InlayHintsMode.LITERALS : value;
        if (mode == inlayHintsMode) {
            return;
        }
        inlayHintsMode = mode;
        Map<String, Object> updated = JdtLsSettings.withInlayHints(effectiveSettings, mode);
        effectiveSettings = updated;
        LspJsonRpcClient rpc = client;
        if (rpc != null && !rpc.isClosed() && isInteractive()) {
            rpc.notify("workspace/didChangeConfiguration", Map.of("settings", updated));
        }
    }

    public void setBuildMode(dtm.ide.settings.JdtBuildMode value) {
        this.buildMode = value == null ? dtm.ide.settings.JdtBuildMode.PROJECT_BUILD : value;
    }

    public void setSpringSupport(boolean enabled) {
        this.springSupport = enabled;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener == null ? (message, percent) -> {
        } : listener;
    }

    public void setWorkListener(WorkListener listener) {
        this.workListener = listener == null ? (message, percent, active) -> {
        } : listener;
    }

    public void setCodeLensRefreshListener(Consumer<Path> listener) {
        this.onCodeLensRefresh = listener == null ? path -> {
        } : listener;
    }

    public void setDocumentUpgradeListener(Consumer<Path> listener) {
        this.onDocumentUpgrade = listener == null ? path -> {
        } : listener;
    }

    public void setLateCompletionListener(LateCompletionListener listener) {
        this.lateCompletionListener = listener == null ? (path, line, col) -> {
        } : listener;
    }

    public void setWarmUpCompleteListener(Runnable listener) {
        this.onWarmUpComplete = listener == null ? () -> {
        } : listener;
    }

    public boolean isWarmingUp() {
        if (!warmingUp.get()) {
            return false;
        }
        if (System.nanoTime() - warmUpDeadline >= 0) {
            finishWarmUp();
            return false;
        }
        return true;
    }

    private void finishWarmUp() {
        if (!warmingUp.compareAndSet(true, false)) {
            return;
        }
        executor.submit(() -> {
            try {
                invalidateWorkspaceNavigation();
                onWarmUpComplete.run();
            } catch (Exception e) {
                log.debug("Falha ao concluir o aquecimento do JDT LS: {}", rootMessage(e));
            }
        });
    }

    public CompletableFuture<Void> start(Path root, JdkInstallation jdk,
                                         DownloadProgressListener progress) {
        if (root == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("projeto nulo"));
        }
        return CompletableFuture.runAsync(() -> launch(root, jdk, progress), executor);
    }

    private void launch(Path root, JdkInstallation jdk, DownloadProgressListener progress) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        boolean replace;
        synchronized (processLock) {
            if (terminated) {
                return;
            }
            boolean active = state == LanguageServerState.STARTING || state == LanguageServerState.INDEXING || isRunning();
            if (active && normalizedRoot.equals(projectRoot)) {
                return;
            }
            replace = active;
        }
        if (replace) {
            stopAsync();
        }
        long launchGeneration;
        synchronized (processLock) {
            if (terminated || state == LanguageServerState.STARTING || state == LanguageServerState.INDEXING || isRunning()) {
                return;
            }
            launchGeneration = generation.incrementAndGet();
            state = LanguageServerState.STARTING;
            lastError = null;
            startupFailure = null;
            projectRoot = normalizedRoot;
            lastLaunch = new LaunchRequest(normalizedRoot, jdk, progress);
            readyLatch = new CountDownLatch(1);
            serviceReadyLatch = new CountDownLatch(1);
            progressAggregator.reset();
            diagnosticsStore.beginImport();
        }

        long launchStarted = System.nanoTime();
        JdtLsWorkspaceLease lease = null;
        Process started = null;
        try {
            JdkInstallation runtime = resolveServerJdk(jdk);
            JdtLsProvisioner.JdtLsInstallation installation = provisioner.ensure(progress);
            if (bundles != null) {
                try {
                    bundles.ensureJavaDebugPlugin(progress);
                } catch (Exception error) {
                    log.warn("Depurador Java indisponivel: {}", rootMessage(error));
                }
            }
            awaitRetiredServers();
            if (!isCurrent(launchGeneration)) {
                return;
            }
            removeLegacyOverlappingWorkspace(root);
            lease = JdtLsWorkspaceLease.acquire(provisioner.workspaceFor(root));
            Path workspace = lease.workspace();
            stopOrphanedWorkspaceServers(lease);

            List<String> command = buildCommand(runtime, installation, workspace);
            log.info("Iniciando o Eclipse JDT LS {} com JDK do servidor {} e JDK do projeto {} em {} (workspace {})",
                    JdtLsProvisioner.DEFAULT_VERSION, runtime.fullVersion(),
                    jdk == null ? "padrao" : jdk.fullVersion(), root, workspace.getFileName());

            CompletableFuture<List<String>> bundlePaths =
                    CompletableFuture.supplyAsync(this::resolveBundlePaths, executor);
            if (!isCurrent(launchGeneration)) {
                return;
            }
            long processStarted = System.nanoTime();
            started = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            LspJsonRpcClient rpc;
            synchronized (processLock) {
                if (!isCurrent(launchGeneration)) {
                    return;
                }
                rpc = new LspJsonRpcClient(started.getInputStream(), started.getOutputStream(),
                        "jdtls-rpc");
                process = started;
                client = rpc;
                workspaceLease = lease;
            }
            Process owned = started;
            lease.recordServer(owned.toHandle());
            lease = null;
            started = null;
            watchServerExit(owned, launchGeneration);
            rpc.onUnexpectedDisconnect(() -> onProtocolLost(owned, launchGeneration));
            pumpStderr(owned);
            registerHandlers(rpc);

            initialize(rpc, owned, launchGeneration, root, runtime, jdk, bundlePaths.join());
            long initialized = System.nanoTime();
            if (!advanceState(launchGeneration, LanguageServerState.INDEXING)) {
                return;
            }
            publishProgress(progressAggregator.initialized("indexando projeto..."));
            flushOpenDocuments();
            drainPendingWatchedFiles();
            provisionBundlesInBackground(progress);
            awaitWorkspaceReady(rpc, launchGeneration);
            if (startupFailure != null) {
                throw new IllegalStateException(startupFailure);
            }
            if (!advanceState(launchGeneration, LanguageServerState.READY)) {
                return;
            }
            warmUpDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_UP_TIMEOUT_MS);
            warmUpWorkStarted.set(false);
            warmingUp.set(true);
            CompletableFuture.runAsync(this::finishWarmUp, CompletableFuture.delayedExecutor(
                    WARM_UP_TIMEOUT_MS, TimeUnit.MILLISECONDS, executor));
            flushOpenDocuments();
            reopenDocumentsOpenedDuringImport();
            refreshOpenDocuments();
            drainPendingWatchedFiles();
            progressAggregator.restartBackgroundWork();
            diagnosticsStore.startSettleTimer();
            readyLatch.countDown();
            long ready = System.nanoTime();
            log.info("JDT LS pronto em {} ms (preparo {} ms, processo+initialize {} ms, importacao {} ms)",
                    elapsedMs(launchStarted, ready), elapsedMs(launchStarted, processStarted),
                    elapsedMs(processStarted, initialized), elapsedMs(initialized, ready));
            statusListener.onStatus("Java: IntelliSense pronto", -1);
        } catch (Exception e) {
            if (!isCurrent(launchGeneration)) {
                readyLatch.countDown();
                return;
            }
            String message = rootMessage(e);
            Process failed = process;
            if (failed != null && !failed.isAlive()) {
                message = "o processo do JDT LS encerrou com codigo " + failed.exitValue()
                        + " (" + message + ")";
            }
            log.warn("Falha ao iniciar o Eclipse JDT LS: {}", message, e);
            finishRetired(retire(), 0, 0);
            state = LanguageServerState.ERROR;
            lastError = message;
            if (!(rootCause(e) instanceof TimeoutException)
                    || !scheduleRestart("Java: o JDT LS nao respondeu; tentando de novo...")) {
                statusListener.onStatus("Java: IntelliSense indisponivel - " + message, -1);
            }
        } finally {
            if (started != null) {
                terminateProcessTree(started.toHandle());
            }
            if (lease != null) {
                lease.close();
            }
        }
    }

    private boolean isCurrent(long launchGeneration) {
        return generation.get() == launchGeneration && !terminated;
    }

    private boolean advanceState(long launchGeneration, LanguageServerState next) {
        synchronized (processLock) {
            if (!isCurrent(launchGeneration)) {
                return false;
            }
            state = next;
            return true;
        }
    }

    private void awaitRetiredServers() {
        try {
            retiring.get(RETIRE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Servidor JDT LS anterior ainda encerrando: {}", rootMessage(e));
        }
    }

    private void watchServerExit(Process server, long launchGeneration) {
        server.onExit().thenRun(() -> onServerExit(server, launchGeneration));
    }

    private void onServerExit(Process server, long launchGeneration) {
        if (!isCurrent(launchGeneration)) {
            return;
        }
        LanguageServerState before = state;
        int code = server.exitValue();
        log.warn("O processo do JDT LS encerrou inesperadamente com codigo {} (estado {})", code, before);
        finishRetired(retire(), 0, 0);
        state = LanguageServerState.ERROR;
        lastError = "o processo do JDT LS encerrou com codigo " + code;
        boolean restartable = before == LanguageServerState.READY || before == LanguageServerState.STARTING || before == LanguageServerState.INDEXING;
        if (!restartable || !scheduleRestart(
                "Java: o IntelliSense encerrou (codigo " + code + "); reiniciando...")) {
            statusListener.onStatus("Java: IntelliSense indisponivel - " + lastError, -1);
        }
    }

    private void onProtocolLost(Process server, long launchGeneration) {
        if (!isCurrent(launchGeneration) || !server.isAlive()) {
            return;
        }
        log.warn("Conexao com o JDT LS perdida com o processo ainda vivo; encerrando para reiniciar");
        server.destroyForcibly();
    }

    private boolean scheduleRestart(String status) {
        LaunchRequest again = lastLaunch;
        if (again == null || terminated) {
            return false;
        }
        long delay;
        synchronized (crashRestarts) {
            long now = System.currentTimeMillis();
            crashRestarts.removeIf(time -> now - time > CRASH_RESTART_WINDOW_MS);
            if (crashRestarts.size() >= CRASH_RESTART_DELAYS_MS.length) {
                log.warn("JDT LS reiniciado {} vezes em {} min; desistindo ate um reinicio manual",
                        crashRestarts.size(), CRASH_RESTART_WINDOW_MS / 60_000);
                return false;
            }
            delay = CRASH_RESTART_DELAYS_MS[crashRestarts.size()];
            crashRestarts.add(now);
        }
        statusListener.onStatus(status, -1);
        try {
            CompletableFuture.runAsync(() -> launch(again.root(), again.jdk(), again.progress()),
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        }
    }

    public void resetCrashHistory() {
        synchronized (crashRestarts) {
            crashRestarts.clear();
        }
    }

    static JsonNode awaitWhileAlive(CompletableFuture<JsonNode> response, BooleanSupplier keepWaiting,
                                    long sliceMs, long ceilingMs, LongConsumer onWaiting) throws Exception {
        long started = System.nanoTime();
        while (true) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long remaining = ceilingMs - elapsed;
            if (remaining <= 0) {
                response.cancel(false);
                throw new TimeoutException("o JDT LS nao respondeu em " + ceilingMs + " ms");
            }
            try {
                return response.get(Math.min(sliceMs, remaining), TimeUnit.MILLISECONDS);
            } catch (TimeoutException slice) {
                if (!keepWaiting.getAsBoolean()) {
                    response.cancel(false);
                    throw new IllegalStateException("o processo do JDT LS encerrou durante a inicializacao");
                }
                onWaiting.accept(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    private void awaitWorkspaceReady(LspJsonRpcClient rpc, long launchGeneration) throws Exception {
        if (rpc == null) {
            throw new IllegalStateException("Cliente LSP indisponivel durante a indexacao");
        }
        CountDownLatch ready = serviceReadyLatch;
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
                if (!isCurrent(launchGeneration)) {
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

    static void removeLegacyOverlappingWorkspace(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path legacyRoot = normalizedRoot.resolve(".orion").resolve("jdtls").normalize();
        Path legacyWorkspace = legacyRoot.resolve("workspace");
        if (legacyRoot.startsWith(normalizedRoot.resolve(".orion"))
                && Files.isDirectory(legacyWorkspace.resolve(".metadata"))) {
            SdkDownloader.deleteRecursively(legacyRoot);
        }
    }

    private void stopOrphanedWorkspaceServers(JdtLsWorkspaceLease lease) {
        Path workspace = lease.workspace();
        lease.recordedServer().ifPresent(handle -> {
            log.warn("Encerrando JDT LS orfao registrado pid={} do workspace {}", handle.pid(), workspace);
            terminateProcessTree(handle);
        });
        for (ProcessHandle handle : lease.serversHoldingMetadata()) {
            log.warn("Encerrando JDT LS orfao pid={} que segurava o workspace {}", handle.pid(), workspace);
            terminateProcessTree(handle);
        }
        try (var processes = ProcessHandle.allProcesses()) {
            processes.filter(ProcessHandle::isAlive)
                    .filter(handle -> handle.pid() != ProcessHandle.current().pid())
                    .filter(handle -> handle.parent().map(ProcessHandle::isAlive).orElse(false) == false)
                    .filter(handle -> isJdtLsForWorkspace(
                            handle.info().command().orElse(""),
                            handle.info().arguments().orElseGet(() -> new String[0]), workspace))
                    .forEach(handle -> {
                        log.warn("Encerrando JDT LS orfao pid={} do workspace {}",
                                handle.pid(), workspace);
                        terminateProcessTree(handle);
                    });
        } catch (Exception e) {
            log.debug("Nao foi possivel procurar JDT LS orfao em {}: {}",
                    workspace, e.getMessage());
        }
    }

    static boolean isJdtLsForWorkspace(String command, String[] arguments, Path workspace) {
        if (workspace == null || arguments == null || command == null
                || !command.toLowerCase(java.util.Locale.ROOT).contains("java")) {
            return false;
        }
        boolean launcher = false;
        boolean sameWorkspace = false;
        String expected = workspace.toAbsolutePath().normalize().toString();
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i] == null ? "" : arguments[i];
            if (argument.toLowerCase(java.util.Locale.ROOT)
                    .contains("org.eclipse.equinox.launcher")) {
                launcher = true;
            }
            if ("-data".equals(argument) && i + 1 < arguments.length) {
                try {
                    String candidate = Path.of(arguments[i + 1]).toAbsolutePath()
                            .normalize().toString();
                    sameWorkspace = expected.equalsIgnoreCase(candidate);
                } catch (Exception ignored) {
                    sameWorkspace = expected.equalsIgnoreCase(arguments[i + 1]);
                }
            }
        }
        return launcher && sameWorkspace;
    }

    private JdkInstallation resolveServerJdk(JdkInstallation preferred) {
        if (preferred != null && preferred.major() >= JdkService.LANGUAGE_SERVER_MIN_MAJOR
                && preferred.isJdk()) {
            return preferred;
        }
        return jdkService.languageServerJdk().orElseThrow(() -> new IllegalStateException(
                "O IntelliSense Java precisa de uma JDK "
                        + JdkService.LANGUAGE_SERVER_MIN_MAJOR + " ou mais nova."));
    }

    private void provisionBundlesInBackground(DownloadProgressListener progress) {
        if (bundles == null) {
            return;
        }
        executor.submit(() -> provisionBundles(progress));
    }

    private void provisionBundles(DownloadProgressListener progress) {
        try {
            bundles.ensureJavaTestPlugin(progress);
        } catch (Exception e) {
            log.warn("Test Runner Java indisponivel: {}", rootMessage(e));
        }
        if (!springSupport) {
            return;
        }
        try {
            bundles.ensureSpringTools(progress);
        } catch (Exception e) {
            log.warn("Extensao Spring indisponivel: {}", rootMessage(e));
        }
    }

    private Path resolveMavenRepository() {
        if (projectRoot == null) return null;
        var project = dtm.ide.project.JavaProjectConventions.describe(projectRoot);
        return project == null || !project.isMaven() ? null
                : new dtm.ide.deps.MavenLocalRepositoryResolver().resolve(project, null).repository();
    }

    List<String> buildCommand(JdkInstallation runtime,
                              JdtLsProvisioner.JdtLsInstallation installation,
                              Path workspace) {
        List<String> command = new ArrayList<>();
        command.add(runtime.javaExecutable().toString());
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=WARNING");
        command.add("-Dfile.encoding=UTF-8");
        launchedMavenRepository = resolveMavenRepository();
        if (launchedMavenRepository != null) command.add("-Dmaven.repo.local=" + launchedMavenRepository);
        command.add("-Djava.import.generatesMetadataFilesAtProjectRoot=false");
        command.add("-DDetectVMInstallationsJob.disabled=true");
        command.add("-Dsun.zip.disableMemoryMapping=true");
        command.add("-Xms256m");
        command.add("-Xmx" + maxHeap);
        Path lombok = lombokAgentJar;
        launchedLombokAgentJar = lombok;
        if (runtime.vendor() != JdkVendor.SEMERU) {
            command.add("-XX:+UseParallelGC");
            command.add("-XX:GCTimeRatio=4");
            command.add("-XX:AdaptiveSizePolicyWeight=90");
            command.add("-Xlog:disable");
            if (lombok == null && runtime.major() >= MIN_AUTO_SHARED_ARCHIVE_MAJOR) {
                command.add("-XX:+AutoCreateSharedArchive");
                command.add("-XX:SharedArchiveFile=" + installation.home()
                        .resolve("jdtls-jdk" + runtime.major() + ".jsa"));
            }
        }
        if (lombok != null) {
            command.add("-javaagent:" + lombok);
        }
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens");
        command.add("java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(installation.launcherJar().toString());
        command.add("-configuration");
        command.add(installation.configDir().toString());
        command.add("-data");
        command.add(workspace.toString());
        return command;
    }

    private void pumpStderr(Process started) {
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    if (!isExpectedCancellationNotice(lower)) {
                        log.debug("[jdtls] {}", line);
                    }
                    if (lower.contains("initialization failed")
                            || lower.contains("failed to import projects")
                            || lower.contains("overlaps the workspace location")) {
                        startupFailure = line.isBlank()
                                ? "Falha ao importar o projeto no JDT LS" : line.trim();
                        serviceReadyLatch.countDown();
                    }
                    if (isRecoverableDocumentError(line)) {
                        recoverDocumentSynchronization();
                    }
                }
            } catch (Exception error) {
                if (started.isAlive()) {
                    log.debug("Leitura da saida de erro do JDT LS foi interrompida", error);
                }
            }
        });
    }

    private static boolean isExpectedCancellationNotice(String lowerCaseLine) {
        return lowerCaseLine.contains("handlecancellation")
                || lowerCaseLine.contains("unmatched cancel notification");
    }

    public void stop() {
        stop(SHUTDOWN_TIMEOUT_MS, EXIT_TIMEOUT_MS);
    }

    private void stop(long shutdownTimeoutMs, long exitTimeoutMs) {
        Retired retired = retire();
        CompletableFuture<Void> done = new CompletableFuture<>();
        trackRetirement(done, retired);
        try {
            finishRetired(retired, shutdownTimeoutMs, exitTimeoutMs);
        } finally {
            done.complete(null);
        }
    }

    public CompletableFuture<Void> stopAsync() {
        Retired retired = retire();
        CompletableFuture<Void> done = new CompletableFuture<>();
        trackRetirement(done, retired);
        Thread.ofVirtual().name("jdtls-stop").start(() -> {
            try {
                finishRetired(retired, SHUTDOWN_TIMEOUT_MS, EXIT_TIMEOUT_MS);
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    public void stopAsyncIfBoundTo(Path root) {
        if (root == null) {
            return;
        }
        Path normalized = root.toAbsolutePath().normalize();
        synchronized (processLock) {
            if (!normalized.equals(projectRoot)) {
                return;
            }
        }
        stopAsync();
    }

    private void trackRetirement(CompletableFuture<Void> done, Retired retired) {
        synchronized (processLock) {
            if (retired.process() != null) {
                retiringProcesses.add(retired.process().toHandle());
            }
            CompletableFuture<Void> previous = retiring;
            retiring = previous.isDone() ? done : CompletableFuture.allOf(previous, done);
        }
    }

    public void resetProjectState() {
        documents.clear();
        clearNavigationCache(null);
    }

    public void shutdown() {
        terminated = true;
        try {
            stop(UNLOAD_SHUTDOWN_TIMEOUT_MS, UNLOAD_EXIT_TIMEOUT_MS);
            try {
                retiring.get(UNLOAD_RETIRE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("Parada anterior do JDT LS ainda em andamento", error);
            }
        } finally {
            for (ProcessHandle handle : List.copyOf(retiringProcesses)) {
                terminateProcessTree(handle);
            }
            LIVE_SERVICES.remove(this);
            resetProjectState();
            executor.shutdownNow();
        }
    }

    private record Retired(Process process, LspJsonRpcClient rpc, JdtLsWorkspaceLease lease) {
    }

    private Retired detach() {
        synchronized (processLock) {
            state = LanguageServerState.STOPPED;
            generation.incrementAndGet();
            Retired retired = new Retired(process, client, workspaceLease);
            process = null;
            client = null;
            workspaceLease = null;
            return retired;
        }
    }

    private Retired retire() {
        Retired retired = detach();
        warmingUp.set(false);
        clearNavigationCache(null);
        serviceReadyLatch.countDown();
        readyLatch.countDown();
        synchronized (this) {
            resetServerState();
        }
        return retired;
    }

    private void finishRetired(Retired retired, long shutdownTimeoutMs, long exitTimeoutMs) {
        try {
            LspJsonRpcClient rpc = retired.rpc();
            Process running = retired.process();
            if (rpc != null && !rpc.isClosed() && shutdownTimeoutMs > 0
                    && running != null && running.isAlive()) {
                try {
                    rpc.request("shutdown", Map.of()).get(shutdownTimeoutMs, TimeUnit.MILLISECONDS);
                    rpc.notify("exit", Map.of());
                    running.waitFor(exitTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception error) {
                    log.debug("JDT LS nao confirmou o encerramento dentro do prazo", error);
                }
            }
            if (running != null) {
                terminateProcessTree(running.toHandle());
            }
            if (rpc != null) {
                rpc.close();
            }
            if (retired.lease() != null) {
                retired.lease().close();
            }
        } finally {
            if (retired.process() != null) {
                retiringProcesses.remove(retired.process().toHandle());
            }
        }
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(JdtLsService::stopAllOnExit,
                    "jdtls-shutdown-hook"));
        } catch (IllegalStateException ignored) {
        }
    }

    private static void stopAllOnExit() {
        List<Thread> stoppers = new ArrayList<>();
        for (JdtLsService service : List.copyOf(LIVE_SERVICES)) {
            service.terminated = true;
            stoppers.add(Thread.ofPlatform().name("jdtls-exit").start(() -> {
                service.finishRetired(service.detach(), EXIT_HOOK_SHUTDOWN_TIMEOUT_MS,
                        EXIT_HOOK_SHUTDOWN_TIMEOUT_MS);
                for (ProcessHandle handle : List.copyOf(service.retiringProcesses)) {
                    terminateProcessTree(handle);
                }
            }));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_HOOK_TOTAL_TIMEOUT_MS);
        for (Thread stopper : stoppers) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                break;
            }
            try {
                stopper.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void resetServerState() {
        documents.clearSyncState();
        openedDuringImport.clear();
        watchedFiles.clear();
        watchedFlushTicket.incrementAndGet();
        resyncTicket.incrementAndGet();
        diagnosticsStore.reset();
        navigation.clearSymbols();
        decorations.reset();
        completion.clearCache();
        requests.clearInFlight();
        workspaceWorkTokens.clear();
        capabilities = ServerCapabilities.none();
    }

    private static void terminateProcessTree(ProcessHandle handle) {
        if (handle == null) {
            return;
        }
        List<ProcessHandle> descendants;
        try (var children = handle.descendants()) {
            descendants = children.toList();
        } catch (RuntimeException error) {
            descendants = List.of();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        if (handle.isAlive()) {
            handle.destroy();
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("Falha ao aguardar encerramento normal do processo JDT LS", error);
            }
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (handle.isAlive()) {
            handle.destroyForcibly();
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("Falha ao aguardar encerramento forcado do processo JDT LS", error);
            }
        }
    }

    public boolean awaitReady(long timeoutMs) {
        if (isRunning()) {
            return true;
        }
        try {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && isRunning();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<String> resolveBundlePaths() {
        if (bundles == null) {
            return List.of();
        }
        try {
            return bundles.resolveBundlePaths(springSupport);
        } catch (Exception error) {
            log.warn("Falha ao resolver os bundles do jdtls: {}", rootMessage(error));
            return List.of();
        }
    }

    private static long elapsedMs(long fromNanos, long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
    }

    private void initialize(LspJsonRpcClient rpc, Process server, long launchGeneration, Path root,
                            JdkInstallation runtime, JdkInstallation preferredProjectJdk,
                            List<String> bundlePaths) throws Exception {
        if (rpc == null) {
            throw new IllegalStateException("Cliente LSP indisponivel");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", LspConversions.toUri(root));
        params.put("workspaceFolders", List.of(Map.of(
                "uri", LspConversions.toUri(root),
                "name", root.getFileName() == null ? "workspace" : root.getFileName().toString())));
        params.put("capabilities", LspClientCapabilities.build(LspDecorations.TOKEN_TYPES, LspDecorations.TOKEN_MODIFIERS));
        JdkInstallation configuredJdk = preferredProjectJdk == null ? runtime : preferredProjectJdk;
        effectiveSettings = JdtLsSettings.build(configuredJdk, jdkService.available(), buildMode,
                inlayHintsMode);
        effectiveSettings = JdtLsSettings.withMavenSettings(effectiveSettings, root);
        params.put("initializationOptions", initializationOptions(effectiveSettings, bundlePaths));

        JsonNode result = awaitWhileAlive(rpc.request("initialize", params),
                () -> server.isAlive() && isCurrent(launchGeneration),
                INITIALIZE_WAIT_SLICE_MS, INITIALIZE_CEILING_MS,
                elapsed -> statusListener.onStatus("Java: iniciando o JDT LS... "
                        + TimeUnit.MILLISECONDS.toSeconds(elapsed) + " s", -1));
        capabilities = LspClientCapabilities.readServerCapabilities(result);
        rpc.notify("initialized", Map.of());
        rpc.notify("workspace/didChangeConfiguration",
                Map.of("settings", effectiveSettings));
    }

    private Map<String, Object> initializationOptions(Map<String, Object> settings,
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

    public boolean isDebugAdapterAvailable() {
        return debugCommands.isDebugAdapterAvailable();
    }

    public boolean prepareDebugAdapter() {
        return debugCommands.prepareDebugAdapter();
    }

    public int startDebugSession() {
        return debugCommands.startDebugSession();
    }

    public JsonNode findTestTypesAndMethods(Path file) {
        if (file == null) {
            return null;
        }
        return requests.requestInteractive("workspace/executeCommand", Map.of(
                "command", "vscode.java.test.findTestTypesAndMethods",
                "arguments", List.of(LspConversions.toUri(file))), 15_000);
    }

    public List<JavaTest> testsIn(Path file) {
        return JdtTestItems.parse(findTestTypesAndMethods(file), file);
    }

    public boolean isTestRunnerAvailable() {
        return testBundleLoaded && isInteractive();
    }

    public boolean updateProjectConfiguration(Path projectRoot) {
        return projectCommands.updateProjectConfiguration(projectRoot);
    }

    public void resynchronizeAfterProjectUpdate() {
        projectCommands.resynchronizeAfterProjectUpdate();
    }

    public String buildWorkspace(boolean fullBuild) {
        return projectCommands.buildWorkspace(fullBuild);
    }

    public String buildWorkspace(boolean fullBuild, StatusListener progress) {
        return projectCommands.buildWorkspace(fullBuild, progress);
    }

    public java.util.Optional<String> runtimeClasspath(Path projectOrSource) {
        return projectCommands.runtimeClasspath(projectOrSource);
    }

    static List<String> runtimeClasspathArguments(Path projectOrSource) {
        return JdtProjectCommands.runtimeClasspathArguments(projectOrSource);
    }

    private void registerHandlers(LspJsonRpcClient rpc) {
        if (rpc == null) {
            return;
        }
        rpc.onNotification("textDocument/publishDiagnostics", this::onPublishDiagnostics);
        rpc.onNotification("language/status", this::onLanguageStatus);
        rpc.onNotification("window/logMessage", this::onServerLogMessage);
        rpc.onNotification("window/showMessage", params -> {
            if (params != null) {
                statusListener.onStatus("Java: " + params.path("message").asText(""), -1);
            }
        });
        rpc.onNotification("$/progress", this::onProgress);
        rpc.onNotification("language/progressReport", this::onProgressReport);
        rpc.onRequest("window/workDoneProgress/create", params -> null);

        rpc.onRequest("workspace/configuration", params -> {
            int items = params != null && params.has("items") ? params.get("items").size() : 1;
            List<Object> answer = new ArrayList<>(items);
            for (int i = 0; i < items; i++) {
                String section = params != null && params.has("items")
                        ? params.get("items").get(i).path("section").asText("")
                        : "";
                answer.add(configurationValue(effectiveSettings, section));
            }
            return answer;
        });
        rpc.onRequest("client/registerCapability", params -> Map.of());
        rpc.onRequest("client/unregisterCapability", params -> Map.of());
        rpc.onRequest("workspace/applyEdit", params -> Map.of("applied", false));
        rpc.onRequest("workspace/codeLens/refresh", params -> {
            invalidateWorkspaceNavigation();
            return null;
        });
    }

    static Object configurationValue(Map<String, Object> settings, String section) {
        if (section == null || section.isBlank()) {
            return settings;
        }
        Object current = settings;
        for (String part : section.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    void onPublishDiagnostics(JsonNode params) {
        diagnosticsStore.publish(params);
    }

    boolean isDiagnosticsSettled() {
        return diagnosticsStore.isSettled();
    }

    void markDiagnosticsUnsettled() {
        diagnosticsStore.beginImport();
    }

    void settleDiagnostics() {
        diagnosticsStore.settle();
    }

    private void onLanguageStatus(JsonNode params) {
        if (params == null) {
            return;
        }
        String message = params.path("message").asText("");
        if ("ServiceReady".equalsIgnoreCase(params.path("type").asText(""))) {
            serviceReadyLatch.countDown();
            return;
        }
        if (!message.isBlank()) {
            publishProgress(progressAggregator.status(message));
        }
    }

    private void publishProgress(LspProgressAggregator.Snapshot snapshot) {
        LanguageServerState current = state;
        if (current == LanguageServerState.READY) {
            String label = snapshot.label().isBlank() ? "" : "Java: " + snapshot.label();
            workListener.onWork(label, snapshot.workPercent(), snapshot.visibleWork());
            if (snapshot.idle()) {
                progressAggregator.restartBackgroundWork();
            }
            diagnosticsStore.scheduleSettle();
            return;
        }
        if (current == LanguageServerState.STARTING || current == LanguageServerState.INDEXING) {
            statusListener.onStatus("Java: " + snapshot.label(), snapshot.percent());
        }
    }

    private void onProgressReport(JsonNode params) {
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
        publishProgress(params.path("complete").asBoolean(false)
                ? progressAggregator.end(token)
                : progressAggregator.report(token, task, status, percent));
    }

    private void onServerLogMessage(JsonNode params) {
        String message = params == null ? "" : params.path("message").asText("");
        if (isCompletionDocumentationFailure(message)) {
            log.debug("[jdtls] Javadoc invalido no completion; a documentacao vem pelo hover");
            return;
        }
        if (isRecoverableDocumentError(message)) {
            log.debug("[jdtls] BadLocationException; agendando resincronizacao integral");
            recoverDocumentSynchronization();
            return;
        }
        log.debug("[jdtls] {}", message);
    }

    private void onProgress(JsonNode params) {
        if (params == null) {
            return;
        }
        JsonNode value = params.get("value");
        if (value == null) {
            return;
        }
        String title = value.path("title").asText("");
        String message = value.path("message").asText("");
        String text = !message.isBlank() ? message : title;
        int percent = value.has("percentage") ? value.path("percentage").asInt(-1) : -1;
        String kind = value.path("kind").asText("");
        String token = params.path("token").asText();
        if ("begin".equalsIgnoreCase(kind)) {
            publishProgress(progressAggregator.begin(token, title, message, percent));
        } else if ("end".equalsIgnoreCase(kind)) {
            publishProgress(progressAggregator.end(token));
        } else {
            publishProgress(progressAggregator.report(token, title, message, percent));
        }
        if (!text.isBlank()) {
            StatusListener buildProgress = workspaceBuildProgress;
            if (buildProgress != null) {
                buildProgress.onStatus(text, percent);
            }
        }
        if ("begin".equalsIgnoreCase(kind)) {
            String operation = (title + " " + message).toLowerCase(java.util.Locale.ROOT);
            if (operation.contains("build") || operation.contains("import") || operation.contains("synchroniz")) {
                workspaceWorkTokens.add(token);
            }
            warmUpWorkStarted.set(true);
            return;
        }
        if ("end".equalsIgnoreCase(kind)) {
            if (warmUpWorkStarted.compareAndSet(true, false)) {
                finishWarmUp();
            }
            if (workspaceWorkTokens.remove(token)) invalidateWorkspaceNavigation();
            else decorations.refreshCodeLensesAfterWork();
        }
    }

    public synchronized void openDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        String content = text == null ? "" : text;
        String previous = documents.put(uri, content);
        if (!content.equals(previous)) {
            documentContentChanged(filePath, uri, previous, content, false);
        }
        if (!canSyncDocuments()) {
            return;
        }
        if (documents.markSynced(uri)) {
            sendDidOpen(uri, content);
        } else if (!content.equals(previous)) {
            sendDidChange(uri, previous, content);
        }
    }

    public synchronized void changeDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        String content = text == null ? "" : text;
        if (content.equals(documents.content(uri))) {
            return;
        }
        String previous = documents.put(uri, content);
        documentContentChanged(filePath, uri, previous, content, true);

        if (!canSyncDocuments()) {
            return;
        }
        if (documents.markSynced(uri)) {
            sendDidOpen(uri, content);
            return;
        }
        sendDidChange(uri, previous, content);
    }

    public synchronized void closeDocument(Path filePath) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        decorations.forgetRefreshTicket(uri);
        boolean wasSynced = documents.unmarkSynced(uri);
        documents.remove(uri);
        navigation.forgetSymbols(uri);
        decorations.discardCodeLenses(uri);
        completion.forget(uri);
        invalidateWorkspaceNavigation();
        requests.cancelInFlightForUri(uri);

        LspJsonRpcClient rpc = client;
        if (wasSynced && rpc != null && canSyncDocuments()) {
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    public void pathCreated(Path createdPath) {
        queueWatchedFile(createdPath, WatchedFileBatch.CREATED);
    }

    public void pathChanged(Path changedPath) {
        queueWatchedFile(changedPath, WatchedFileBatch.CHANGED);
    }

    public void requestExternalResync() {
        scheduleExternalResync();
    }

    public void resynchronizeWithDisk() {
        if (!canSyncDocuments()) {
            return;
        }
        invalidateWorkspaceNavigation();
        executor.execute(() -> resynchronizeOpenDocuments(ResyncMode.REOPEN, true,
                "Java: sincronizado com o disco"));
    }

    public String documentContent(Path filePath) {
        return filePath == null ? null : documents.content(LspConversions.toUri(filePath));
    }

    public <T> T withDocument(Path filePath, String diskText, java.util.function.Function<String, T> query) {
        String open = documentContent(filePath);
        if (open != null) {
            return query.apply(open);
        }
        try {
            return query.apply(diskText);
        } finally {
            if (documentContent(filePath) != null) {
                closeDocument(filePath);
            }
        }
    }

    private void queueWatchedFile(Path path, int changeType) {
        if (path == null) {
            return;
        }
        Path target = normalizePath(path);
        String uri = LspConversions.toUri(target);
        navigation.forgetSymbols(uri);
        decorations.discardCodeLenses(uri);
        completion.forget(uri);
        invalidateWorkspaceNavigation();

        watchedFiles.add(target, changeType, System.nanoTime());
        if (canSyncDocuments()) {
            scheduleWatchedFlush();
        }
    }

    private void scheduleWatchedFlush() {
        long ticket = watchedFlushTicket.incrementAndGet();
        long delay = TimeUnit.NANOSECONDS.toMillis(watchedFiles.delayNanos(System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(externalResyncDelayMs)));
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor)
                .execute(() -> flushWatchedFiles(ticket));
    }

    private void flushWatchedFiles(long ticket) {
        if (ticket != watchedFlushTicket.get()) {
            return;
        }
        drainWatchedFiles();
    }

    private void drainWatchedFiles() {
        LspJsonRpcClient rpc = client;
        if (rpc == null || rpc.isClosed() || !canSyncDocuments()) {
            return;
        }
        if (!watchedFiles.isPending()) {
            return;
        }
        boolean overflowed = watchedFiles.isOverflowed();
        List<WatchedFileBatch.Entry> entries = watchedFiles.drain();
        if (!overflowed) {
            List<Map<String, Object>> changes = new ArrayList<>(entries.size());
            for (WatchedFileBatch.Entry entry : entries) {
                String uri = LspConversions.toUri(entry.path());
                if (entry.changeType() != WatchedFileBatch.DELETED && documents.isSynced(uri)) {
                    continue;
                }
                changes.add(Map.of("uri", uri, "type", entry.changeType()));
            }
            for (int from = 0; from < changes.size(); from += WATCHED_FILES_PER_NOTIFICATION) {
                List<Map<String, Object>> chunk = changes.subList(from,
                        Math.min(changes.size(), from + WATCHED_FILES_PER_NOTIFICATION));
                rpc.notify("workspace/didChangeWatchedFiles", Map.of("changes", List.copyOf(chunk)));
            }
        } else {
            log.debug("Lote de mudancas externas estourou; ressincronizando tudo");
        }
        scheduleExternalResync();
    }

    private void scheduleExternalResync() {
        if (!canSyncDocuments()) {
            return;
        }
        long ticket = resyncTicket.incrementAndGet();
        CompletableFuture.delayedExecutor(externalResyncDelayMs, TimeUnit.MILLISECONDS, executor)
                .execute(() -> runExternalResync(ticket));
    }

    private void runExternalResync(long ticket) {
        if (ticket != resyncTicket.get() || !canSyncDocuments()) {
            return;
        }
        resynchronizeOpenDocuments(resyncMode(), false, null);
    }

    void setExternalResyncDelayMs(long delayMs) {
        externalResyncDelayMs = Math.max(0, delayMs);
    }

    void drainPendingWatchedFiles() {
        if (!watchedFiles.isPending()) {
            return;
        }
        drainWatchedFiles();
    }

    public void projectConfigurationUpdate() {
        invalidateWorkspaceNavigation();
        LspJsonRpcClient rpc = client;
        Path root = projectRoot;
        if (rpc == null || rpc.isClosed() || root == null || !isInteractive()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastProjectConfigurationUpdate.get();
        if (now - previous < PROJECT_CONFIGURATION_COOLDOWN_MS
                || !lastProjectConfigurationUpdate.compareAndSet(previous, now)) {
            return;
        }
        rpc.notify("java/projectConfigurationUpdate",
                Map.of("uri", LspConversions.toUri(root)));
    }

    public void pathDeleted(Path deletedPath) {
        if (deletedPath == null) {
            return;
        }
        Path deleted = normalizePath(deletedPath);
        invalidateWorkspaceNavigation();
        List<Path> openBelowDeleted = documents.uris().stream()
                .map(LspConversions::toPath)
                .filter(java.util.Objects::nonNull)
                .map(JdtLsService::normalizePath)
                .filter(path -> path.startsWith(deleted))
                .toList();
        openBelowDeleted.forEach(this::closeDocument);

        diagnosticsStore.removeBelow(deleted);

        queueWatchedFile(deleted, WatchedFileBatch.DELETED);
        if (affectsProjectStructure(deleted)) {
            projectConfigurationUpdate();
        }
    }

    static boolean affectsProjectStructure(Path deleted) {
        Path name = deleted == null ? null : deleted.getFileName();
        if (name == null) {
            return true;
        }
        String fileName = name.toString();
        if (fileName.endsWith(".java")) {
            return false;
        }
        return Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
                        "settings.gradle.kts", "gradle.properties", "src", "main", "test", "java",
                        "resources", ".classpath", ".project")
                .contains(fileName.toLowerCase(java.util.Locale.ROOT));
    }

    public synchronized void saveDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        changeDocument(filePath, text);
        String uri = LspConversions.toUri(filePath);
        LspJsonRpcClient rpc = client;
        if (rpc != null && canSyncDocuments() && documents.isSynced(uri)) {
            rpc.notify("textDocument/didSave", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    private synchronized void flushOpenDocuments() {
        Path root = projectRoot;
        documents.forEach((uri, content) -> {
            Path path = LspConversions.toPath(uri);
            if (root != null && (path == null || !path.startsWith(root))) {
                return;
            }
            if (documents.markSynced(uri)) {
                sendDidOpen(uri, content);
            }
        });
    }

    static boolean isRecoverableDocumentError(String line) {
        return line != null && line.toLowerCase(java.util.Locale.ROOT)
                .contains("badlocationexception");
    }

    static boolean isCompletionDocumentationFailure(String message) {
        if (message == null) return false;
        return message.contains("Unable to read documentation")
                || (message.contains("CompletionResolveHandler")
                && message.contains("StringIndexOutOfBoundsException"));
    }

    private boolean canSyncDocuments() {
        LanguageServerState current = state;
        LspJsonRpcClient rpc = client;
        return (current == LanguageServerState.INDEXING || current == LanguageServerState.READY)
                && rpc != null && !rpc.isClosed();
    }

    private void recoverDocumentSynchronization() {
        long now = System.currentTimeMillis();
        long previous = lastDocumentRecovery.get();
        if (now - previous < DOCUMENT_RECOVERY_COOLDOWN_MS
                || !lastDocumentRecovery.compareAndSet(previous, now)
                || !canSyncDocuments()) {
            return;
        }
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            return;
        }
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS, executor)
                .execute(this::performDocumentResynchronization);
    }

    private void performDocumentResynchronization() {
        log.warn("JDT LS perdeu a posicao de um documento; resincronizando {} buffer(s)",
                documents.size());
        resynchronizeOpenDocuments(ResyncMode.REOPEN, true, "Java: documentos resincronizados");
    }

    private ResyncMode resyncMode() {
        return documents.size() > MAX_REOPEN_DOCUMENTS ? ResyncMode.TOUCH : ResyncMode.REOPEN;
    }

    private synchronized void resynchronizeOpenDocuments(ResyncMode mode, boolean clearDiagnostics,
                                                         String statusMessage) {
        if (!canSyncDocuments()) {
            return;
        }
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            return;
        }
        documents.forEach((uri, content) -> {
            requests.cancelInFlightForUri(uri);
            if (mode == ResyncMode.REOPEN) {
                if (documents.unmarkSynced(uri)) {
                    rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
                }
                documents.incrementVersion(uri);
                if (documents.markSynced(uri)) {
                    sendDidOpen(uri, content);
                }
            } else if (documents.isSynced(uri)) {
                sendDidChange(uri, content, content);
            }
            navigation.forgetSymbols(uri);
            decorations.discardCodeLenses(uri);
            completion.forget(uri);
            Path path = LspConversions.toPath(uri);
            if (path == null) {
                return;
            }
            if (clearDiagnostics) {
                diagnosticsStore.removeAndPublish(path);
            }
            onCodeLensRefresh.accept(path);
        });
        if (statusMessage != null) {
            statusListener.onStatus(statusMessage, -1);
        }
    }

    private synchronized void reopenDocumentsOpenedDuringImport() {
        LspJsonRpcClient rpc = client;
        if (rpc == null || openedDuringImport.isEmpty()) {
            openedDuringImport.clear();
            return;
        }
        List<String> uris = List.copyOf(openedDuringImport);
        openedDuringImport.clear();
        for (String uri : uris) {
            String content = documents.content(uri);
            if (content == null || !documents.unmarkSynced(uri)) {
                continue;
            }
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
            documents.incrementVersion(uri);
            if (documents.markSynced(uri)) {
                sendDidOpen(uri, content);
            }
        }
        log.debug("{} documento(s) aberto(s) durante a importacao foram reabertos", uris.size());
    }

    private void refreshOpenDocuments() {
        documents.uris().forEach(uri -> {
            navigation.forgetSymbols(uri);
            decorations.discardCodeLenses(uri);
            completion.forget(uri);
            Path path = LspConversions.toPath(uri);
            if (path != null) {
                onDiagnosticsPublished.accept(path);
                onCodeLensRefresh.accept(path);
                onDocumentUpgrade.accept(path);
            }
        });
    }

    private void sendDidOpen(String uri, String content) {
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            return;
        }
        int version = documents.ensureVersion(uri);
        if (state != LanguageServerState.READY) {
            openedDuringImport.add(uri);
        }
        rpc.notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                "uri", uri,
                "languageId", "java",
                "version", version,
                "text", content)));
    }

    private void sendDidChange(String uri, String previous, String content) {
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            return;
        }
        int version = documents.incrementVersion(uri);
        Map<String, Object> change = capabilities.incrementalSync()
                ? incrementalDocumentChange(previous, content)
                : Map.of("text", content);
        rpc.notify("textDocument/didChange", Map.of(
                "textDocument", Map.of("uri", uri, "version", version),
                "contentChanges", List.of(change)));
    }

    private void invalidateWorkspaceNavigation() {
        workspaceRevision.incrementAndGet();
        navigation.clearCache();
        decorations.discardAll();
        documents.uris().forEach(uri -> {
            Path path = LspConversions.toPath(uri);
            if (path != null) onCodeLensRefresh.accept(path);
        });
    }

    private void invalidateNavigationForEdit() {
        workspaceRevision.incrementAndGet();
        navigation.clearCache();
    }

    private void documentContentChanged(Path filePath, String uri, String previous,
                                        String content, boolean deferCodeLensRefresh) {
        navigation.forgetSymbols(uri);
        decorations.discardCodeLenses(uri);
        invalidateNavigationForEdit();
        requests.cancelInFlightForUri(uri);
        diagnosticsStore.retainAfterEdit(filePath, previous, content);
        if (deferCodeLensRefresh) {
            decorations.scheduleCodeLensRefresh(filePath, uri);
        } else if (filePath != null) {
            onCodeLensRefresh.accept(filePath);
        }
    }

    static Map<String, Object> incrementalDocumentChange(String previous, String current) {
        String before = previous == null ? "" : previous;
        String after = current == null ? "" : current;
        int prefix = 0;
        int shared = Math.min(before.length(), after.length());
        while (prefix < shared && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        if (prefix > 0 && prefix < before.length()
                && Character.isLowSurrogate(before.charAt(prefix))
                && Character.isHighSurrogate(before.charAt(prefix - 1))) {
            prefix--;
        }
        int beforeEnd = before.length();
        int afterEnd = after.length();
        while (beforeEnd > prefix && afterEnd > prefix
                && before.charAt(beforeEnd - 1) == after.charAt(afterEnd - 1)) {
            beforeEnd--;
            afterEnd--;
        }
        Map<String, Integer> start = lspPosition(before, prefix);
        Map<String, Integer> end = lspPosition(before, beforeEnd);
        return Map.of(
                "range", Map.of("start", start, "end", end),
                "rangeLength", beforeEnd - prefix,
                "text", after.substring(prefix, afterEnd));
    }

    private static Map<String, Integer> lspPosition(String text, int offset) {
        int bounded = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < bounded; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return Map.of("line", line, "character", bounded - lineStart);
    }

    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int col) {
        return completion.complete(filePath, text, line, col);
    }

    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int col,
                                           CompletionTrigger trigger, Character triggerCharacter,
                                           int expectedVersion) {
        return completion.complete(filePath, text, line, col, trigger, triggerCharacter, expectedVersion);
    }

    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int col,
                                           CompletionTrigger trigger, Character triggerCharacter,
                                           int expectedVersion, boolean announceLateResult) {
        return completion.complete(filePath, text, line, col, trigger, triggerCharacter, expectedVersion,
                announceLateResult);
    }

    public CompletableFuture<List<AutoCompleteItem>> completeAsync(Path filePath, String text, int line,
                                                                   int col, CompletionTrigger trigger,
                                                                   Character triggerCharacter,
                                                                   int expectedVersion) {
        return completion.completeAsync(filePath, text, line, col, trigger, triggerCharacter, expectedVersion);
    }

    public CompletableFuture<AutoCompleteItem> resolveCompletionAsync(AutoCompleteItem item) {
        return completion.resolveCompletionAsync(item);
    }

    public int documentVersion(Path filePath) {
        if (filePath == null) {
            return ANY_VERSION;
        }
        return documents.version(LspConversions.toUri(filePath));
    }

    public List<AutoCompleteItem> cachedCompletions(Path filePath, String text, int line, int col) {
        return completion.cachedCompletions(filePath, text, line, col);
    }

    public List<AutoCompleteItem> reusableCompletions(Path filePath, String text, int line, int col) {
        return completion.reusableCompletions(filePath, text, line, col);
    }

    public void warmCompletion(Path filePath, String text, int line, int col) {
        completion.warmCompletion(filePath, text, line, col);
    }

    public CompletableFuture<HoverInfo> hoverAsync(Path filePath, String text, int line, int col) {
        return navigation.hoverAsync(filePath, text, line, col);
    }

    public CompletableFuture<SignatureHelp> signatureHelpAsync(Path filePath, String text, int line, int col) {
        return navigation.signatureHelpAsync(filePath, text, line, col);
    }

    public CompletableFuture<List<Range>> selectionRangesAsync(Path filePath, String text, int line, int col) {
        return navigation.selectionRangesAsync(filePath, text, line, col);
    }

    public HoverInfo hover(Path filePath, String text, int line, int col) {
        return navigation.hover(filePath, text, line, col);
    }

    public SignatureHelp signatureHelp(Path filePath, String text, int line, int col) {
        return navigation.signatureHelp(filePath, text, line, col);
    }

    public List<Location> definitions(Path filePath, String text, int line, int col) {
        return navigation.definitions(filePath, text, line, col);
    }

    public List<Location> definitionsInteractive(Path filePath, String text, int line, int col) {
        return navigation.definitionsInteractive(filePath, text, line, col);
    }

    public Result navigation(Kind kind, Path file, String text, int line, int col) {
        return navigation.navigation(kind, file, text, line, col);
    }

    public Result navigation(Kind kind, Path file, String text, int line, int col, long timeoutMs) {
        return navigation.navigation(kind, file, text, line, col, timeoutMs);
    }

    public List<Location> definitionsAtUri(String uri, int line, int col) {
        if (!capabilities.definition() || !JavaClassFileNavigation.isClassFileUri(uri)) {
            return List.of();
        }
        Map<String, Object> params = Map.of(
                "textDocument", Map.of("uri", uri),
                "position", Map.of(
                        "line", Math.max(0, line),
                        "character", Math.max(0, col)));
        return LspConversions.locations(requests.requestInteractive(
                "textDocument/definition", params, INTERACTIVE_TIMEOUT_MS));
    }

    public HoverInfo hoverAtUri(String uri, int line, int col) {
        if (!JavaClassFileNavigation.isClassFileUri(uri)) {
            return null;
        }
        Map<String, Object> params = Map.of(
                "textDocument", Map.of("uri", uri),
                "position", Map.of(
                        "line", Math.max(0, line),
                        "character", Math.max(0, col)));
        return LspConversions.hover(requests.requestInteractive(
                "textDocument/hover", params, INTERACTIVE_TIMEOUT_MS));
    }

    public boolean isClassFileUri(String uri) {
        return JavaClassFileNavigation.isClassFileUri(uri);
    }

    public String classFileSourceName(String uri) {
        return JavaClassFileNavigation.sourceFileName(uri);
    }

    public String classFileTabKey(String uri) {
        return JavaClassFileNavigation.tabKey(uri);
    }

    public String classFileContents(String uri) {
        if (!JavaClassFileNavigation.isClassFileUri(uri)) {
            return null;
        }
        JsonNode result = requests.requestInteractive(
                "java/classFileContents", Map.of("uri", uri), REQUEST_TIMEOUT_MS * 2);
        if (result == null || result.isNull()) {
            return null;
        }
        if (result.isTextual()) {
            return result.asText();
        }
        String content = result.path("contents").asText("");
        if (content.isBlank()) {
            content = result.path("content").asText("");
        }
        return content.isBlank() ? null : content;
    }

    public boolean supportsTypeHierarchy() {
        return navigation.supportsTypeHierarchy();
    }

    public boolean supportsFoldingRanges() {
        return navigation.supportsFoldingRanges();
    }

    public boolean supportsCallHierarchy() {
        return navigation.supportsCallHierarchy();
    }

    public List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int col) {
        return navigation.prepareCallHierarchy(filePath, text, line, col);
    }

    public CompletableFuture<List<FoldRange>> foldingRangesAsync(Path filePath, String text) {
        return navigation.foldingRangesAsync(filePath, text);
    }

    public List<TypeHierarchyItem> prepareTypeHierarchy(Path filePath, String text, int line, int col) {
        return navigation.prepareTypeHierarchy(filePath, text, line, col);
    }

    public List<TypeHierarchyItem> supertypes(TypeHierarchyItem item) {
        return navigation.supertypes(item);
    }

    public List<TypeHierarchyItem> subtypes(TypeHierarchyItem item) {
        return navigation.subtypes(item);
    }

    public List<CallHierarchyCall> incomingCalls(CallHierarchyItem item) {
        return navigation.incomingCalls(item);
    }

    public List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item) {
        return navigation.outgoingCalls(item);
    }

    public List<DocumentSymbol> documentSymbols(Path filePath, String text) {
        return navigation.documentSymbols(filePath, text);
    }

    public List<DocumentSymbol> documentSymbolsInteractive(Path filePath, String text) {
        return navigation.documentSymbolsInteractive(filePath, text);
    }

    public List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col) {
        return navigation.documentHighlights(filePath, text, line, col);
    }

    public List<DocumentHighlight> documentHighlightsInteractive(Path filePath, String text,
                                                                 int line, int col) {
        return navigation.documentHighlightsInteractive(filePath, text, line, col);
    }

    public List<TextEdit> rename(Path filePath, String text, int line, int col, String newName) {
        return refactoring.rename(filePath, text, line, col, newName);
    }

    public IdeWorkspaceEdit renameWorkspace(Path filePath, String text, int line, int col, String newName) {
        return refactoring.renameWorkspace(filePath, text, line, col, newName);
    }

    public String lastRenameProblem() {
        return refactoring.lastRenameProblem();
    }

    public IdeWorkspaceEdit moveTypesWorkspace(List<Path> sources, Path targetDirectory) {
        return move.moveTypesWorkspace(sources, targetDirectory);
    }

    public IdeWorkspaceEdit willRenameFilesWorkspace(Map<Path, Path> renames) {
        return move.willRenameFilesWorkspace(renames);
    }

    public String lastMoveProblem() {
        return move.lastMoveProblem();
    }

    static JsonNode moveDestinationFor(JsonNode destinations, Path targetDirectory) {
        return JdtMove.moveDestinationFor(destinations, targetDirectory);
    }

    static String textIn(String text, Range range) {
        return LspRefactoring.textIn(text, range);
    }

    static String identifierAt(String text, int line, int col) {
        return LspRefactoring.identifierAt(text, line, col);
    }

    public PrepareRenameResult prepareRename(Path filePath, String text, int line, int col) {
        return refactoring.prepareRename(filePath, text, line, col);
    }

    public List<CodeAction> codeActions(Path filePath, String text, Range range,
                                        List<Diagnostic> diagnostics) {
        return refactoring.codeActions(filePath, text, range, diagnostics);
    }

    static List<JsonNode> diagnosticsIntersecting(List<JsonNode> diagnostics, Range range) {
        return LspRefactoring.diagnosticsIntersecting(diagnostics, range);
    }

    public ResolvedCodeAction resolveCodeAction(String rawJson) {
        return refactoring.resolveCodeAction(rawJson);
    }

    public ImportLookup importCandidates(Path filePath, String text, Range pasted,
                                                   Set<String> handled) {
        if (!capabilities.codeAction() || !isCurrentText(filePath, text)) {
            return ImportLookup.PENDING;
        }
        Map<String, JsonNode> unresolved = ImportCandidates.unresolvedByName(
                diagnosticsStore.raw(filePath), text, pasted, handled);
        if (unresolved.isEmpty()) {
            return ImportLookup.PENDING;
        }
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        for (JsonNode diagnostic : unresolved.values()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("textDocument", documentId(filePath));
            params.put("range", diagnostic.get("range"));
            params.put("context", Map.of("diagnostics", List.of(diagnostic), "only", List.of("quickfix")));
            JsonNode result = requests.requestInteractive("textDocument/codeAction", params,
                    IMPORT_CANDIDATES_TIMEOUT_MS);
            if (result == null) {
                return ImportLookup.PENDING;
            }
            ImportCandidates.merge(candidates, ImportCandidates.fromActions(result));
        }
        return new ImportLookup(true, candidates, unresolved.keySet());
    }

    public List<TypeSymbol> workspaceTypes(String query) {
        return refactoring.workspaceTypes(query);
    }

    static List<TypeSymbol> parseWorkspaceTypes(JsonNode result) {
        return LspRefactoring.parseWorkspaceTypes(result);
    }

    public OverrideStatus overridableMethods(Path filePath, String text, int line, int col) {
        return sourceGeneration.overridableMethods(filePath, text, line, col);
    }

    static OverrideStatus parseOverrideStatus(JsonNode result) {
        return JdtSourceGeneration.parseOverrideStatus(result);
    }

    public List<TextEdit> generateOverridableMethods(Path filePath, String text, int line, int col,
                                                      List<SourceItem> methods) {
        return sourceGeneration.generateOverridableMethods(filePath, text, line, col, methods);
    }

    public ConstructorsStatus constructorsStatus(Path filePath, String text, int line, int col) {
        return sourceGeneration.constructorsStatus(filePath, text, line, col);
    }

    public List<TextEdit> generateConstructors(Path filePath, String text, int line, int col,
                                               List<SourceItem> constructors, List<SourceItem> fields) {
        return sourceGeneration.generateConstructors(filePath, text, line, col, constructors, fields);
    }

    public List<SourceItem> accessorsStatus(Path filePath, String text, int line, int col) {
        return sourceGeneration.accessorsStatus(filePath, text, line, col);
    }

    public List<TextEdit> generateAccessors(Path filePath, String text, int line, int col,
                                            List<SourceItem> accessors) {
        return sourceGeneration.generateAccessors(filePath, text, line, col, accessors);
    }

    public FieldsStatus hashCodeEqualsStatus(Path filePath, String text, int line, int col) {
        return sourceGeneration.hashCodeEqualsStatus(filePath, text, line, col);
    }

    public List<TextEdit> generateHashCodeEquals(Path filePath, String text, int line, int col,
                                                  List<SourceItem> fields, boolean regenerate) {
        return sourceGeneration.generateHashCodeEquals(filePath, text, line, col, fields, regenerate);
    }

    public FieldsStatus toStringStatus(Path filePath, String text, int line, int col) {
        return sourceGeneration.toStringStatus(filePath, text, line, col);
    }

    public List<TextEdit> generateToString(Path filePath, String text, int line, int col,
                                            List<SourceItem> fields) {
        return sourceGeneration.generateToString(filePath, text, line, col, fields);
    }

    public List<DelegateTarget> delegateTargets(Path filePath, String text, int line, int col) {
        return sourceGeneration.delegateTargets(filePath, text, line, col);
    }

    public List<TextEdit> generateDelegateMethods(Path filePath, String text, int line, int col,
                                                   DelegateTarget target, List<SourceItem> methods) {
        return sourceGeneration.generateDelegateMethods(filePath, text, line, col, target, methods);
    }

    public String applyTextEdits(String text, List<TextEdit> edits) {
        return TextEditApplier.apply(text, edits);
    }

    static List<SourceItem> variableItems(JsonNode values) {
        return JdtSourceGeneration.variableItems(values);
    }

    static List<SourceItem> methodItems(JsonNode values, boolean selected) {
        return JdtSourceGeneration.methodItems(values, selected);
    }

    static List<JsonNode> rawValues(List<SourceItem> items) {
        return JdtSourceGeneration.rawValues(items);
    }

    static boolean isSourcePrompt(String id) {
        return JdtSourceGeneration.isSourcePrompt(id);
    }

    public void executeCodeAction(String rawJson) {
        refactoring.executeCodeAction(rawJson);
    }

    public CompletableFuture<List<InlayHint>> inlayHintsAsync(Path filePath, String text,
                                                               int firstLine, int lastLine) {
        return decorations.inlayHintsAsync(filePath, text, firstLine, lastLine);
    }

    public List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine) {
        return decorations.inlayHints(filePath, text, firstLine, lastLine);
    }

    public List<JavaCodeLens> codeLenses(Path filePath, String text) {
        return decorations.codeLenses(filePath, text);
    }

    private static JavaCodeLens unresolvedLens(JsonNode node, Status status) {
        JsonNode data = node.path("data");
        String type = data.isArray() && data.size() > 2 ? data.get(2).asText() : "";
        String command = switch (type) {
            case "references" -> "java.show.references";
            case "implementations" -> "java.show.implementations";
            default -> "";
        };
        return new JavaCodeLens(LspConversions.range(node.get("range")), "", command, List.of(), status);
    }

    public CompletableFuture<List<SemanticToken>> semanticTokensAsync(Path filePath, String text) {
        return decorations.semanticTokensAsync(filePath, text);
    }

    public List<SemanticToken> semanticTokens(Path filePath, String text) {
        return decorations.semanticTokens(filePath, text);
    }

    public String format(Path filePath, String text, int tabSize, boolean insertSpaces) {
        return refactoring.format(filePath, text, tabSize, insertSpaces);
    }

    public String prepareSave(Path file, String source, boolean organize, boolean format,
                              int tabSize, boolean spaces) {
        if (!syncBeforeRequest(file, source)) return source;
        String current = source;
        if (organize) {
            String next = organizeImports(file, current);
            if (next != null) {
                if (!replaceSnapshot(file, current, next)) return source;
                current = next;
            }
        }
        if (format) {
            String next = format(file, current, tabSize, spaces);
            if (next != null) {
                if (!replaceSnapshot(file, current, next)) return source;
                current = next;
            }
        }
        return isCurrentText(file, current) ? current : source;
    }

    private synchronized boolean replaceSnapshot(Path file, String expected, String next) {
        if (!isCurrentText(file, expected)) return false;
        changeDocument(file, next);
        return true;
    }

    public String organizeImports(Path filePath, String text) {
        return refactoring.organizeImports(filePath, text);
    }

    public Collection<Diagnostic> diagnostics(Path filePath) {
        return diagnosticsStore.diagnostics(filePath);
    }

    public void clearDiagnostics() {
        diagnosticsStore.clear();
    }

    public Diagnostic diagnosticAt(Path filePath, int line, int col) {
        return diagnosticsStore.diagnosticAt(filePath, line, col);
    }

    public HoverInfo diagnosticHover(Path filePath, int line, int col) {
        return diagnosticsStore.diagnosticHover(filePath, line, col);
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            return Path.of(normalized.toString().toLowerCase(java.util.Locale.ROOT));
        }
        return normalized;
    }

    private boolean syncBeforeRequest(Path filePath, String text) {
        return syncBeforeRequest(filePath, text, false);
    }

    private synchronized boolean syncBeforeRequest(Path filePath, String text, boolean authoritative) {
        if (filePath == null || text == null) {
            return filePath != null;
        }
        String uri = LspConversions.toUri(filePath);
        String current = documents.content(uri);
        if (current == null) {
            openDocument(filePath, text);
            return true;
        }
        if (!current.equals(text)) {
            if (!authoritative && documents.hasSeen(uri, text)) {
                log.debug("Requisicao descartada para {}: revisao anterior do editor", uri);
                return false;
            }
            log.debug("Sincronizando {} antes da requisicao interativa", uri);
            changeDocument(filePath, text);
        }
        return true;
    }

    private void clearNavigationCache(String uri) {
        workspaceRevision.incrementAndGet();
        if (uri == null || uri.isBlank()) {
            navigation.clearCache();
            return;
        }
        navigation.clearCacheFor(uri);
    }

    private boolean isCurrentText(Path filePath, String text) {
        return filePath != null && (text == null ? "" : text)
                .equals(documents.content(LspConversions.toUri(filePath)));
    }
}
