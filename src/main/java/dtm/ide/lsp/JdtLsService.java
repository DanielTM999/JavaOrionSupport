package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
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

    private static final long IMPORT_CANDIDATES_TIMEOUT_MS = 5_000;

    private static final long INITIALIZE_CEILING_MS = 900_000;
    private static final long INITIALIZE_WAIT_SLICE_MS = 5_000;
    private static final long SERVICE_READY_TIMEOUT_MS = 300_000;
    private static final long SERVICE_READY_POLL_MS = 250;
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
    private static final long PROJECT_CONFIGURATION_COOLDOWN_MS = 2_000;
    private static final long WARM_UP_TIMEOUT_MS = 8_000;

    private final JdkService jdkService;
    private final JdtLsProvisioner provisioner;
    private final JdtLsExtensionBundles bundles;
    private final Consumer<Path> onDiagnosticsPublished;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("jdtls-", 0).factory());

    private final JdtDocumentStore documents = new JdtDocumentStore();
    private final LspDocumentSync documentSync;
    private final LspDiagnosticsStore diagnosticsStore;
    private final Set<String> workspaceWorkTokens = ConcurrentHashMap.newKeySet();
    private final AtomicLong workspaceRevision = new AtomicLong();
    private final AtomicLong lastProjectConfigurationUpdate = new AtomicLong();
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

    private final LspWatchedFiles watched = new LspWatchedFiles(executor, documents,
            new LspWatchedFiles.Host() {
                @Override
                public LspJsonRpcClient client() {
                    return client;
                }

                @Override
                public boolean canSyncDocuments() {
                    return JdtLsService.this.canSyncDocuments();
                }

                @Override
                public void invalidateWorkspaceNavigation() {
                    JdtLsService.this.invalidateWorkspaceNavigation();
                }

                @Override
                public void forgetSymbols(String uri) {
                    navigation.forgetSymbols(uri);
                }

                @Override
                public void discardCodeLenses(String uri) {
                    decorations.discardCodeLenses(uri);
                }

                @Override
                public void forgetCompletion(String uri) {
                    completion.forget(uri);
                }

                @Override
                public void resynchronizeOpenDocuments() {
                    JdtLsService.this.resynchronizeOpenDocuments(resyncMode(), false, null);
                }
            });

    private final JdtLsProcess processSupport = new JdtLsProcess(new JdtLsProcess.Host() {
        @Override
        public Path resolveMavenRepository() {
            return JdtLsService.this.resolveMavenRepository();
        }

        @Override
        public void launchedMavenRepository(Path path) {
            launchedMavenRepository = path;
        }

        @Override
        public String maxHeap() {
            return maxHeap;
        }

        @Override
        public Path lombokAgentJar() {
            return lombokAgentJar;
        }

        @Override
        public void launchedLombokAgentJar(Path path) {
            launchedLombokAgentJar = path;
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
        this.documentSync = new LspDocumentSync(documents, executor,
                new LspDocumentSync.Host() {
                    @Override
                    public LspJsonRpcClient client() {
                        return client;
                    }

                    @Override
                    public LanguageServerState state() {
                        return state;
                    }

                    @Override
                    public ServerCapabilities capabilities() {
                        return capabilities;
                    }

                    @Override
                    public Path projectRoot() {
                        return projectRoot;
                    }

                    @Override
                    public void documentContentChanged(Path filePath, String uri, String previous,
                                                       String content, boolean deferCodeLensRefresh) {
                        JdtLsService.this.documentContentChanged(filePath, uri, previous,
                                content, deferCodeLensRefresh);
                    }

                    @Override
                    public void forgetRefreshTicket(String uri) {
                        decorations.forgetRefreshTicket(uri);
                    }

                    @Override
                    public void forgetSymbols(String uri) {
                        navigation.forgetSymbols(uri);
                    }

                    @Override
                    public void discardCodeLenses(String uri) {
                        decorations.discardCodeLenses(uri);
                    }

                    @Override
                    public void forgetCompletion(String uri) {
                        completion.forget(uri);
                    }

                    @Override
                    public void invalidateWorkspaceNavigation() {
                        JdtLsService.this.invalidateWorkspaceNavigation();
                    }

                    @Override
                    public void cancelInFlightForUri(String uri) {
                        requests.cancelInFlightForUri(uri);
                    }

                    @Override
                    public void removeDiagnostics(Path path) {
                        diagnosticsStore.removeAndPublish(path);
                    }

                    @Override
                    public void codeLensRefresh(Path path) {
                        onCodeLensRefresh.accept(path);
                    }

                    @Override
                    public void status(String message) {
                        statusListener.onStatus(message, -1);
                    }

                    @Override
                    public void resynchronizeOpenDocuments(LspDocumentSync.ResyncMode mode,
                                                           boolean clearDiagnostics, String statusMessage) {
                        JdtLsService.this.resynchronizeOpenDocuments(mode, clearDiagnostics, statusMessage);
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
            JdtLsProcess.removeLegacyOverlappingWorkspace(root);
            lease = JdtLsWorkspaceLease.acquire(provisioner.workspaceFor(root));
            Path workspace = lease.workspace();
            JdtLsProcess.stopOrphanedWorkspaceServers(lease);

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
                JdtLsProcess.terminateProcessTree(started.toHandle());
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
        return processSupport.buildCommand(runtime, installation, workspace);
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
                JdtLsProcess.terminateProcessTree(handle);
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
                JdtLsProcess.terminateProcessTree(running.toHandle());
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
                    JdtLsProcess.terminateProcessTree(handle);
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
        documentSync.reset();
        watched.reset();
        diagnosticsStore.reset();
        navigation.clearSymbols();
        decorations.reset();
        completion.clearCache();
        requests.clearInFlight();
        workspaceWorkTokens.clear();
        capabilities = ServerCapabilities.none();
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
        documentSync.openDocument(filePath, text);
    }

    public synchronized void changeDocument(Path filePath, String text) {
        documentSync.changeDocument(filePath, text);
    }

    public synchronized void closeDocument(Path filePath) {
        documentSync.closeDocument(filePath);
    }

    public void pathCreated(Path createdPath) {
        queueWatchedFile(createdPath, WatchedFileBatch.CREATED);
    }

    public void pathChanged(Path changedPath) {
        queueWatchedFile(changedPath, WatchedFileBatch.CHANGED);
    }

    public void requestExternalResync() {
        watched.scheduleExternalResync();
    }

    public void resynchronizeWithDisk() {
        if (!canSyncDocuments()) {
            return;
        }
        invalidateWorkspaceNavigation();
        executor.execute(() -> resynchronizeOpenDocuments(LspDocumentSync.ResyncMode.REOPEN, true,
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
        watched.queue(path, changeType);
    }

    void setExternalResyncDelayMs(long delayMs) {
        watched.setExternalResyncDelayMs(delayMs);
    }

    void drainPendingWatchedFiles() {
        watched.drainPending();
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
        documentSync.saveDocument(filePath, text);
    }

    private synchronized void flushOpenDocuments() {
        documentSync.flushOpenDocuments();
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
        return documentSync.canSyncDocuments();
    }

    private void recoverDocumentSynchronization() {
        documentSync.recoverDocumentSynchronization();
    }

    private LspDocumentSync.ResyncMode resyncMode() {
        return documentSync.resyncMode();
    }

    private synchronized void resynchronizeOpenDocuments(LspDocumentSync.ResyncMode mode,
                                                         boolean clearDiagnostics, String statusMessage) {
        documentSync.resynchronizeOpenDocuments(mode, clearDiagnostics, statusMessage);
    }

    private synchronized void reopenDocumentsOpenedDuringImport() {
        documentSync.reopenDocumentsOpenedDuringImport();
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
        return LspDocumentSync.incrementalDocumentChange(previous, current);
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

    private synchronized boolean syncBeforeRequest(Path filePath, String text) {
        return documentSync.syncBeforeRequest(filePath, text);
    }

    private synchronized boolean syncBeforeRequest(Path filePath, String text,
                                                   boolean authoritative) {
        return documentSync.syncBeforeRequest(filePath, text, authoritative);
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
