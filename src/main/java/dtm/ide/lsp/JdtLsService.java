package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.inspection.DiagnosticRanges;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.Consumer;

@Slf4j
public class JdtLsService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum State {
        NOT_STARTED,
        STARTING,
        INDEXING,
        READY,
        STOPPED,
        ERROR
    }

    public interface StatusListener {
        void onStatus(String message, int percent);
    }

    enum ResyncMode {
        REOPEN,
        TOUCH
    }

    public static final String APPLY_CODE_ACTION_COMMAND = "java/applyCodeAction";
    public static final String OVERRIDE_METHODS_PROMPT = "java.action.overrideMethodsPrompt";
    public static final String HASHCODE_EQUALS_PROMPT = "java.action.hashCodeEqualsPrompt";
    public static final String GENERATE_TOSTRING_PROMPT = "java.action.generateToStringPrompt";
    public static final String GENERATE_ACCESSORS_PROMPT = "java.action.generateAccessorsPrompt";
    public static final String GENERATE_CONSTRUCTORS_PROMPT = "java.action.generateConstructorsPrompt";
    public static final String GENERATE_DELEGATE_METHODS_PROMPT = "java.action.generateDelegateMethodsPrompt";

    public enum CompletionTrigger {
        INVOKED(1),
        TRIGGER_CHARACTER(2),
        INCOMPLETE(3);

        private final int lspKind;

        CompletionTrigger(int lspKind) {
            this.lspKind = lspKind;
        }

        public int lspKind() {
            return lspKind;
        }
    }

    public record SourceAction(String title, String command) {
    }

    public record SourceItem(JsonNode value, String label, String detail, boolean selected) {
    }

    public record OverrideStatus(String type, List<SourceItem> methods) {
    }

    public record FieldsStatus(String type, List<SourceItem> fields,
                               List<String> existingMethods, boolean exists) {
    }

    public record ConstructorsStatus(List<SourceItem> constructors, List<SourceItem> fields) {
    }

    public record DelegateTarget(JsonNode field, String label, List<SourceItem> methods) {
    }

    private static final long REQUEST_TIMEOUT_MS = 4_000;
    private static final long INTERACTIVE_TIMEOUT_MS = 800;
    private static final long INDEXING_COMPLETION_TIMEOUT_MS = 750;
    private static final long READY_COMPLETION_TIMEOUT_MS = 1_500;
    private static final long INITIALIZE_TIMEOUT_MS = 120_000;
    private static final long SERVICE_READY_TIMEOUT_MS = 300_000;
    private static final long SERVICE_READY_POLL_MS = 250;
    private static final long DOCUMENT_RECOVERY_COOLDOWN_MS = 5_000;
    private static final long EXTERNAL_RESYNC_DELAY_MS = 350;
    private static final long EXTERNAL_RESYNC_MAX_DELAY_MS = 2_000;
    private static final long PROJECT_CONFIGURATION_COOLDOWN_MS = 2_000;
    private static final int MAX_PENDING_WATCHED_FILES = 512;
    private static final int MAX_REOPEN_DOCUMENTS = 30;
    private static final int MAX_COMPLETION_ITEMS = 80;
    public static final int ANY_VERSION = -1;
    private static final int MAX_CODE_LENS_RESOLVE = 8;
    private static final long CODE_LENS_RETRY_TIMEOUT_MS = 20_000;
    private static final int MAX_CODE_LENS_RETRIES = 2;
    private static final long CODE_LENS_WORK_REFRESH_COOLDOWN_MS = 2_000;
    private static final long WARM_UP_TIMEOUT_MS = 8_000;

    private static final List<String> TOKEN_TYPES = List.of(
            "namespace", "class", "interface", "enum", "enumMember", "type", "typeParameter",
            "method", "property", "variable", "parameter", "record", "recordComponent",
            "annotation", "annotationMember", "modifier", "keyword", "comment", "string",
            "number", "operator");

    private static final List<String> TOKEN_MODIFIERS = List.of(
            "abstract", "static", "final", "deprecated", "declaration", "documentation",
            "public", "private", "protected", "native", "generic", "typeArgument",
            "importDeclaration", "constructor");

    private final JdkService jdkService;
    private final JdtLsProvisioner provisioner;
    private final JdtLsExtensionBundles bundles;
    private final Consumer<Path> onDiagnosticsPublished;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("jdtls-", 0).factory());

    private final JdtDocumentStore documents = new JdtDocumentStore();
    private final Map<Path, List<Diagnostic>> diagnosticsByPath = new ConcurrentHashMap<>();
    private final Map<Path, List<JsonNode>> rawDiagnosticsByPath = new ConcurrentHashMap<>();
    private final Map<String, SymbolCache> symbolCache = new ConcurrentHashMap<>();
    private final Map<String, LensWork> codeLensCache = new ConcurrentHashMap<>();
    private final Map<String, CompletionCache> completionCache = new ConcurrentHashMap<>();
    private final Map<String, List<Location>> navigationCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> inFlightRequests = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastFailureLog = new ConcurrentHashMap<>();
    private final Set<String> workspaceWorkTokens = ConcurrentHashMap.newKeySet();
    private final AtomicLong workspaceRevision = new AtomicLong();
    private final AtomicLong lastDocumentRecovery = new AtomicLong();
    private final AtomicLong lastProjectConfigurationUpdate = new AtomicLong();
    private final AtomicLong watchedFlushTicket = new AtomicLong();
    private final AtomicLong resyncTicket = new AtomicLong();
    private final WatchedFileBatch watchedFiles = new WatchedFileBatch(
            MAX_PENDING_WATCHED_FILES,
            TimeUnit.MILLISECONDS.toNanos(EXTERNAL_RESYNC_MAX_DELAY_MS));
    private volatile long externalResyncDelayMs = EXTERNAL_RESYNC_DELAY_MS;
    private final AtomicLong lastCodeLensWorkRefresh = new AtomicLong();
    private final Object processLock = new Object();

    private volatile State state = State.NOT_STARTED;
    private volatile String lastError;
    private volatile String startupFailure;
    private volatile Path projectRoot;
    private volatile Process process;
    private volatile LspJsonRpcClient client;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile CountDownLatch serviceReadyLatch = new CountDownLatch(1);
    private volatile ServerCapabilities capabilities = ServerCapabilities.none();
    private volatile StatusListener statusListener = (message, percent) -> {
    };
    private volatile StatusListener workspaceBuildProgress;
    private volatile String maxHeap = "2G";
    private volatile dtm.ide.settings.JdtBuildMode buildMode =
            dtm.ide.settings.JdtBuildMode.PROJECT_BUILD;
    private volatile Path lombokAgentJar;
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
    private final AtomicBoolean warmingUp = new AtomicBoolean();
    private final AtomicBoolean warmUpWorkStarted = new AtomicBoolean();
    private volatile long warmUpDeadline;

    public JdtLsService(JdkService jdkService, JdtLsProvisioner provisioner,
                        JdtLsExtensionBundles bundles, Consumer<Path> onDiagnosticsPublished) {
        this.jdkService = jdkService;
        this.provisioner = provisioner;
        this.bundles = bundles;
        this.onDiagnosticsPublished = onDiagnosticsPublished == null ? path -> {
        } : onDiagnosticsPublished;
    }

    record ServerCapabilities(
            boolean definition,
            boolean typeDefinition,
            boolean implementation,
            boolean references,
            boolean documentSymbol,
            boolean documentHighlight,
            boolean codeLens,
            boolean rename,
            boolean formatting,
            boolean rangeFormatting,
            boolean codeAction,
            boolean signatureHelp,
            boolean inlayHint,
            boolean semanticTokens,
            boolean callHierarchy,
            boolean executeCommand,
            boolean completionResolve,
            boolean incrementalSync,
            Set<Character> completionTriggers,
            Set<Character> signatureTriggers) {

        static ServerCapabilities none() {
            return new ServerCapabilities(false, false, false, false, false, false, false, false,
                    false, false, false, false, false, false, false, false, false, false,
                    Set.of(), Set.of());
        }
    }

    public record JavaCodeLens(Range range, String title, String command,
                               List<Location> locations, Status status) {
        public JavaCodeLens(Range range, String title, String command, List<Location> locations) {
            this(range, title, command, locations, Status.COMPLETE);
        }
        public JavaCodeLens {
            range = range == null ? Range.point(0, 0) : range;
            title = title == null ? "" : title;
            command = command == null ? "" : command;
            locations = JavaNavigation.unique(locations);
        }
    }

    private record SymbolCache(String text, List<DocumentSymbol> symbols) { }

    private static final class LensWork {
        final String text;
        final long revision;
        final int version;
        volatile List<JavaCodeLens> lenses = List.of();
        final Set<CompletableFuture<JsonNode>> pending = ConcurrentHashMap.newKeySet();
        LensWork(String text, long revision, int version) {
            this.text = text; this.revision = revision; this.version = version;
        }
        void cancel() { pending.forEach(f -> f.cancel(false)); }
    }

    private record CompletionCache(String text, int line, int col,
                                   List<AutoCompleteItem> items) {
    }

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        return isReady();
    }

    public boolean isInteractive() {
        State current = state;
        return isInteractiveState(current)
                && client != null && !client.isClosed();
    }

    static boolean isInteractiveState(State state) {
        return state == State.INDEXING || state == State.READY;
    }

    public boolean isReady() {
        return state == State.READY && client != null && !client.isClosed();
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

    public Path getLombokAgentJar() {
        return lombokAgentJar;
    }

    public void setMaxHeap(String value) {
        this.maxHeap = value == null || value.isBlank() ? "2G" : value.trim();
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

    public void setCodeLensRefreshListener(Consumer<Path> listener) {
        this.onCodeLensRefresh = listener == null ? path -> {
        } : listener;
    }

    public void setDocumentUpgradeListener(Consumer<Path> listener) {
        this.onDocumentUpgrade = listener == null ? path -> {
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
        synchronized (processLock) {
            if (state == State.STARTING || state == State.INDEXING || isRunning()) {
                return;
            }
            state = State.STARTING;
            lastError = null;
            startupFailure = null;
            projectRoot = root.toAbsolutePath().normalize();
            readyLatch = new CountDownLatch(1);
            serviceReadyLatch = new CountDownLatch(1);
        }

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
            Path workspace = provisioner.workspaceFor(root);
            removeLegacyOverlappingWorkspace(root);
            Files.createDirectories(workspace);
            stopOrphanedWorkspaceServers(workspace);

            List<String> command = buildCommand(runtime, installation, workspace);
            log.info("Iniciando o Eclipse JDT LS {} com JDK do servidor {} e JDK do projeto {} em {}",
                    JdtLsProvisioner.DEFAULT_VERSION, runtime.fullVersion(),
                    jdk == null ? "padrao" : jdk.fullVersion(), root);

            Process started = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            synchronized (processLock) {
                process = started;
                client = new LspJsonRpcClient(started.getInputStream(), started.getOutputStream(),
                        "jdtls-rpc");
            }
            pumpStderr(started);
            registerHandlers();

            initialize(root, runtime, jdk);
            state = State.INDEXING;
            statusListener.onStatus("Java: indexando projeto...", -1);
            flushOpenDocuments();
            drainPendingWatchedFiles();
            provisionBundlesInBackground(progress);
            awaitWorkspaceReady();
            if (startupFailure != null) {
                throw new IllegalStateException(startupFailure);
            }
            if (state == State.STOPPED) {
                return;
            }
            state = State.READY;
            warmUpDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_UP_TIMEOUT_MS);
            warmUpWorkStarted.set(false);
            warmingUp.set(true);
            CompletableFuture.runAsync(this::finishWarmUp, CompletableFuture.delayedExecutor(
                    WARM_UP_TIMEOUT_MS, TimeUnit.MILLISECONDS, executor));
            flushOpenDocuments();
            refreshOpenDocuments();
            drainPendingWatchedFiles();
            readyLatch.countDown();
            statusListener.onStatus("Java: IntelliSense pronto", -1);
        } catch (Exception e) {
            if (state == State.STOPPED) {
                readyLatch.countDown();
                return;
            }
            state = State.ERROR;
            lastError = rootMessage(e);
            readyLatch.countDown();
            log.warn("Falha ao iniciar o Eclipse JDT LS", e);
            statusListener.onStatus("Java: IntelliSense indisponivel - " + lastError, -1);
            stopProcess();
        }
    }

    private void awaitWorkspaceReady() throws Exception {
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            throw new IllegalStateException("Cliente LSP indisponivel durante a indexacao");
        }
        CountDownLatch ready = serviceReadyLatch;
        CompletableFuture<JsonNode> projects = rpc.request("workspace/executeCommand", Map.of(
                "command", "java.project.getAll",
                "arguments", List.of()));
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(SERVICE_READY_TIMEOUT_MS);
        try {
            while (System.nanoTime() < deadline) {
                if (ready.await(SERVICE_READY_POLL_MS, TimeUnit.MILLISECONDS)) {
                    projects.cancel(false);
                    return;
                }
                if (projects.isDone()) {
                    projects.get();
                    return;
                }
                if (state == State.STOPPED) {
                    projects.cancel(false);
                    return;
                }
            }
            projects.cancel(false);
            throw new TimeoutException("A indexacao do projeto excedeu o tempo limite");
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

    private void stopOrphanedWorkspaceServers(Path workspace) {
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
        command.add("-Djava.import.generatesMetadataFilesAtProjectRoot=false");
        command.add("-Xms256m");
        command.add("-Xmx" + maxHeap);
        Path lombok = lombokAgentJar;
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

    public synchronized void stop() {
        synchronized (processLock) {
            state = State.STOPPED;
        }
        warmingUp.set(false);
        clearNavigationCache(null);
        serviceReadyLatch.countDown();
        readyLatch.countDown();
        LspJsonRpcClient rpc = client;
        if (rpc != null && !rpc.isClosed()) {
            try {
                rpc.request("shutdown", Map.of()).get(2, TimeUnit.SECONDS);
                rpc.notify("exit", Map.of());
                // notify is queued on the writer. Give exit time to reach the server
                // and save its workspace before the process-tree fallback below.
                Process running = process;
                if (running != null) running.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("JDT LS nao confirmou o encerramento dentro do prazo", error);
            }
        }
        stopProcess();
    }

    /** Clears document state when the adapter moves to another project. */
    public void resetProjectState() {
        documents.clear();
        clearNavigationCache(null);
    }

    /** Permanently releases this service. Unlike {@link #stop()}, it cannot be restarted. */
    public void shutdown() {
        stop();
        resetProjectState();
        executor.shutdownNow();
    }

    private void stopProcess() {
        LspJsonRpcClient rpc;
        Process running;
        synchronized (processLock) {
            rpc = client;
            running = process;
            client = null;
            process = null;
        }
        // A Windows pipe read can hold the stream lock indefinitely. End the
        // producer before closing its streams so the RPC reader receives EOF.
        if (running != null) {
            terminateProcessTree(running.toHandle());
        }
        if (rpc != null) {
            rpc.close();
        }
        documents.clearSyncState();
        watchedFiles.clear();
        watchedFlushTicket.incrementAndGet();
        resyncTicket.incrementAndGet();
        diagnosticsByPath.clear();
        rawDiagnosticsByPath.clear();
        symbolCache.clear();
        codeLensCache.values().forEach(LensWork::cancel);
        codeLensCache.clear();
        completionCache.clear();
        inFlightRequests.clear();
        workspaceWorkTokens.clear();
        capabilities = ServerCapabilities.none();
    }

    private static void terminateProcessTree(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) {
            return;
        }
        try (var descendants = handle.descendants()) {
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        }
        handle.destroy();
        try {
            handle.onExit().get(3, TimeUnit.SECONDS);
            return;
        } catch (TimeoutException ignored) {
            // Escala abaixo para encerramento forcado.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            log.debug("Falha ao aguardar encerramento normal do processo JDT LS", error);
        }
        try (var descendants = handle.descendants()) {
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
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

    private void initialize(Path root, JdkInstallation runtime,
                            JdkInstallation preferredProjectJdk) throws Exception {
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            throw new IllegalStateException("Cliente LSP indisponivel");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", LspConversions.toUri(root));
        params.put("workspaceFolders", List.of(Map.of(
                "uri", LspConversions.toUri(root),
                "name", root.getFileName() == null ? "workspace" : root.getFileName().toString())));
        params.put("capabilities", LspClientCapabilities.build(TOKEN_TYPES, TOKEN_MODIFIERS));
        JdkInstallation configuredJdk = preferredProjectJdk == null ? runtime : preferredProjectJdk;
        effectiveSettings = JdtLsSettings.build(configuredJdk, jdkService.available(), buildMode);
        params.put("initializationOptions", initializationOptions(effectiveSettings));

        JsonNode result = rpc.request("initialize", params)
                .get(INITIALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        capabilities = LspClientCapabilities.readServerCapabilities(result);
        rpc.notify("initialized", Map.of());
        rpc.notify("workspace/didChangeConfiguration",
                Map.of("settings", effectiveSettings));
    }

    private Map<String, Object> initializationOptions(Map<String, Object> settings) {
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

        List<String> bundlePaths = bundles == null ? List.of()
                : bundles.resolveBundlePaths(springSupport);
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
        return debugBundleLoaded && isInteractive();
    }

    public int startDebugSession() {
        JsonNode result = requestInteractive("workspace/executeCommand", Map.of(
                "command", "vscode.java.startDebugSession",
                "arguments", List.of()), 30_000);
        if (result == null || !result.canConvertToInt()) {
            return -1;
        }
        return result.asInt(-1);
    }

    public JsonNode findTestTypesAndMethods(Path file) {
        if (file == null) {
            return null;
        }
        return requestInteractive("workspace/executeCommand", Map.of(
                "command", "vscode.java.test.findTestTypesAndMethods",
                "arguments", List.of(LspConversions.toUri(file))), 15_000);
    }

    public boolean isTestRunnerAvailable() {
        return testBundleLoaded && isInteractive();
    }

    public boolean updateProjectConfiguration(Path projectRoot) {
        if (projectRoot == null || !isInteractive()) {
            return false;
        }
        LspJsonRpcClient rpc = client;
        if (rpc == null) {
            return false;
        }
        clearNavigationCache(null);
        rpc.notify("java/projectConfigurationUpdate",
                Map.of("uri", LspConversions.toUri(projectRoot)));
        return true;
    }

    public String buildWorkspace(boolean fullBuild) {
        return buildWorkspace(fullBuild, null);
    }

    public String buildWorkspace(boolean fullBuild, StatusListener progress) {
        workspaceBuildProgress = progress;
        try {
            JsonNode result = requestInteractive("java/buildWorkspace", fullBuild, 120_000);
            return result == null || result.isNull() ? "FAILED" : result.asText("FAILED");
        } finally {
            workspaceBuildProgress = null;
        }
    }

    public java.util.Optional<String> runtimeClasspath(Path projectOrSource) {
        if (projectOrSource == null || !isInteractive()) {
            return java.util.Optional.empty();
        }
        JsonNode result = requestInteractive("workspace/executeCommand", Map.of(
                "command", "java.project.getClasspaths",
                "arguments", runtimeClasspathArguments(projectOrSource)), 30_000);
        if (result == null || result.isNull()) {
            return java.util.Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : result.path("classpaths")) {
            if (!value.asText("").isBlank()) {
                entries.add(value.asText());
            }
        }
        for (JsonNode value : result.path("modulepaths")) {
            if (!value.asText("").isBlank()) {
                entries.add(value.asText());
            }
        }
        return entries.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(String.join(java.io.File.pathSeparator, entries));
    }

    static List<String> runtimeClasspathArguments(Path projectOrSource) {
        String options = JSON.createObjectNode().put("scope", "runtime").toString();
        return List.of(LspConversions.toUri(projectOrSource), options);
    }

    private void registerHandlers() {
        LspJsonRpcClient rpc = client;
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

    synchronized void onPublishDiagnostics(JsonNode params) {
        if (params == null) {
            return;
        }
        String uri = params.path("uri").asText("");
        if (uri.isBlank()) {
            return;
        }
        JsonNode publishedVersion = params.get("version");
        int currentVersion = documents.version(uri);
        if (publishedVersion != null && publishedVersion.isIntegralNumber()
                && currentVersion != ANY_VERSION
                && publishedVersion.asInt() != currentVersion) {
            log.debug("Diagnosticos descartados para {}: versao {} recebida, {} atual",
                    uri, publishedVersion.asInt(), currentVersion);
            return;
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<JsonNode> rawDiagnostics = new ArrayList<>();
        JsonNode array = params.get("diagnostics");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                rawDiagnostics.add(node.deepCopy());
                Diagnostic diagnostic = LspConversions.diagnostic(node);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
        }
        Path path = LspConversions.toPath(uri);
        if (path != null) {
            Path key = normalizePath(path);
            String content = documents.content(uri);
            diagnosticsByPath.put(key, content == null
                    ? List.copyOf(diagnostics)
                    : DiagnosticRanges.compactMultiline(diagnostics, content));
            rawDiagnosticsByPath.put(key, List.copyOf(rawDiagnostics));
            onDiagnosticsPublished.accept(path);
        }
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
            statusListener.onStatus("Java: " + message, -1);
        }
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
        if (!text.isBlank()) {
            statusListener.onStatus("Java: " + text, percent);
            StatusListener buildProgress = workspaceBuildProgress;
            if (buildProgress != null) {
                buildProgress.onStatus(text, percent);
            }
        }
        String kind = value.path("kind").asText("");
        String token = params.path("token").asText();
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
            else refreshCodeLensesAfterWork();
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
            documentContentChanged(filePath, uri);
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
        documentContentChanged(filePath, uri);

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
        boolean wasSynced = documents.unmarkSynced(uri);
        documents.remove(uri);
        diagnosticsByPath.remove(normalizePath(filePath));
        rawDiagnosticsByPath.remove(normalizePath(filePath));
        symbolCache.remove(uri);
        discardCodeLenses(uri);
        completionCache.remove(uri);
        invalidateWorkspaceNavigation();
        cancelInFlightForUri(uri);

        LspJsonRpcClient rpc = client;
        if (wasSynced && rpc != null && canSyncDocuments()) {
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    /** Tells JDT LS that a source appeared on disk outside the editor. */
    public void pathCreated(Path createdPath) {
        queueWatchedFile(createdPath, WatchedFileBatch.CREATED);
    }

    /** Tells JDT LS that a source changed on disk outside the editor. */
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

    private void queueWatchedFile(Path path, int changeType) {
        if (path == null) {
            return;
        }
        Path target = normalizePath(path);
        String uri = LspConversions.toUri(target);
        symbolCache.remove(uri);
        discardCodeLenses(uri);
        completionCache.remove(uri);
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
            if (!changes.isEmpty()) {
                rpc.notify("workspace/didChangeWatchedFiles", Map.of("changes", changes));
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

    /** Asks JDT LS to reread the build files of the project. */
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

    /** Tells JDT LS that a source disappeared, including files moved to Orion's trash. */
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

        List<Path> diagnosedBelowDeleted = diagnosticsByPath.keySet().stream()
                .filter(path -> path.startsWith(deleted)).toList();
        diagnosedBelowDeleted.forEach(path -> {
            diagnosticsByPath.remove(path);
            rawDiagnosticsByPath.remove(path);
            onDiagnosticsPublished.accept(path);
        });

        queueWatchedFile(deleted, WatchedFileBatch.DELETED);
        projectConfigurationUpdate();
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
        State current = state;
        LspJsonRpcClient rpc = client;
        return (current == State.INDEXING || current == State.READY)
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
            cancelInFlightForUri(uri);
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
            symbolCache.remove(uri);
            discardCodeLenses(uri);
            completionCache.remove(uri);
            Path path = LspConversions.toPath(uri);
            if (path == null) {
                return;
            }
            if (clearDiagnostics) {
                diagnosticsByPath.remove(normalizePath(path));
                rawDiagnosticsByPath.remove(normalizePath(path));
                onDiagnosticsPublished.accept(path);
            }
            onCodeLensRefresh.accept(path);
        });
        if (statusMessage != null) {
            statusListener.onStatus(statusMessage, -1);
        }
    }

    private void refreshOpenDocuments() {
        documents.uris().forEach(uri -> {
            symbolCache.remove(uri);
            discardCodeLenses(uri);
            completionCache.remove(uri);
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

    private void discardCodeLenses(String uri) {
        LensWork old = codeLensCache.remove(uri);
        if (old != null) old.cancel();
    }

    private void invalidateWorkspaceNavigation() {
        workspaceRevision.incrementAndGet();
        navigationCache.clear();
        codeLensCache.values().forEach(LensWork::cancel);
        codeLensCache.clear();
        documents.uris().forEach(uri -> {
            Path path = LspConversions.toPath(uri);
            if (path != null) onCodeLensRefresh.accept(path);
        });
    }

    private void documentContentChanged(Path filePath, String uri) {
        symbolCache.remove(uri);
        discardCodeLenses(uri);
        completionCache.remove(uri);
        invalidateWorkspaceNavigation();
        cancelInFlightForUri(uri);
        Path key = normalizePath(filePath);
        boolean hadDiagnostics = diagnosticsByPath.remove(key) != null;
        hadDiagnostics |= rawDiagnosticsByPath.remove(key) != null;
        if (hadDiagnostics) {
            onDiagnosticsPublished.accept(filePath);
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
        return complete(filePath, text, line, col, CompletionTrigger.INVOKED, null, ANY_VERSION);
    }

    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int col,
                                           CompletionTrigger trigger, Character triggerCharacter,
                                           int expectedVersion) {
        if (!isInteractive()) {
            return List.of();
        }
        String uri = LspConversions.toUri(filePath);
        String requestedText = text == null ? "" : text;
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        CompletionCache cached = completionCache.get(uri);
        if (cached != null && cached.text().equals(requestedText)
                && cached.line() == line && cached.col() == col) {
            return cached.items();
        }
        int version = documents.version(uri);
        if (isStaleVersion(expectedVersion, version)) {
            log.debug("Completion descartada antes do envio: versao {} esperada, {} atual",
                    expectedVersion, version);
            return List.of();
        }
        CompletionTrigger kind = trigger == null ? CompletionTrigger.INVOKED : trigger;
        long timeout = isReady() ? READY_COMPLETION_TIMEOUT_MS : INDEXING_COMPLETION_TIMEOUT_MS;
        long started = System.nanoTime();
        JsonNode result = requestCoalescedInteractive("textDocument/completion",
                completionParams(filePath, line, col, kind, triggerCharacter), timeout,
                "completion|" + uri + "|" + version + "|" + line + "|" + col + "|" + kind);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (result == null) {
            log.debug("Completion sem resposta em {} ms (limite {} ms, acionamento {})",
                    elapsedMs, timeout, kind);
            return List.of();
        }
        JsonNode items = result.isArray() ? result : result.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<AutoCompleteItem> completions = new ArrayList<>(
                Math.min(items.size(), MAX_COMPLETION_ITEMS));
        for (JsonNode node : items) {
            if (completions.size() >= MAX_COMPLETION_ITEMS) {
                break;
            }
            if (node == null || node.path("label").asText("").isBlank()) {
                continue;
            }
            AutoCompleteItem item = LspConversions.completionItem(node);
            if (item != null) completions.add(item);
        }
        int current = documents.version(uri);
        if (!requestedText.equals(documents.content(uri)) || current != version
                || isStaleVersion(expectedVersion, current)) {
            log.debug("Completion descartada: documento mudou durante a requisicao ({} ms)",
                    elapsedMs);
            return List.of();
        }
        List<AutoCompleteItem> answer = List.copyOf(completions);
        completionCache.put(uri, new CompletionCache(requestedText, line, col, answer));
        log.debug("Completion com {} item(ns) em {} ms (acionamento {})",
                answer.size(), elapsedMs, kind);
        return answer;
    }

    public int documentVersion(Path filePath) {
        if (filePath == null) {
            return ANY_VERSION;
        }
        return documents.version(LspConversions.toUri(filePath));
    }

    static boolean isStaleVersion(int expectedVersion, int currentVersion) {
        return expectedVersion != ANY_VERSION && expectedVersion != currentVersion;
    }

    private static Map<String, Object> completionParams(Path filePath, int line, int col,
                                                        CompletionTrigger trigger,
                                                        Character triggerCharacter) {
        Map<String, Object> params = positionParams(filePath, line, col);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("triggerKind", trigger.lspKind());
        if (trigger == CompletionTrigger.TRIGGER_CHARACTER && triggerCharacter != null) {
            context.put("triggerCharacter", String.valueOf(triggerCharacter.charValue()));
        }
        params.put("context", context);
        return params;
    }

    public List<AutoCompleteItem> cachedCompletions(Path filePath) {
        if (filePath == null) return List.of();
        CompletionCache cached = completionCache.get(LspConversions.toUri(filePath));
        return cached == null ? List.of() : cached.items();
    }

    public void warmCompletion(Path filePath, String text, int line, int col) {
        if (!isInteractive() || isReady() || filePath == null) return;
        executor.submit(() -> complete(filePath, text, line, col));
    }

    public HoverInfo hover(Path filePath, String text, int line, int col) {
        JsonNode result = requestAtInteractive("textDocument/hover", filePath, text, line, col);
        return isCurrentText(filePath, text) ? LspConversions.hover(result) : null;
    }

    public SignatureHelp signatureHelp(Path filePath, String text, int line, int col) {
        if (!capabilities.signatureHelp()) {
            return null;
        }
        return LspConversions.signatureHelp(
                requestAtInteractive("textDocument/signatureHelp", filePath, text, line, col));
    }

    public List<Location> definitions(Path filePath, String text, int line, int col) {
        return definitions(filePath, text, line, col, false);
    }

    public List<Location> definitionsInteractive(Path filePath, String text, int line, int col) {
        return definitions(filePath, text, line, col, true);
    }

    private List<Location> definitions(Path filePath, String text, int line, int col,
                                       boolean interactive) {
        if (!capabilities.definition()) {
            return List.of();
        }
        return navigate("textDocument/definition", filePath, text, line, col, interactive, null);
    }

    private List<Location> navigate(String method, Path filePath, String text, int line, int col,
                                    boolean interactive, Map<String, Object> extraParams) {
        return navigateResult(method, filePath, text, line, col, interactive, extraParams).locations();
    }

    public Result navigation(Kind kind, Path file, String text, int line, int col) {
        boolean supported = switch (kind) {
            case DEFINITION -> capabilities.definition();
            case IMPLEMENTATION -> capabilities.implementation();
            case REFERENCES -> capabilities.references();
        };
        if (!supported || !isInteractive()) return Result.of(Status.UNAVAILABLE);
        return navigateResult(kind.method(), file, text, line, col, true,
                kind == Kind.REFERENCES ? Map.of("context", Map.of("includeDeclaration", false)) : null,
                REQUEST_TIMEOUT_MS * 3);
    }

    private Result navigateResult(String method, Path filePath, String text, int line, int col,
                                  boolean interactive, Map<String, Object> extraParams) {
        return navigateResult(method, filePath, text, line, col, interactive, extraParams, REQUEST_TIMEOUT_MS);
    }

    private Result navigateResult(String method, Path filePath, String text, int line, int col,
                                  boolean interactive, Map<String, Object> extraParams, long timeoutMs) {
        if (filePath == null) return Result.of(Status.UNAVAILABLE);
        String uri = LspConversions.toUri(filePath);
        if (!syncBeforeRequest(filePath, text)) return Result.of(Status.STALE);
        int version = documents.version(uri);
        long revision = workspaceRevision.get();
        String key = method + "|" + uri + "|" + version + "|" + line + "|" + col + "|" + revision;
        List<Location> cached = navigationCache.get(key);
        if (cached != null && isReady() && !isWarmingUp() && workspaceWorkTokens.isEmpty()) {
            return new Result(Status.COMPLETE, cached);
        }
        Map<String, Object> params = positionParams(filePath, line, col);
        if (extraParams != null) params.putAll(extraParams);
        // Explicit navigation runs off the EDT and needs more time than hover/completion.
        JsonNode response = requestCoalesced(method, params, timeoutMs, key, interactive);
        if (documents.version(uri) != version || workspaceRevision.get() != revision) {
            return Result.of(Status.STALE);
        }
        boolean indexing = !isReady() || isWarmingUp() || !workspaceWorkTokens.isEmpty();
        if (response == null) return Result.of(indexing ? Status.INDEXING : Status.FAILED);
        List<Location> locations = JavaNavigation.unique(LspConversions.locations(response));
        if (indexing) return new Result(Status.INDEXING, locations);
        navigationCache.put(key, locations);
        return new Result(Status.COMPLETE, locations);
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
        return LspConversions.locations(requestInteractive(
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
        return LspConversions.hover(requestInteractive(
                "textDocument/hover", params, INTERACTIVE_TIMEOUT_MS));
    }

    public String classFileContents(String uri) {
        if (!JavaClassFileNavigation.isClassFileUri(uri)) {
            return null;
        }
        JsonNode result = requestInteractive(
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

    public List<Location> implementations(Path filePath, String text, int line, int col) {
        return implementations(filePath, text, line, col, false);
    }

    public List<Location> implementationsInteractive(Path filePath, String text, int line, int col) {
        return implementations(filePath, text, line, col, true);
    }

    private List<Location> implementations(Path filePath, String text, int line, int col,
                                           boolean interactive) {
        if (!capabilities.implementation()) {
            return List.of();
        }
        return navigate("textDocument/implementation", filePath, text, line, col,
                interactive, null);
    }

    public List<Location> typeDefinitions(Path filePath, String text, int line, int col) {
        return typeDefinitions(filePath, text, line, col, false);
    }

    public List<Location> typeDefinitionsInteractive(Path filePath, String text, int line, int col) {
        return typeDefinitions(filePath, text, line, col, true);
    }

    private List<Location> typeDefinitions(Path filePath, String text, int line, int col,
                                           boolean interactive) {
        if (!capabilities.typeDefinition()) {
            return List.of();
        }
        return navigate("textDocument/typeDefinition", filePath, text, line, col,
                interactive, null);
    }

    public List<Location> references(Path filePath, String text, int line, int col) {
        return references(filePath, text, line, col, false);
    }

    public List<Location> referencesInteractive(Path filePath, String text, int line, int col) {
        return references(filePath, text, line, col, true);
    }

    private List<Location> references(Path filePath, String text, int line, int col,
                                      boolean interactive) {
        if (!capabilities.references()) {
            return List.of();
        }
        return navigate("textDocument/references", filePath, text, line, col, interactive,
                Map.of("context", Map.of("includeDeclaration", false)));
    }

    public boolean supportsCallHierarchy() {
        return capabilities.callHierarchy();
    }

    public List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int col) {
        if (!capabilities.callHierarchy()) {
            return List.of();
        }
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        JsonNode result = request("textDocument/prepareCallHierarchy",
                positionParams(filePath, line, col), REQUEST_TIMEOUT_MS);
        return callHierarchyItems(result);
    }

    public List<CallHierarchyCall> incomingCalls(CallHierarchyItem item) {
        return calls("callHierarchy/incomingCalls", item, "from");
    }

    public List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item) {
        return calls("callHierarchy/outgoingCalls", item, "to");
    }

    private List<CallHierarchyCall> calls(String method, CallHierarchyItem item, String itemField) {
        if (!capabilities.callHierarchy() || item == null) {
            return List.of();
        }
        Map<String, Object> serialized = serializeCallHierarchyItem(item);
        if (serialized == null) {
            return List.of();
        }
        JsonNode result = request(method, Map.of("item", serialized), REQUEST_TIMEOUT_MS);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CallHierarchyCall> calls = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            CallHierarchyCall call = LspConversions.callHierarchyCall(node, itemField);
            if (call != null) {
                calls.add(call);
            }
        }
        return calls;
    }

    private List<CallHierarchyItem> callHierarchyItems(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CallHierarchyItem> items = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            CallHierarchyItem item = LspConversions.callHierarchyItem(node);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> serializeCallHierarchyItem(CallHierarchyItem item) {
        Map<String, Object> data = item.data();
        Object uri = data == null ? null : data.get("uri");
        if (uri == null) {
            uri = LspConversions.toUri(item.filePath());
        }
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("name", item.name());
        serialized.put("kind", LspConversions.toLspSymbolKind(item.kind()));
        serialized.put("uri", uri.toString());
        serialized.put("range", LspConversions.toLspRange(item.range()));
        serialized.put("selectionRange", LspConversions.toLspRange(item.selectionRange()));
        if (item.detail() != null && !item.detail().isBlank()) {
            serialized.put("detail", item.detail());
        }
        Object raw = data == null ? null : data.get("data");
        if (raw != null && !raw.toString().isBlank()) {
            try {
                serialized.put("data", JSON.readTree(raw.toString()));
            } catch (Exception e) {
                log.debug("Campo data da hierarquia ilegivel: {}", e.getMessage());
            }
        }
        return serialized;
    }

    public List<DocumentSymbol> documentSymbols(Path filePath, String text) {
        return documentSymbols(filePath, text, false);
    }

    public List<DocumentSymbol> documentSymbolsInteractive(Path filePath, String text) {
        return documentSymbols(filePath, text, true);
    }

    private List<DocumentSymbol> documentSymbols(Path filePath, String text, boolean interactive) {
        if (!capabilities.documentSymbol()) {
            return List.of();
        }
        String uri = LspConversions.toUri(filePath);
        String requestedText = text == null ? "" : text;
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        SymbolCache cached = symbolCache.get(uri);
        if (cached != null && cached.text().equals(requestedText)) {
            return cached.symbols();
        }
        int version = documents.version(uri);
        String key = "symbols|" + uri + "|" + version;
        JsonNode result = interactive
                ? requestCoalescedInteractive("textDocument/documentSymbol",
                        Map.of("textDocument", documentId(filePath)), INTERACTIVE_TIMEOUT_MS, key)
                : requestCoalescedBackground("textDocument/documentSymbol",
                        Map.of("textDocument", documentId(filePath)), REQUEST_TIMEOUT_MS, key);
        List<DocumentSymbol> symbols = List.copyOf(LspConversions.documentSymbols(result));
        if (requestedText.equals(documents.content(uri))) {
            symbolCache.put(uri, new SymbolCache(requestedText, symbols));
            return symbols;
        }
        return List.of();
    }

    public List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col) {
        return documentHighlights(filePath, text, line, col, false);
    }

    public List<DocumentHighlight> documentHighlightsInteractive(Path filePath, String text,
                                                                 int line, int col) {
        return documentHighlights(filePath, text, line, col, true);
    }

    private List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int col,
                                                       boolean interactive) {
        if (!capabilities.documentHighlight()) {
            return List.of();
        }
        JsonNode result = interactive
                ? requestAtInteractive("textDocument/documentHighlight", filePath, text, line, col)
                : requestAt("textDocument/documentHighlight", filePath, text, line, col);
        if (result == null || !result.isArray() || !isCurrentText(filePath, text)) {
            return List.of();
        }
        List<DocumentHighlight> highlights = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            Range range = LspConversions.range(node.get("range"));
            DocumentHighlight.Kind kind = switch (node.path("kind").asInt(1)) {
                case 2 -> DocumentHighlight.Kind.READ;
                case 3 -> DocumentHighlight.Kind.WRITE;
                default -> DocumentHighlight.Kind.TEXT;
            };
            highlights.add(new DocumentHighlight(range, kind));
        }
        return highlights;
    }

    public List<TextEdit> rename(Path filePath, String text, int line, int col, String newName) {
        if (!capabilities.rename()) {
            return List.of();
        }
        Map<String, Object> params = positionParams(filePath, line, col);
        params.put("newName", newName);
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        JsonNode result = request("textDocument/rename", params, REQUEST_TIMEOUT_MS);
        return LspConversions.singleDocumentEdits(result);
    }

    public List<CodeAction> codeActions(Path filePath, String text, Range range,
                                        List<Diagnostic> diagnostics) {
        if (!capabilities.codeAction()) {
            return List.of();
        }
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("range", rangeParam(range));
        params.put("context", Map.of("diagnostics",
                rawDiagnosticsByPath.getOrDefault(normalizePath(filePath), List.of())));

        JsonNode result = requestInteractive("textDocument/codeAction", params,
                INTERACTIVE_TIMEOUT_MS);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<CodeAction> actions = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            JsonNode resolved = node;
            if (!node.hasNonNull("edit") && !node.hasNonNull("command")
                    && node.hasNonNull("data")) {
                JsonNode answer = requestInteractive("codeAction/resolve", node,
                        INTERACTIVE_TIMEOUT_MS);
                if (answer != null && !answer.isNull()) {
                    resolved = answer;
                }
            }
            CodeAction action = LspConversions.codeAction(resolved, APPLY_CODE_ACTION_COMMAND);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    public List<SourceAction> sourceActions(Path filePath, String text, int line, int col) {
        if (!capabilities.codeAction()) {
            return List.of();
        }
        Map<String, Object> params = sourceActionParams(filePath, text, line, col);
        if (params == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) params.get("context");
        Map<String, Object> filteredContext = new LinkedHashMap<>(context);
        filteredContext.put("only", List.of("source"));
        params.put("context", filteredContext);
        JsonNode result = request("textDocument/codeAction", params, REQUEST_TIMEOUT_MS * 2);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        Map<String, SourceAction> unique = new LinkedHashMap<>();
        for (JsonNode action : result) {
            JsonNode command = action.path("command");
            if (command.isTextual()) {
                command = action;
            }
            String id = command.path("command").asText("");
            if (!isSourcePrompt(id)) {
                continue;
            }
            String title = action.path("title").asText(command.path("title").asText(id));
            unique.putIfAbsent(id, new SourceAction(title, id));
        }
        return List.copyOf(unique.values());
    }

    public OverrideStatus overridableMethods(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/listOverridableMethods", filePath, text, line, col, null);
        return parseOverrideStatus(result);
    }

    static OverrideStatus parseOverrideStatus(JsonNode result) {
        if (result == null || result.isNull()) return new OverrideStatus("", List.of());
        List<SourceItem> methods = new ArrayList<>();
        for (JsonNode method : result.path("methods")) {
            String signature = method.path("name").asText("") + "("
                    + joinText(method.path("parameters")) + ")";
            String detail = method.path("declaringClassType").asText("");
            String declaring = method.path("declaringClass").asText("");
            if (!declaring.isBlank()) {
                detail = detail.isBlank() ? declaring : detail + ": " + declaring;
            }
            methods.add(new SourceItem(method, signature, detail,
                    method.path("unimplemented").asBoolean(false)));
        }
        return new OverrideStatus(result.path("type").asText(""), List.copyOf(methods));
    }

    public List<TextEdit> generateOverridableMethods(Path filePath, String text, int line, int col,
                                                       List<SourceItem> methods) {
        return generatedEdits("java/addOverridableMethods", filePath, text, line, col,
                Map.of("overridableMethods", rawValues(methods)));
    }

    public ConstructorsStatus constructorsStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkConstructorsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new ConstructorsStatus(List.of(), List.of());
        }
        return new ConstructorsStatus(methodItems(result.path("constructors"), false),
                variableItems(result.path("fields")));
    }

    public List<TextEdit> generateConstructors(Path filePath, String text, int line, int col,
                                                List<SourceItem> constructors,
                                                List<SourceItem> fields) {
        return generatedEdits("java/generateConstructors", filePath, text, line, col,
                Map.of("constructors", rawValues(constructors), "fields", rawValues(fields)));
    }

    public List<SourceItem> accessorsStatus(Path filePath, String text, int line, int col) {
        Map<String, Object> extraContext = Map.of("kind", 2);
        JsonNode result = sourceRequest("java/resolveUnimplementedAccessors",
                filePath, text, line, col, extraContext);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode accessor : result) {
            List<String> kinds = new ArrayList<>(2);
            if (accessor.path("generateGetter").asBoolean()) kinds.add("getter");
            if (accessor.path("generateSetter").asBoolean()) kinds.add("setter");
            String detail = (accessor.path("isStatic").asBoolean() ? "static " : "")
                    + String.join(", ", kinds);
            items.add(new SourceItem(accessor,
                    accessor.path("fieldName").asText("") + ": "
                            + accessor.path("typeName").asText(""), detail, true));
        }
        return List.copyOf(items);
    }

    public List<TextEdit> generateAccessors(Path filePath, String text, int line, int col,
                                             List<SourceItem> accessors) {
        return generatedEdits("java/generateAccessors", filePath, text, line, col,
                Map.of("accessors", rawValues(accessors), "contextExtra", Map.of("kind", 2)));
    }

    public FieldsStatus hashCodeEqualsStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkHashCodeEqualsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new FieldsStatus("", List.of(), List.of(), false);
        }
        List<String> existing = new ArrayList<>();
        result.path("existingMethods").forEach(node -> existing.add(node.asText()));
        return new FieldsStatus(result.path("type").asText(""), variableItems(result.path("fields")),
                List.copyOf(existing), !existing.isEmpty());
    }

    public List<TextEdit> generateHashCodeEquals(Path filePath, String text, int line, int col,
                                                  List<SourceItem> fields, boolean regenerate) {
        return generatedEdits("java/generateHashCodeEquals", filePath, text, line, col,
                Map.of("fields", rawValues(fields), "regenerate", regenerate));
    }

    public FieldsStatus toStringStatus(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkToStringStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return new FieldsStatus("", List.of(), List.of(), false);
        }
        return new FieldsStatus(result.path("type").asText(""), variableItems(result.path("fields")),
                List.of(), result.path("exists").asBoolean(false));
    }

    public List<TextEdit> generateToString(Path filePath, String text, int line, int col,
                                            List<SourceItem> fields) {
        return generatedEdits("java/generateToString", filePath, text, line, col,
                Map.of("fields", rawValues(fields)));
    }

    public List<DelegateTarget> delegateTargets(Path filePath, String text, int line, int col) {
        JsonNode result = sourceRequest("java/checkDelegateMethodsStatus", filePath, text, line, col, null);
        if (result == null || result.isNull()) {
            return List.of();
        }
        List<DelegateTarget> targets = new ArrayList<>();
        for (JsonNode delegate : result.path("delegateFields")) {
            JsonNode field = delegate.path("field");
            String label = field.path("name").asText("") + ": " + field.path("type").asText("");
            targets.add(new DelegateTarget(field, label,
                    methodItems(delegate.path("delegateMethods"), true)));
        }
        return List.copyOf(targets);
    }

    public List<TextEdit> generateDelegateMethods(Path filePath, String text, int line, int col,
                                                   DelegateTarget target, List<SourceItem> methods) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SourceItem method : methods) {
            entries.add(Map.of("field", target.field(), "delegateMethod", method.value()));
        }
        return generatedEdits("java/generateDelegateMethods", filePath, text, line, col,
                Map.of("delegateEntries", entries));
    }

    public String applyTextEdits(String text, List<TextEdit> edits) {
        return TextEditApplier.apply(text, edits);
    }

    private List<TextEdit> generatedEdits(String method, Path filePath, String text, int line, int col,
                                           Map<String, Object> values) {
        Map<String, Object> extra = new LinkedHashMap<>(values);
        @SuppressWarnings("unchecked")
        Map<String, Object> contextExtra = (Map<String, Object>) extra.remove("contextExtra");
        Map<String, Object> params = new LinkedHashMap<>(extra);
        Map<String, Object> context = sourceActionParams(filePath, text, line, col);
        if (context == null) {
            return List.of();
        }
        if (contextExtra != null) {
            context.putAll(contextExtra);
        }
        params.put("context", context);
        JsonNode result = request(method, params, REQUEST_TIMEOUT_MS * 2);
        return LspConversions.singleDocumentEdits(result);
    }

    private JsonNode sourceRequest(String method, Path filePath, String text, int line, int col,
                                   Map<String, Object> paramsExtra) {
        Map<String, Object> params = sourceActionParams(filePath, text, line, col);
        if (params == null) {
            return null;
        }
        if (paramsExtra != null) {
            params.putAll(paramsExtra);
        }
        return request(method, params, REQUEST_TIMEOUT_MS * 2);
    }

    private Map<String, Object> sourceActionParams(Path filePath, String text, int line, int col) {
        if (!syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        Map<String, Object> position = Map.of("line", Math.max(0, line),
                "character", Math.max(0, col));
        params.put("range", Map.of("start", position, "end", position));
        params.put("context", Map.of("diagnostics", List.of()));
        return params;
    }

    static List<SourceItem> variableItems(JsonNode values) {
        if (values == null || !values.isArray()) return List.of();
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode field : values) {
            items.add(new SourceItem(field, field.path("name").asText("") + ": "
                    + field.path("type").asText(""), "",
                    field.path("isSelected").asBoolean(true)));
        }
        return List.copyOf(items);
    }

    static List<SourceItem> methodItems(JsonNode values, boolean selected) {
        if (values == null || !values.isArray()) return List.of();
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode method : values) {
            items.add(new SourceItem(method, method.path("name").asText("") + "("
                    + joinText(method.path("parameters")) + ")", "", selected));
        }
        return List.copyOf(items);
    }

    private static String joinText(JsonNode values) {
        if (values == null || !values.isArray()) return "";
        List<String> parts = new ArrayList<>();
        values.forEach(node -> parts.add(node.asText("")));
        return String.join(", ", parts);
    }

    static List<JsonNode> rawValues(List<SourceItem> items) {
        if (items == null) return List.of();
        return items.stream().map(SourceItem::value).toList();
    }

    static boolean isSourcePrompt(String id) {
        return OVERRIDE_METHODS_PROMPT.equals(id)
                || HASHCODE_EQUALS_PROMPT.equals(id)
                || GENERATE_TOSTRING_PROMPT.equals(id)
                || GENERATE_ACCESSORS_PROMPT.equals(id)
                || GENERATE_CONSTRUCTORS_PROMPT.equals(id)
                || GENERATE_DELEGATE_METHODS_PROMPT.equals(id);
    }

    public void executeCodeAction(String rawJson) {
        LspJsonRpcClient rpc = client;
        if (rpc == null || !isInteractive() || rawJson == null || rawJson.isBlank()) {
            return;
        }
        try {
            JsonNode action = JSON.readTree(rawJson);
            JsonNode command = action.path("command");
            if (command.isTextual()) {
                command = action;
            }
            String id = command.path("command").asText("");
            if (id.isBlank()) {
                return;
            }
            List<Object> arguments = command.hasNonNull("arguments")
                    ? JSON.convertValue(command.get("arguments"), List.class) : List.of();
            requestInteractive("workspace/executeCommand",
                    Map.of("command", id, "arguments", arguments), REQUEST_TIMEOUT_MS * 2);
        } catch (Exception e) {
            log.debug("Falha ao executar code action Java: {}", rootMessage(e));
        }
    }

    public List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine) {
        if (!capabilities.inlayHint()) {
            return List.of();
        }
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        Map<String, Object> params = Map.of(
                "textDocument", documentId(filePath),
                "range", Map.of(
                        "start", Map.of("line", Math.max(0, firstLine), "character", 0),
                        "end", Map.of("line", Math.max(0, lastLine), "character", 0)));

        JsonNode result = requestBackground("textDocument/inlayHint", params, REQUEST_TIMEOUT_MS);
        if (result == null || !result.isArray() || !isCurrentText(filePath, text)) {
            return List.of();
        }
        List<InlayHint> hints = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            InlayHint hint = LspConversions.inlayHint(node);
            if (hint != null) {
                hints.add(hint);
            }
        }
        return hints;
    }

    public List<JavaCodeLens> codeLenses(Path filePath, String text) {
        if (!capabilities.codeLens() || !isReady() || isWarmingUp()
                || !syncBeforeRequest(filePath, text)) return List.of();
        String uri = LspConversions.toUri(filePath);
        String snapshot = text == null ? "" : text;
        long revision = workspaceRevision.get();
        int version = documents.version(uri);
        AtomicBoolean created = new AtomicBoolean();
        LensWork work = codeLensCache.compute(uri, (key, previous) -> {
            if (previous != null && previous.text.equals(snapshot) && previous.revision == revision
                    && previous.version == version) return previous;
            if (previous != null) previous.cancel();
            created.set(true);
            return new LensWork(snapshot, revision, version);
        });
        if (created.get()) executor.execute(() -> resolveCodeLenses(filePath, uri, work));
        return work.lenses;
    }

    private boolean currentLensWork(String uri, LensWork work) {
        return codeLensCache.get(uri) == work && workspaceRevision.get() == work.revision
                && documents.version(uri) == work.version && work.text.equals(documents.content(uri)) && isReady();
    }

    private JavaCodeLens unresolvedLens(JsonNode node, Status status) {
        JsonNode data = node.path("data");
        String type = data.isArray() && data.size() > 2 ? data.get(2).asText() : "";
        String command = switch (type) {
            case "references" -> "java.show.references";
            case "implementations" -> "java.show.implementations";
            default -> "";
        };
        return new JavaCodeLens(LspConversions.range(node.get("range")), "", command, List.of(), status);
    }

    private void resolveCodeLenses(Path file, String uri, LensWork work) {
        try {
            JsonNode response = requestBackground("textDocument/codeLens",
                    Map.of("textDocument", Map.of("uri", uri)), REQUEST_TIMEOUT_MS * 2);
            if (!currentLensWork(uri, work)) return;
            if (response == null || !response.isArray()) {
                codeLensCache.remove(uri, work);
                return;
            }
            List<JsonNode> raw = new ArrayList<>();
            response.forEach(raw::add);
            List<JavaCodeLens> resolved = new ArrayList<>();
            for (JsonNode node : raw) {
                JavaCodeLens lens = LspConversions.codeLens(node);
                resolved.add(lens == null ? unresolvedLens(node, Status.INDEXING) : lens);
            }
            publishLenses(file, uri, work, resolved);
            // Each pass advances through the entire document. A slow lens cannot starve later batches.
            for (int attempt = 0; attempt <= MAX_CODE_LENS_RETRIES && currentLensWork(uri, work); attempt++) {
                List<Integer> remaining = new ArrayList<>();
                for (int i = 0; i < resolved.size(); i++) {
                    if (resolved.get(i).status() != Status.COMPLETE) remaining.add(i);
                }
                if (remaining.isEmpty()) break;
                for (int start = 0; start < remaining.size() && currentLensWork(uri, work); start += MAX_CODE_LENS_RESOLVE) {
                    Map<Integer, CompletableFuture<JsonNode>> batch = new LinkedHashMap<>();
                    LspJsonRpcClient rpc = client;
                    if (rpc == null) return;
                    for (int i = start; i < Math.min(start + MAX_CODE_LENS_RESOLVE, remaining.size()); i++) {
                        int index = remaining.get(i);
                        CompletableFuture<JsonNode> future = rpc.request("codeLens/resolve", raw.get(index));
                        batch.put(index, future); work.pending.add(future);
                    }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CODE_LENS_RETRY_TIMEOUT_MS);
                    for (var entry : batch.entrySet()) {
                        CompletableFuture<JsonNode> future = entry.getValue();
                        try {
                            JsonNode answer = future.isDone() ? future.getNow(null)
                                    : future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                            JavaCodeLens lens = LspConversions.codeLens(answer);
                            resolved.set(entry.getKey(), lens == null
                                    ? unresolvedLens(raw.get(entry.getKey()), Status.FAILED) : lens);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); return;
                        } catch (Exception failure) {
                            resolved.set(entry.getKey(), unresolvedLens(raw.get(entry.getKey()), Status.FAILED));
                            future.cancel(false);
                        } finally { work.pending.remove(future); }
                    }
                    publishLenses(file, uri, work, resolved);
                }
            }
        } finally { work.cancel(); }
    }

    private void publishLenses(Path file, String uri, LensWork work, List<JavaCodeLens> lenses) {
        if (!currentLensWork(uri, work)) return;
        work.lenses = List.copyOf(lenses);
        onCodeLensRefresh.accept(file);
    }

    private void refreshCodeLensesAfterWork() {
        if (!isReady()) return;
        long now = System.currentTimeMillis();
        long previous = lastCodeLensWorkRefresh.get();
        if (now - previous < CODE_LENS_WORK_REFRESH_COOLDOWN_MS
                || !lastCodeLensWorkRefresh.compareAndSet(previous, now)) return;
        documents.uris().forEach(uri -> {
            if (!codeLensCache.containsKey(uri)) {
                Path path = LspConversions.toPath(uri);
                if (path != null) onCodeLensRefresh.accept(path);
            }
        });
    }

    public List<SemanticToken> semanticTokens(Path filePath, String text) {
        if (!capabilities.semanticTokens()) {
            return List.of();
        }
        if (!syncBeforeRequest(filePath, text)) {
            return List.of();
        }
        JsonNode result = requestBackground("textDocument/semanticTokens/full",
                Map.of("textDocument", documentId(filePath)), REQUEST_TIMEOUT_MS);
        if (result == null || !isCurrentText(filePath, text)) {
            return List.of();
        }
        return SemanticTokenDecoder.decode(result.get("data"), TOKEN_TYPES, TOKEN_MODIFIERS);
    }

    public String format(Path filePath, String text, int tabSize, boolean insertSpaces) {
        if (!capabilities.formatting()) {
            return null;
        }
        if (!syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = Map.of(
                "textDocument", documentId(filePath),
                "options", Map.of(
                        "tabSize", Math.max(1, tabSize),
                        "insertSpaces", insertSpaces));

        JsonNode result = request("textDocument/formatting", params, REQUEST_TIMEOUT_MS * 2);
        List<TextEdit> edits = LspConversions.textEdits(result);
        return edits.isEmpty() ? null : TextEditApplier.apply(text, edits);
    }

    /** Sequential save transformations share the updated snapshot without accepting stale requests. */
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
        if (!capabilities.codeAction()) {
            return null;
        }
        if (!syncBeforeRequest(filePath, text)) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("range", rangeParam(Range.point(0, 0)));
        params.put("context", Map.of(
                "diagnostics", List.of(),
                "only", List.of("source.organizeImports")));

        JsonNode result = request("textDocument/codeAction", params, REQUEST_TIMEOUT_MS);
        if (result == null || !result.isArray()) {
            return null;
        }
        for (JsonNode action : result) {
            List<TextEdit> edits = LspConversions.singleDocumentEdits(action.get("edit"));
            if (!edits.isEmpty()) {
                return TextEditApplier.apply(text, edits);
            }
        }
        return null;
    }

    public Collection<Diagnostic> diagnostics(Path filePath) {
        if (filePath == null) {
            return List.of();
        }
        return diagnosticsByPath.getOrDefault(normalizePath(filePath), List.of());
    }

    /**
     * Discards every diagnostic cached from the current server session.
     *
     * <p>The listeners are notified after the caches are empty so editors can remove stale
     * markers immediately, even when the language server itself is unresponsive.</p>
     */
    public void clearDiagnostics() {
        Set<Path> affected = new java.util.LinkedHashSet<>(diagnosticsByPath.keySet());
        affected.addAll(rawDiagnosticsByPath.keySet());
        diagnosticsByPath.clear();
        rawDiagnosticsByPath.clear();
        affected.forEach(onDiagnosticsPublished);
    }

    public Diagnostic diagnosticAt(Path filePath, int line, int col) {
        Diagnostic first = null;
        for (Diagnostic diagnostic : diagnostics(filePath)) {
            if (!contains(diagnostic, line, col)) {
                continue;
            }
            if (diagnostic.severity()
                    == dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.ERROR) {
                return diagnostic;
            }
            if (first == null) {
                first = diagnostic;
            }
        }
        return first;
    }

    public HoverInfo diagnosticHover(Path filePath, int line, int col) {
        Diagnostic diagnostic = diagnosticAt(filePath, line, col);
        if (diagnostic == null || diagnostic.message() == null || diagnostic.message().isBlank()) {
            return null;
        }
        return new HoverInfo(diagnostic.message(), true,
                diagnostic.startLine(), diagnostic.startCol(),
                diagnostic.endLine(), diagnostic.endCol());
    }

    private static boolean contains(Diagnostic diagnostic, int line, int col) {
        if (diagnostic == null || line < diagnostic.startLine() || line > diagnostic.endLine()) {
            return false;
        }
        if (line == diagnostic.startLine() && col < diagnostic.startCol()) {
            return false;
        }
        return line != diagnostic.endLine() || col <= diagnostic.endCol();
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

    private JsonNode requestAt(String method, Path filePath, String text, int line, int col) {
        return requestAt(method, filePath, text, line, col, REQUEST_TIMEOUT_MS);
    }

    private JsonNode requestAt(String method, Path filePath, String text, int line, int col,
                               long timeoutMs) {
        if (!syncBeforeRequest(filePath, text)) {
            return null;
        }
        return request(method, positionParams(filePath, line, col), timeoutMs);
    }

    private JsonNode requestAtInteractive(String method, Path filePath, String text,
                                          int line, int col) {
        if (!syncBeforeRequest(filePath, text)) {
            return null;
        }
        return requestInteractive(method, positionParams(filePath, line, col),
                INTERACTIVE_TIMEOUT_MS);
    }

    private synchronized boolean syncBeforeRequest(Path filePath, String text) {
        if (filePath == null || text == null) {
            return filePath != null;
        }
        String uri = LspConversions.toUri(filePath);
        String current = documents.content(uri);
        if (current == null) {
            openDocument(filePath, text);
            return true;
        }
        boolean currentSnapshot = current.equals(text);
        if (!currentSnapshot) {
            log.debug("Requisicao descartada para {}: snapshot do editor desatualizado", uri);
        }
        return currentSnapshot;
    }

    private JsonNode request(String method, Object params, long timeoutMs) {
        return request(method, params, timeoutMs, false);
    }

    private JsonNode requestInteractive(String method, Object params, long timeoutMs) {
        return request(method, params, timeoutMs, true);
    }

    private JsonNode requestBackground(String method, Object params, long timeoutMs) {
        return isWarmingUp() ? null : request(method, params, timeoutMs, false);
    }

    private JsonNode request(String method, Object params, long timeoutMs, boolean interactive) {
        LspJsonRpcClient rpc = client;
        if (rpc == null || (interactive ? !isInteractive() : !isReady())) {
            return null;
        }
        CompletableFuture<JsonNode> future = rpc.request(method, params);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            future.cancel(false);
            logRequestFailure(method, e);
            return null;
        }
    }

    private JsonNode requestCoalesced(String method, Object params, long timeoutMs, String key) {
        return requestCoalesced(method, params, timeoutMs, key, false);
    }

    private JsonNode requestCoalescedInteractive(String method, Object params, long timeoutMs,
                                                 String key) {
        return requestCoalesced(method, params, timeoutMs, key, true);
    }

    private JsonNode requestCoalescedBackground(String method, Object params, long timeoutMs,
                                                String key) {
        return isWarmingUp() ? null : requestCoalesced(method, params, timeoutMs, key, false);
    }

    private JsonNode requestCoalesced(String method, Object params, long timeoutMs, String key,
                                      boolean interactive) {
        LspJsonRpcClient rpc = client;
        if (rpc == null || (interactive ? !isInteractive() : !isReady())) {
            return null;
        }
        CompletableFuture<JsonNode> future = inFlightRequests.computeIfAbsent(key, ignored ->
                rpc.request(method, params));
        future.whenComplete((result, error) -> inFlightRequests.remove(key, future));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            inFlightRequests.remove(key, future);
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            inFlightRequests.remove(key, future);
            future.cancel(false);
            logRequestFailure(method, e);
            return null;
        }
    }

    private void logRequestFailure(String method, Exception error) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastFailureLog.computeIfAbsent(method, ignored -> new AtomicLong());
        long previous = last.get();
        if (now - previous >= 10_000 && last.compareAndSet(previous, now)) {
            String reason = error instanceof TimeoutException || error.getCause() instanceof TimeoutException
                    ? "tempo limite excedido" : rootMessage(error);
            log.debug("Requisicao {} falhou: {}", method, reason);
        }
    }

    private void clearNavigationCache(String uri) {
        workspaceRevision.incrementAndGet();
        if (uri == null || uri.isBlank()) {
            navigationCache.clear();
            return;
        }
        navigationCache.keySet().removeIf(key -> key.contains("|" + uri + "|"));
    }

    private void cancelInFlightForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        inFlightRequests.forEach((key, future) -> {
            if (key.contains("|" + uri + "|") && inFlightRequests.remove(key, future)) {
                future.cancel(false);
            }
        });
    }

    private boolean isCurrentText(Path filePath, String text) {
        return filePath != null && (text == null ? "" : text)
                .equals(documents.content(LspConversions.toUri(filePath)));
    }

    private static Map<String, Object> positionParams(Path filePath, int line, int col) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", documentId(filePath));
        params.put("position", Map.of("line", Math.max(0, line), "character", Math.max(0, col)));
        return params;
    }

    private static Map<String, Object> documentId(Path filePath) {
        return Map.of("uri", LspConversions.toUri(filePath));
    }

    private static Map<String, Object> rangeParam(Range range) {
        Range safe = range == null ? Range.point(0, 0) : range;
        return Map.of(
                "start", Map.of("line", safe.start().line(), "character", safe.start().col()),
                "end", Map.of("line", safe.end().line(), "character", safe.end().col()));
    }

    static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
