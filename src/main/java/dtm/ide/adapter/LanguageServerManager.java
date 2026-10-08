package dtm.ide.adapter;

import dtm.ide.api.extension.NotificationContext;
import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.ClasspathValidation;
import dtm.ide.debug.ConditionEditorSession;
import dtm.ide.lsp.LanguageServers;
import dtm.ide.lsp.LombokAgentResolver;
import dtm.ide.lsp.LombokSupport;
import dtm.ide.lsp.LombokSupportStatus;
import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.JavaAgentSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JdtOutputIsolation;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.ide.settings.JdtBuildMode;
import dtm.ide.ui.JavaIcons;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class LanguageServerManager {

    public static final String LSP_PROGRESS_ID = "javaLanguageServer";
    private static final String LSP_WORK_PROGRESS_ID = "javaLanguageServerWork";

    private final AdapterHost host;
    private final AtomicInteger lspProgress = new AtomicInteger();
    private volatile JdtBuildMode appliedBuildMode;
    private final AtomicBoolean languageServerReadyHandled = new AtomicBoolean();
    private final AtomicBoolean languageServerWorkVisible = new AtomicBoolean();
    private volatile LombokAgentResolver lombokResolver;
    private final LombokSupport lombokSupport = new LombokSupport(this::onLombokStatusChanged);

    public LanguageServerManager(AdapterHost host) {
        this.host = host;
    }

    public AtomicInteger progress() {
        return lspProgress;
    }

    public AtomicBoolean readyHandled() {
        return languageServerReadyHandled;
    }

    public JdtBuildMode appliedBuildMode() {
        return appliedBuildMode;
    }

    public void appliedBuildMode(JdtBuildMode mode) {
        appliedBuildMode = mode;
    }

    public void startLanguageServer(long ticket, Path root, JdkInstallation jdk) {
        if (!host.settings().getLanguageServerMode().startsServer()) {
            host.setStatusBarText(text("status.lspDisabled",
                    "Java: IntelliSense desligado nas preferencias"));
            return;
        }
        JavaLanguageServer lsp = ensureLanguageServer();
        if (lsp.isInteractive() && root.equals(lsp.getProjectRoot())) {
            return;
        }
        JavaProjectDescriptor current = host.descriptor();
        JdtBuildMode buildMode = host.settings().getJdtBuildMode();
        lsp.setBuildMode(buildMode);
        appliedBuildMode = buildMode;
        if (buildMode.isAutobuild() && JdtOutputIsolation.ensure(current)) {
            host.setStatusBarText(text("status.jdtOutputIsolated",
                    "Java: saida do autobuild isolada em") + " "
                    + JdtOutputIsolation.BUILD_DIRECTORY);
        }
        lsp.setSpringSupport(current != null && current.spring() && host.settings().isSpringSupport());
        applyLombokAgent(lsp, current);
        String loading = text("status.startingLsp", "Java: carregando IntelliSense...");
        lspProgress.set(5);
        host.showProgress(LSP_PROGRESS_ID, loading);
        host.updateProgress(LSP_PROGRESS_ID, loading, 5);
        lsp.start(root, jdk, host.progressListener()).whenComplete((unused, error) -> {
            host.hideProgress(LSP_PROGRESS_ID);
            if (!host.isCurrent(ticket, root)) {
                if (!root.equals(host.projectRoot())) {
                    lsp.stopAsyncIfBoundTo(root);
                }
                host.finishDiagnosticReanalysis(ticket, root, false);
                return;
            }
            if (error != null) {
                log.warn("IntelliSense Java indisponivel", error);
                host.setStatusBarText(text("status.lspUnavailable", "Java: IntelliSense indisponivel")
                        + " - " + AdapterFailures.rootMessage(error));
                JavaProjectDescriptor activeDescriptor = host.descriptor();
                if (activeDescriptor != null && activeDescriptor.spring()
                        && host.settings().isSpringSupport()) {
                    host.spring().loadMetadata(ticket, root);
                }
            }
            host.finishDiagnosticReanalysis(ticket, root, error == null && lsp.isReady());
        });
    }

    public JavaLanguageServer ensureLanguageServer() {
        synchronized (host.monitor()) {
        if (host.isUnloaded()) {
            throw new IllegalStateException("plugin Java descarregado");
        }
        JavaLanguageServer existing = host.languageServer();
        if (existing != null) {
            return existing;
        }
        JdkService jdks = host.jdkService();
        SdkDownloader downloader = new SdkDownloader(host.resolveDownloadObserver());
        JavaLanguageServer created = LanguageServers.defaultProvider().create(jdks, downloader,
                this::onLspDiagnosticsPublished);
        created.setMaxHeap(host.settings().getLanguageServerMemory());
        created.setInlayHintsMode(host.settings().getInlayHints());
        created.setStatusListener(this::publishLanguageServerStatus);
        created.setWorkListener(this::publishLanguageServerWork);
        created.setCodeLensRefreshListener(path -> {
            if (path == null || !host.conditionalBreakpoints().sessions().containsKey(path.toAbsolutePath().normalize())) {
                host.requestRefreshCodeLenses(path);
            }
        });
        created.setWarmUpCompleteListener(this::refreshJavaEditorsAfterIndexing);
        created.setLateCompletionListener(host.completionEngine()::onLateCompletion);
        created.setDocumentUpgradeListener(this::onLanguageServerDocumentUpgrade);
        languageServerReadyHandled.set(false);
        host.classFileUris(created.extension(ClassFileSupport.class));
        host.languageServer(created);
        return created;
        }
    }

    void onLspDiagnosticsPublished(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        ConditionEditorSession conditionSession = host.conditionalBreakpoints().sessions().get(normalized);
        if (conditionSession != null) {
            conditionSession.diagnosticsPublished();
            return;
        }
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null && lsp.isReady()) {
            host.problems().supersedeCompilerProblems(normalized);
        }
        host.requestRefreshDiagnostics(path);
        host.sourceActions().resolvePastedImports(path);
        List<BuildDiagnostic> problems = lsp == null ? List.of() : lsp.diagnostics(normalized)
                .stream()
                .map(diagnostic -> new BuildDiagnostic(normalized,
                        diagnostic.startLine() + 1, diagnostic.startCol() + 1,
                        diagnostic.severity(), diagnostic.message(), diagnostic.source()))
                .toList();
        host.problems().publishLive(normalized, problems);
        host.refreshProblemsPanel();
    }

    public boolean applyLombokAgent(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        JavaAgentSupport agents = lsp == null ? null : lsp.extension(JavaAgentSupport.class);
        if (agents == null) {
            return false;
        }
        if (!host.settings().isLombokSupport()) {
            lombokSupport.update(LombokSupportStatus.DISABLED, "");
            return agents.setLombokAgentJar(null);
        }
        try {
            LombokAgentResolver.Agent agent = ensureLombokResolver()
                    .resolveAgent(current, resolvedClasspath(lsp, current));
            if (!agent.declared()) {
                lombokSupport.update(LombokSupportStatus.NOT_USED, "");
                return agents.setLombokAgentJar(null);
            }
            if (!agent.isUsable()) {
                lombokSupport.update(LombokSupportStatus.ERROR, agent.failure());
                return agents.setLombokAgentJar(null);
            }
            lombokSupport.update(LombokSupportStatus.STARTING,
                    agent.version() == null ? LombokAgentResolver.TESTED_VERSION : agent.version());
            return agents.setLombokAgentJar(agent.jar());
        } catch (Exception e) {
            lombokSupport.update(LombokSupportStatus.ERROR, AdapterFailures.rootMessage(e));
            log.warn("Falha ao resolver o agente do Lombok: {}", AdapterFailures.rootMessage(e));
            return false;
        }
    }

    public LombokSupportStatus getLombokSupportStatus() {
        return lombokSupport.status();
    }

    public void setLombokSupportListener(LombokSupport.Listener listener) {
        lombokSupport.setListener(listener == null ? this::onLombokStatusChanged : listener);
    }

    List<Path> resolvedClasspath(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        if (lsp == null || current == null || !lsp.isInteractive()) {
            return List.of();
        }
        ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
        if (model == null) {
            return List.of();
        }
        return model.runtimeClasspath(current.root())
                .map(classpath -> {
                    if (ClasspathValidation.hasMissingJar(classpath)) {
                        requestJdtLsProjectConfigurationRefresh(lsp);
                        return List.<Path>of();
                    }
                    return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                            .filter(entry -> !entry.isBlank())
                            .map(Path::of)
                            .toList();
                })
                .orElse(List.of());
    }

    public void requestJdtLsProjectConfigurationRefresh() {
        requestJdtLsProjectConfigurationRefresh(host.languageServer());
    }

    public void requestJdtLsProjectConfigurationRefresh(JavaLanguageServer lsp) {
        ProjectModelSupport model = lsp == null ? null : lsp.extension(ProjectModelSupport.class);
        if (model != null && lsp.isInteractive()) {
            model.projectConfigurationUpdate();
        }
    }

    void onLombokStatusChanged(LombokSupportStatus status, String detail) {
        log.debug("Lombok: estado {} ({})", status, detail);
        switch (status) {
            case ACTIVE -> host.setStatusBarText(text("status.lombokActive", "Java: Lombok ativo")
                    + (detail == null || detail.isBlank() ? "" : " - " + detail));
            case ERROR -> notifyLombokFailure(detail);
            default -> {
            }
        }
    }

    void notifyLombokFailure(String detail) {
        String message = text("notification.lombokMessage",
                "Lombok foi detectado no projeto, mas o agente nao pode ser carregado. "
                        + "Getters, setters e builders podem aparecer como erro.");
        host.setStatusBarText(text("status.lombokError", "Java: Lombok detectado sem agente ativo"));
        host.createNotification(NotificationContext.builder()
                .title(text("notification.lombokTitle", "Lombok indisponivel"))
                .message(detail == null || detail.isBlank() ? message : message + " (" + detail + ")")
                .icon(JavaIcons.java(JavaIcons.SMALL))
                .build());
    }

    void refreshLombokStatusAfterServerState(LanguageServerState state) {
        LombokSupportStatus current = lombokSupport.status();
        if (current != LombokSupportStatus.STARTING && current != LombokSupportStatus.ACTIVE) {
            return;
        }
        if (state == LanguageServerState.READY) {
            lombokSupport.update(LombokSupportStatus.ACTIVE, lombokSupport.detail());
            return;
        }
        if (state == LanguageServerState.ERROR) {
            lombokSupport.update(LombokSupportStatus.ERROR, lombokSupport.detail());
        }
    }

    LombokAgentResolver ensureLombokResolver() {
        synchronized (host.monitor()) {
            LombokAgentResolver existing = lombokResolver;
            if (existing != null) {
                return existing;
            }
            LombokAgentResolver created = new LombokAgentResolver(host.jdkService().sdkRoot());
            lombokResolver = created;
            return created;
        }
    }

    void onLanguageServerDocumentUpgrade(Path path) {
        if (path == null) {
            return;
        }
        host.requestRefreshInlayHints(path);
        host.requestRefreshSemanticTokens(path);
        host.requestRefreshCodeLenses(path);
    }

    void publishLanguageServerStatus(String message, int percent) {
        JavaLanguageServer lsp = host.languageServer();
        LanguageServerState state = lsp == null ? LanguageServerState.NOT_STARTED : lsp.getState();
        refreshLombokStatusAfterServerState(state);
        if (state == LanguageServerState.STARTING || state == LanguageServerState.INDEXING) {
            int effectivePercent = percent >= 0 ? Math.max(1, Math.min(99, percent)) : -1;
            lspProgress.set(Math.max(0, effectivePercent));
            String label = message == null || message.isBlank() || message.strip().equals("Java:")
                    ? text("status.indexing",
                            "Java: indexando - navegacao e autocomplete aproximados disponiveis")
                    : message;
            host.updateProgress(LSP_PROGRESS_ID, label, effectivePercent, true,
                    this::cancelLanguageServerIndexing);
            return;
        }
        if (state == LanguageServerState.READY) {
            lspProgress.set(100);
            host.updateProgress(LSP_PROGRESS_ID, message, 100);
            host.hideProgress(LSP_PROGRESS_ID);
            if (languageServerReadyHandled.compareAndSet(false, true)) {
                Path root = host.projectRoot();
                JavaProjectDescriptor current = host.descriptor();
                if (root != null && current != null && current.spring()
                        && host.settings().isSpringSupport()) {
                    host.spring().loadMetadata(host.lifecycleTicket(), root);
                }
            }
            return;
        }
        host.hideProgress(LSP_PROGRESS_ID);
        publishLanguageServerWork(null, -1, false);
        if (state == LanguageServerState.ERROR) {
            host.setStatusBarText(message);
        }
    }

    void publishLanguageServerWork(String message, int percent, boolean active) {
        host.observeSyncWork(active);
        if (!active) {
            if (languageServerWorkVisible.compareAndSet(true, false)) {
                host.hideProgress(LSP_WORK_PROGRESS_ID);
            }
            return;
        }
        String label = message == null || message.isBlank()
                ? text("status.lspWorking", "Java: atualizando o projeto...")
                : message;
        if (languageServerWorkVisible.compareAndSet(false, true)) {
            host.showProgress(LSP_WORK_PROGRESS_ID, label);
        }
        host.updateProgress(LSP_WORK_PROGRESS_ID, label, percent);
    }

    void refreshJavaEditorsAfterIndexing() {
        if (host.javaEditors().isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            List<Path> openFiles = List.copyOf(host.javaEditors().keySet());
            host.background().submit(() -> {
                openFiles.forEach(path -> {
                    host.requestRefreshDiagnostics(path);
                    host.requestRefreshCodeLenses(path);
                    host.requestRefreshInlayHints(path);
                    host.requestRefreshSemanticTokens(path);
                });
            });
        });
    }

    void cancelLanguageServerIndexing() {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null) {
            return;
        }
        host.background().submit(() -> {
            lsp.stop();
            host.hideProgress(LSP_PROGRESS_ID);
            host.setStatusBarText(text("status.indexingCanceled",
                    "Java: indexacao cancelada - IntelliSense aproximado"));
        });
    }

    public static boolean needsLombokAgentRestart(JavaLanguageServer lsp) {
        JavaAgentSupport agents = lsp.extension(JavaAgentSupport.class);
        return agents != null && agents.needsRestartForLombokAgent();
    }
}
