package dtm.ide.adapter;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.BuildProblemsCoordinator;
import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildProgressTracker;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.StaticAnalysisReportParser;
import dtm.ide.build.incremental.IncrementalJavaBuilder;
import dtm.ide.build.incremental.ModuleBuildState;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.settings.JavaPluginSettings;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class BuildSupport {

    public static final String STARTUP_BUILD_PROGRESS_ID = "javaStartupBuild";
    private static final String BUILD_PROGRESS_ID = "javaBuild";
    private static final long STARTUP_BUILD_LSP_WAIT_MS = 180_000;
    private static final long STARTUP_BUILD_LSP_POLL_MS = 500;

    private final AdapterHost host;
    private final AtomicReference<IncrementalJavaBuilder> startupBuilder = new AtomicReference<>();

    public BuildSupport(AdapterHost host) {
        this.host = host;
    }

    public void startupBuild(long ticket, Path root) {
        if (!host.settings().isBuildOnProjectOpen() || !host.isCurrent(ticket, root)) {
            return;
        }
        JavaProjectDescriptor current = host.descriptor();
        BuildSystem build = host.buildSystem();
        if (current == null || build == null || current.rootModule() == null) {
            return;
        }
        if (isStartupBuildCached(current, build)) {
            host.setStatusBarText(text("status.buildCached", "Java: build em cache (sem mudancas)"));
            return;
        }
        awaitLanguageServerBeforeBuild(ticket, root);
        if (!host.isCurrent(ticket, root) || build.isRunning() || !host.buildRunning().compareAndSet(false, true)) {
            return;
        }
        if (isStartupBuildCached(current, build)) {
            host.buildRunning().set(false);
            host.setStatusBarText(text("status.buildCached", "Java: build em cache (sem mudancas)"));
            return;
        }

        BuildProgressTracker progress = new BuildProgressTracker(
                text("progress.buildingModule", "Buildando modulo"), current, null,
                update -> host.updateProgress(STARTUP_BUILD_PROGRESS_ID,
                        update.label(), update.percent()));
        BuildProgressTracker.Update initial = progress.initial();
        host.showProgress(STARTUP_BUILD_PROGRESS_ID, initial.label(), true, this::cancelStartupBuild);
        if (initial.percent() >= 0) {
            host.updateProgress(STARTUP_BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        try {
            BuildResult result = runStartupBuild(current, build, progress);
            if (!host.isCurrent(ticket, root)) {
                return;
            }
            publishBuildDiagnostics(result, true);
            host.setStatusBarText(result.summary().isEmpty() ? "" : "Java: " + result.summary());
        } finally {
            startupBuilder.set(null);
            BuildProgressTracker.Update completed = progress.completed();
            host.updateProgress(STARTUP_BUILD_PROGRESS_ID, completed.label(), completed.percent());
            host.hideProgress(STARTUP_BUILD_PROGRESS_ID);
            host.buildRunning().set(false);
        }
    }

    private boolean isStartupBuildCached(JavaProjectDescriptor current, BuildSystem build) {
        if (!host.settings().isIncrementalBuild()) {
            return false;
        }
        try {
            return new IncrementalJavaBuilder(current, () -> build, host::projectJdk)
                    .isUpToDate(current.rootModule());
        } catch (Exception e) {
            log.debug("Falha ao verificar o cache do build incremental: {}", e.getMessage());
            return false;
        }
    }

    private void awaitLanguageServerBeforeBuild(long ticket, Path root) {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_BUILD_LSP_WAIT_MS);
        while (host.isCurrent(ticket, root) && System.nanoTime() < deadline) {
            LanguageServerState state = lsp.getState();
            if (state != LanguageServerState.STARTING && state != LanguageServerState.INDEXING) {
                return;
            }
            lsp.awaitReady(STARTUP_BUILD_LSP_POLL_MS);
        }
    }

    private BuildResult runStartupBuild(JavaProjectDescriptor current, BuildSystem build,
                                        BuildProgressTracker progress) {
        JavaModule rootModule = current.rootModule();
        if (host.settings().isIncrementalBuild()) {
            IncrementalJavaBuilder builder =
                    new IncrementalJavaBuilder(current, host::buildSystem, host::projectJdk)
                            .withModuleListener(progress::moduleStarted);
            if (builder.isApplicable(rootModule)) {
                startupBuilder.set(builder);
                return builder.build(rootModule, false, progress);
            }
        }
        return executeBuild(BuildSystem.BuildAction.COMPILE, build, null, progress);
    }

    public void cancelStartupBuild() {
        IncrementalJavaBuilder builder = startupBuilder.getAndSet(null);
        if (builder != null) {
            builder.cancel();
        }
        BuildSystem build = host.currentBuildSystem();
        if (build != null && build.isRunning()) {
            build.cancel();
        }
    }

    public void runBuild(BuildSystem.BuildAction action, String title, JavaModule requestedModule) {
        Path root = host.projectRoot();
        BuildSystem build = host.buildSystem();
        if (root == null || build == null) {
            return;
        }
        if (build.isRunning() || !host.buildRunning().compareAndSet(false, true)) {
            host.setStatusBarText(text("status.buildRunning", "Java: ja existe um build em andamento"));
            return;
        }

        long ticket = host.lifecycleTicket();
        JavaModule module = requestedModule != null && requestedModule.isAggregator()
                ? null : requestedModule;
        BuildProgressTracker progress = new BuildProgressTracker(
                buildProgressAction(action), host.descriptor(), module,
                update -> host.updateProgress(BUILD_PROGRESS_ID, update.label(), update.percent()));
        BuildProgressTracker.Update initial = progress.initial();
        host.showProgress(BUILD_PROGRESS_ID, initial.label());
        if (initial.percent() >= 0) {
            host.updateProgress(BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        host.background().submit(() -> {
            try {
                BuildResult result = executeBuild(action, build, module, progress);
                if (!host.isCurrent(ticket, root)) {
                    return;
                }
                publishBuildDiagnostics(result, true);
                host.setStatusBarText(result.summary().isEmpty() ? "" : "Java: " + result.summary());
            } finally {
                BuildProgressTracker.Update completed = progress.completed();
                host.updateProgress(BUILD_PROGRESS_ID, completed.label(), completed.percent());
                host.hideProgress(BUILD_PROGRESS_ID);
                host.buildRunning().set(false);
            }
        });
    }

    private BuildResult executeBuild(BuildSystem.BuildAction action, BuildSystem build,
                                     JavaModule module, Consumer<String> output) {
        JavaPluginSettings preferences = host.settings();
        if (action == BuildSystem.BuildAction.REBUILD || action == BuildSystem.BuildAction.CLEAN) {
            discardIncrementalState();
        }
        JavaProjectDescriptor current = host.descriptor();
        if ((action == BuildSystem.BuildAction.COMPILE || action == BuildSystem.BuildAction.TEST_COMPILE)
                && preferences.isIncrementalBuild() && current != null) {
            JavaModule target = module == null ? current.rootModule() : module;
            IncrementalJavaBuilder builder =
                    new IncrementalJavaBuilder(current, () -> build, host::projectJdk);
            if (target != null && builder.isApplicable(target)) {
                return builder.build(target, action == BuildSystem.BuildAction.TEST_COMPILE, output);
            }
        }
        BuildRequest request = BuildRequest.of(action, module)
                .withOffline(preferences.isBuildOffline());
        return build.execute(request, output);
    }

    private void discardIncrementalState() {
        if (host.descriptor() == null || host.descriptor().root() == null) {
            return;
        }
        ModuleBuildState.discard(IncrementalJavaBuilder.stateDirectory(host.descriptor().root()));
    }

    public String buildProgressAction(BuildSystem.BuildAction action) {
        return switch (action) {
            case COMPILE -> text("progress.compiling", "Compilando");
            case TEST_COMPILE -> text("progress.testCompiling", "Compilando testes");
            case REBUILD -> text("progress.rebuilding", "Recompilando");
            case CLEAN -> text("progress.cleaning", "Limpando");
            case TEST -> text("progress.testing", "Executando testes");
            case PACKAGE -> text("progress.packaging", "Empacotando");
            case INSTALL -> text("progress.installing", "Instalando");
        };
    }

    public void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure) {
        publishBuildDiagnostics(result, revealOnFailure, BuildProblemsCoordinator.Channel.BUILD);
    }

    public void publishTestDiagnostics(BuildResult result) {
        publishBuildDiagnostics(result, true, BuildProblemsCoordinator.Channel.TEST);
    }

    public void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure,
                                         BuildProblemsCoordinator.Channel channel) {
        if (result == null) {
            return;
        }
        List<BuildDiagnostic> reported = new ArrayList<>(result.diagnostics());
        reported.addAll(StaticAnalysisReportParser.discover(host.descriptor()));
        List<BuildDiagnostic> published = !reported.isEmpty() || result.successful()
                ? List.copyOf(reported)
                : List.of(new BuildDiagnostic(null, 0, 0, DiagnosticSeverity.ERROR,
                        result.summary(), "build"));
        Set<Path> affected = host.problems().replace(channel, published);
        affected.forEach(host::requestRefreshDiagnostics);

        host.refreshProblemsPanel();
        if (revealOnFailure && !result.successful()) {
            SwingUtilities.invokeLater(host::requestOpenProblemsPanel);
        }
    }

    public void writeOutput(OutputPanelHandle panel, String line) {
        if (panel == null) {
            return;
        }
        try {
            panel.getOutputStream().write((line + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
            panel.getOutputStream().flush();
        } catch (Exception e) {
            log.debug("Falha ao escrever no painel de saida: {}", e.getMessage());
        }
    }
}
