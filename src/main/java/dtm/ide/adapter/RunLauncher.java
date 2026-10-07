package dtm.ide.adapter;

import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.build.BuildProgressTracker;
import dtm.ide.build.BuildSystem;
import dtm.ide.coverage.CoverageAgent;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.debug.JdwpRelay;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.DebugPorts;
import dtm.ide.run.JavaRunConfigurationContribution;
import dtm.ide.run.JavaRunSupport;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.run.JavaRunValidation;
import dtm.ide.run.MainClassScanner;
import dtm.ide.run.ProcessLauncher;
import dtm.ide.run.RemoteDebugSettings;
import dtm.ide.run.chain.RunChainHost;
import dtm.ide.run.form.RunFormChoicesLoader;
import dtm.ide.run.form.RunFormContext;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class RunLauncher {

    private static final long RUN_BUTTONS_REFRESH_DELAY_MS = 300;
    private static final String RUN_BUILD_PROGRESS_ID = "javaRunBuild";
    private static final long BUILD_SLOT_WAIT_MS = 600_000;
    private static final long BUILD_SLOT_POLL_MS = 100;

    private final AdapterHost host;
    private final Map<RunConfigurationKey, RunProcessHandle> runningProcesses =
            new ConcurrentHashMap<>();
    private final Map<RunConfigurationKey, AtomicBoolean> pendingLaunches =
            new ConcurrentHashMap<>();
    private final AtomicLong runButtonsTicket = new AtomicLong();
    private volatile MainClassMemo mainClassMemo;
    private volatile JavaRunSupport runSupport;
    private final AtomicReference<BuildProgressTracker> runBuildProgress = new AtomicReference<>();
    private volatile JdwpRelay debugRelay;
    private volatile RunFormChoicesLoader runFormChoicesLoader;
    private volatile RunConfigurationData selectedRunConfig;

    public RunLauncher(AdapterHost host) {
        this.host = host;
    }

    public RunConfigurationData selectedRunConfig() {
        return selectedRunConfig;
    }

    public void clearSelectedRunConfig() {
        selectedRunConfig = null;
    }

    public void clearMainClassMemo() {
        mainClassMemo = null;
    }

    public void clearRunSupport() {
        runSupport = null;
    }

    public void clearRunBuildProgress() {
        runBuildProgress.set(null);
    }

    public void setDebugRelay(JdwpRelay relay) {
        debugRelay = relay;
    }

    public Map<RunConfigurationKey, RunProcessHandle> runningProcesses() {
        return runningProcesses;
    }

    public RunFormChoicesLoader currentRunFormChoicesLoader() {
        return runFormChoicesLoader;
    }

    public List<RunConfigurationContribution> getRunConfigurationContributions() {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return List.of();
        }
        RunFormContext formContext = RunFormContext.sharing(host::descriptor, runFormChoicesLoader(),
                host::requestRunConfigurations, host::createModernDialogBuilder,
                () -> host.<Boolean>createModernComponentDialogBuilder());
        List<RunConfigurationContribution> contributions = new ArrayList<>();
        contributions.add(new JavaRunConfigurationContribution(
                JavaRunTypes.APPLICATION, formContext));
        if (current.springBoot()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.SPRING_BOOT, formContext));
        }
        contributions.add(new JavaRunConfigurationContribution(JavaRunTypes.JAR, formContext));
        if (current.isMaven()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.MAVEN, formContext));
        }
        if (current.isGradle()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.GRADLE, formContext));
        }
        if (current.isMaven() || current.isGradle()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.TEST, formContext));
        }
        contributions.add(new JavaRunConfigurationContribution(JavaRunTypes.REMOTE, formContext));
        return contributions;
    }

    public List<JdkInstallation> availableJdks() {
        JdkService service = host.currentJdkService();
        return service == null ? List.of() : service.available();
    }

    public RunFormChoicesLoader runFormChoicesLoader() {
        synchronized (host.monitor()) {
        RunFormChoicesLoader existing = runFormChoicesLoader;
        if (existing != null) {
            return existing;
        }
        RunFormChoicesLoader created = new RunFormChoicesLoader(host::descriptor,
                this::availableJdks, host.background(), SwingUtilities::invokeLater,
                dtm.stools.i18n.I18n.getText(
                        dtm.ide.run.form.RunConfigurationFormBase.class,
                        "field.jdk.project", "JDK do projeto"));
        runFormChoicesLoader = created;
        return created;
        }
    }

    public void onRunConfigurationChanged(RunConfigurationData configuration) {
        selectedRunConfig = configuration;
        refreshCoverageButton();
        if (JavaRunSupport.isCurrentFileType(configuration)) {
            refreshRunButtonsForCurrentFile();
            return;
        }
        if (configuration == null || !JavaRunTypes.isJavaType(configuration)) {
            return;
        }
        String type = configuration.getType();
        boolean valid = JavaRunValidation.validate(configuration,
                JavaRunValidation.Context.of(host.descriptor())).isValid();
        boolean runnable = valid && JavaRunTypes.supportsRun(type);
        boolean debuggable = valid && JavaRunTypes.supportsDebug(type);
        SwingUtilities.invokeLater(() -> {
            host.requestSetRunButtonEnabled(runnable);
            host.requestSetDebugButtonEnabled(debuggable);
        });
    }

    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
        if (configuration != null && JavaRunTypes.REMOTE.equals(configuration.getType())) {
            return ensureRunSupport().failure(text("error.remoteIsDebugOnly",
                    "Remote JVM so pode ser iniciado pelo botao Debug."));
        }
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                cancelled -> ensureRunSupport().launch(resolved, context, 0, cancelled));
        trackRunningProcess(configuration, handle);
        return handle;
    }

    public RunProcessHandle launchCoverage(RunConfigurationData configuration,
                                           RunExecutionContext context) {
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        String coverageArgument = coverageArgumentFor(resolved);
        if (coverageArgument == null) {
            return ensureRunSupport().failure(text("coverage.unsupportedRunType",
                    "Java: cobertura no Run so vale para Aplicacao, Spring Boot e JAR"));
        }
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                cancelled -> ensureRunSupport().launchWithCoverage(resolved, context,
                        coverageArgument, cancelled));
        trackRunningProcess(configuration, handle);
        host.coverage().awaitRun(handle, CoverageAgent.execFileFor(host.descriptor().root()), host.descriptor());
        return handle;
    }

    public String coverageArgumentFor(RunConfigurationData configuration) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null || configuration == null) {
            return null;
        }
        if (!JavaRunTypes.LOCAL_JVM.contains(configuration.getType())) {
            return null;
        }
        CoverageProvisioner provisioner = host.coverage().provisioner();
        Path agent = provisioner == null ? null : provisioner.ensureAgent().orElse(null);
        Path execFile = CoverageAgent.execFileFor(current.root());
        if (agent == null || execFile == null) {
            host.setStatusBarText(text("coverage.agentMissing",
                    "Java: nao foi possivel preparar o agente de cobertura"));
            return null;
        }
        try {
            Files.createDirectories(execFile.getParent());
            Files.deleteIfExists(execFile);
        } catch (Exception error) {
            host.setStatusBarText(text("coverage.agentMissing",
                    "Java: nao foi possivel preparar o agente de cobertura"));
            return null;
        }
        return CoverageAgent.agentArgument(agent, execFile, false);
    }

    public RunProcessHandle launchDebug(RunConfigurationData configuration,
                                        RunExecutionContext context) {
        if (configuration != null && JavaRunTypes.REMOTE.equals(configuration.getType())) {
            return launchRemoteDebug(configuration, context);
        }
        if (configuration != null && JavaRunTypes.BUILD_TOOL.contains(configuration.getType())
                && !JavaRunTypes.TEST.equals(configuration.getType())) {
            return launchBuildToolDebug(configuration, context);
        }
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        RunExecutionContext debugContext = context == null
                ? RunExecutionContext.builder().projectPath(host.projectRoot()).debug(true).build()
                : context;
        debugContext.setDebug(true);
        if (debugContext.getBreakpoints() == null || debugContext.getBreakpoints().isEmpty()) {
            debugContext.setBreakpoints(host.requestWorkspaceBreakpoints());
        }
        host.debug().warmUpDebugAdapter();
        int jdwpPort = DebugPorts.allocate();
        JavaModule targetModule = resolveDebugModule(resolved);
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                cancelled -> ensureRunSupport().launch(resolved, debugContext, jdwpPort, cancelled));
        if (handle.isAlive()) {
            trackRunningProcess(configuration, handle);
            host.debug().startDebugSession(jdwpPort, debugContext, handle::terminate, targetModule, handle);
            host.requestSetRunButtonRunning(true);
        }
        return handle;
    }

    public RunProcessHandle launchRemoteDebug(RunConfigurationData configuration,
                                               RunExecutionContext context) {
        JavaRunValidation.Report report = JavaRunValidation.validate(configuration,
                JavaRunValidation.Context.of(host.descriptor()));
        if (!report.isValid()) {
            return ensureRunSupport().failure(report.firstMessage());
        }
        Optional<String> chainFailure = ensureRunSupport().runBeforeLaunchChain(configuration);
        if (chainFailure.isPresent()) {
            return ensureRunSupport().failure(chainFailure.get());
        }
        RemoteDebugSettings settings = RemoteDebugSettings.from(configuration);
        RunExecutionContext debugContext = debugContextOf(context);
        JavaModule targetModule = resolveDebugModule(configuration);

        if (!settings.listen()) {
            host.debug().startDebugSession(settings.attachTarget(), debugContext, null, targetModule, null);
            return ProcessLauncher.message(text("remote.attaching", "Conectando a")
                    + " " + settings.host() + ":" + settings.port());
        }

        JdwpRelay relay;
        try {
            relay = JdwpRelay.open(settings.host(), settings.port());
        } catch (IOException error) {
            return ensureRunSupport().failure(text("error.remoteBind",
                    "Nao foi possivel escutar em") + " " + settings.host() + ":"
                    + settings.port() + " - " + AdapterFailures.rootMessage(error));
        }
        debugRelay = relay;
        host.background().submit(() -> {
            try {
                relay.awaitTarget(settings.timeoutMillis());
                host.debug().startDebugSession(settings.relayTarget(relay.adapterPort()), debugContext,
                        null, targetModule, null);
            } catch (SocketTimeoutException timeout) {
                closeDebugRelay();
                host.setStatusBarText(text("error.remoteTimeout",
                        "Java Debug: nenhuma JVM conectou dentro do tempo limite."));
            } catch (IOException error) {
                closeDebugRelay();
                host.setStatusBarText("Java Debug: " + AdapterFailures.rootMessage(error));
            }
        });
        return ProcessLauncher.message(text("remote.listening", "Aguardando a JVM conectar em")
                + " " + settings.host() + ":" + settings.port());
    }

    public RunProcessHandle launchBuildToolDebug(RunConfigurationData configuration,
                                                  RunExecutionContext context) {
        host.debug().warmUpDebugAdapter();
        JavaModule targetModule = resolveDebugModule(configuration);
        AtomicReference<RunProcessHandle> launched = new AtomicReference<>();
        BuildToolDebugListener listener;
        try {
            listener = openBuildDebugListener(targetModule, () -> {
                RunProcessHandle running = launched.get();
                if (running != null) {
                    running.terminate();
                }
            });
        } catch (IOException error) {
            return ensureRunSupport().failure(text("error.buildDebugListen",
                    "Nao foi possivel abrir a porta de debug:") + " " + AdapterFailures.rootMessage(error));
        }
        RunProcessHandle handle = launchWithBuildProgress(configuration,
                cancelled -> ensureRunSupport().launch(configuration, context,
                        listener.listenPort(), cancelled));
        if (!handle.isAlive()) {
            listener.close();
            return handle;
        }
        launched.set(handle);
        trackRunningProcess(configuration, handle);
        host.requestSetRunButtonRunning(true);
        host.background().submit(() -> {
            try {
                while (handle.isAlive() && !listener.isClosed()) {
                    Thread.sleep(200);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                listener.close();
            }
        });
        return handle;
    }

    public BuildToolDebugListener openBuildDebugListener(JavaModule targetModule,
                                                          Runnable cancelProcess) throws IOException {
        return openBuildDebugListener(targetModule, cancelProcess, () -> {
        });
    }

    public BuildToolDebugListener openBuildDebugListener(JavaModule targetModule,
                                                          Runnable cancelProcess,
                                                          Runnable onAttach) throws IOException {
        return BuildToolDebugListener.open((target, detached) -> {
                    onAttach.run();
                    host.debug().startDebugSession(target, debugContextOf(null),
                            () -> detached.accept(!host.debug().naturalDebugEnd().getAndSet(false)),
                            targetModule, null);
                },
                cancelProcess, host.background());
    }

    public RunExecutionContext debugContextOf(RunExecutionContext context) {
        RunExecutionContext debugContext = context == null
                ? RunExecutionContext.builder().projectPath(host.projectRoot()).debug(true).build()
                : context;
        debugContext.setDebug(true);
        if (debugContext.getBreakpoints() == null || debugContext.getBreakpoints().isEmpty()) {
            debugContext.setBreakpoints(host.requestWorkspaceBreakpoints());
        }
        return debugContext;
    }

    public boolean supportsHotReloadForSelection() {
        RunConfigurationData selected = selectedRunConfig;
        String type = selected == null ? null : selected.getType();
        return type == null || JavaRunTypes.supportsHotReload(type);
    }

    public void closeDebugRelay() {
        JdwpRelay relay = debugRelay;
        debugRelay = null;
        if (relay != null) {
            relay.close();
        }
    }

    public void trackRunningProcess(RunConfigurationData configuration,
                                     RunProcessHandle handle) {
        if (handle == null || !handle.isAlive()) {
            return;
        }
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        runningProcesses.put(key, handle);
        host.background().submit(() -> {
            try {
                while (handle.isAlive()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                runningProcesses.remove(key, handle);
                host.requestSetRunButtonRunning(hasRunningProcess());
            }
        });
    }

    public RunProcessHandle runningProcess(RunConfigurationData configuration) {
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        RunProcessHandle handle = runningProcesses.get(key);
        if (handle != null && !handle.isAlive()) {
            runningProcesses.remove(key, handle);
            return null;
        }
        return handle;
    }

    public boolean hasRunningProcess() {
        return runningProcesses.values().stream().anyMatch(RunProcessHandle::isAlive);
    }

    public record RunConfigurationKey(String type, String title, Map<String, Object> properties) {

        private static RunConfigurationKey of(RunConfigurationData configuration) {
            if (configuration == null) {
                return new RunConfigurationKey("", "", Map.of());
            }
            Map<String, Object> properties = configuration.getProperties() == null
                    ? Map.of() : new LinkedHashMap<>(configuration.getProperties());
            return new RunConfigurationKey(
                    Objects.toString(configuration.getType(), ""),
                    Objects.toString(configuration.getTitle(), ""), properties);
        }
    }

    public void scheduleRunButtonsRefresh() {
        long ticket = runButtonsTicket.incrementAndGet();
        host.background().schedule(() -> {
            if (runButtonsTicket.get() == ticket) {
                refreshRunButtonsForCurrentFile();
            }
        }, RUN_BUTTONS_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    public void refreshRunButtonsForCurrentFile() {
        refreshCoverageButton();
        if (!JavaRunSupport.isCurrentFileType(selectedRunConfig)) {
            return;
        }
        boolean runnable = currentMainClass().isPresent();
        SwingUtilities.invokeLater(() -> {
            host.requestSetRunButtonEnabled(runnable);
            host.requestSetDebugButtonEnabled(runnable);
        });
    }

    public void refreshCoverageButton() {
        boolean available = host.coverage().supportedForProject() && coverageRunnableConfiguration();
        SwingUtilities.invokeLater(() -> {
            host.requestSetCoverageButtonVisible(available);
            host.requestSetCoverageButtonEnabled(available);
        });
    }

    public boolean coverageRunnableConfiguration() {
        RunConfigurationData configuration = selectedRunConfig;
        if (JavaRunSupport.isCurrentFileType(configuration)) {
            return currentMainClass().isPresent();
        }
        return configuration != null && JavaRunTypes.LOCAL_JVM.contains(configuration.getType());
    }

    public Optional<RunConfigurationData> resolveCurrentFileConfiguration(
            RunConfigurationData configuration) {
        if (!JavaRunSupport.isCurrentFileType(configuration)) {
            return Optional.ofNullable(configuration);
        }
        return currentMainClass().map(mainClass -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            if (configuration.getProperties() != null) {
                properties.putAll(configuration.getProperties());
            }
            properties.put(JavaRunSupport.PROPERTY_MAIN_CLASS, mainClass.qualifiedName());
            properties.put(JavaRunSupport.PROPERTY_MODULE, mainClass.module().name());
            if (mainClass.test()) {
                properties.put(JavaRunSupport.PROPERTY_TEST_CLASSPATH, Boolean.TRUE.toString());
            }
            return RunConfigurationData.builder()
                    .type(mainClass.springBoot() ? JavaRunSupport.TYPE_SPRING_BOOT
                            : JavaRunSupport.TYPE_RUN)
                    .title(mainClass.simpleName())
                    .properties(properties)
                    .build();
        });
    }

    public Optional<MainClassScanner.MainClass> currentMainClass() {
        IdeEditorContext editor = host.activeJavaEditor();
        JavaProjectDescriptor current = host.descriptor();
        if (editor == null || current == null || editor.filePath() == null) {
            return Optional.empty();
        }
        Path file = JavaProjectConventions.normalize(editor.filePath());
        JavaModule module = host.moduleContaining(current, file, false);
        boolean test = false;
        if (module == null) {
            module = host.moduleContaining(current, file, true);
            test = module != null;
        }
        if (module == null) {
            return Optional.empty();
        }
        String source = Objects.toString(editor.getText(), "");
        MainClassMemo memo = mainClassMemo;
        if (memo != null && memo.matches(file, module, test, source)) {
            return memo.result();
        }
        Optional<MainClassScanner.MainClass> result = MainClassScanner.hasValidMain(source)
                ? MainClassScanner.inspect(file, source, module, test)
                : Optional.empty();
        mainClassMemo = new MainClassMemo(file, module, test, source, result);
        return result;
    }

    private record MainClassMemo(Path file, JavaModule module, boolean test, String source,
                                 Optional<MainClassScanner.MainClass> result) {

        boolean matches(Path otherFile, JavaModule otherModule, boolean otherTest, String otherSource) {
            return test == otherTest && file.equals(otherFile) && Objects.equals(module, otherModule)
                    && (source == otherSource || source.equals(otherSource));
        }
    }

    public void stop(RunConfigurationData configuration) {
        RunProcessHandle process = runningProcess(configuration);
        boolean cancelledPreparation = process == null && cancelPendingLaunch(configuration);
        Runnable pendingTest = process == null ? host.pendingTestDebug().getAndSet(null) : null;
        if (pendingTest != null) {
            pendingTest.run();
        }
        if (process != null && process == host.debug().processHandle()
                && (host.debug().session() != null || host.debugActive())) {
            host.debug().closeDebugSession();
        } else {
            if (process == null && (host.debug().session() != null || host.debugActive())) {
                host.debug().closeDebugSession();
            } else if (process != null) {
                process.terminate();
            }
        }
        if (!cancelledPreparation) {
            host.requestSetRunButtonRunning(hasRunningProcess());
        }
    }

    public JavaRunSupport ensureRunSupport() {
        synchronized (host.monitor()) {
        JavaRunSupport existing = runSupport;
        if (existing != null) {
            return existing;
        }
        JavaRunSupport created = new JavaRunSupport(
                host::descriptor,
                host::projectJdk,
                host::buildSystem,
                line -> {
                    BuildProgressTracker progress = runBuildProgress.get();
                    if (progress != null) {
                        progress.accept(line);
                    }
                })
                .withChainHost(this::chainHost)
                .withIncrementalBuild(() -> host.settings().isIncrementalBuild())
                .withModuleProgress((module, index, total) -> {
                    BuildProgressTracker progress = runBuildProgress.get();
                    if (progress != null) {
                        progress.moduleStarted(module, index, total);
                    }
                })
                .withBuildResultListener(result -> host.publishBuildDiagnostics(result, true));
        runSupport = created;
        return created;
        }
    }

    public RunProcessHandle launchWithBuildProgress(RunConfigurationData configuration,
                                                     Function<BooleanSupplier, RunProcessHandle> launcher) {
        if (host.isUnloaded()) {
            return ProcessLauncher.message("Plugin Java descarregado.");
        }
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        AtomicBoolean cancelled = new AtomicBoolean();
        pendingLaunches.put(key, cancelled);
        try {
            return launchWithBuildProgress(configuration, () -> launcher.apply(cancelled::get));
        } finally {
            pendingLaunches.remove(key, cancelled);
        }
    }

    public boolean cancelPendingLaunch(RunConfigurationData configuration) {
        if (pendingLaunches.isEmpty()) {
            return false;
        }
        AtomicBoolean matching = configuration == null ? null
                : pendingLaunches.get(RunConfigurationKey.of(configuration));
        if (matching != null) {
            matching.set(true);
        } else {
            pendingLaunches.values().forEach(flag -> flag.set(true));
        }
        ensureRunSupport().cancelPreparation();
        return true;
    }

    public RunProcessHandle launchWithBuildProgress(RunConfigurationData configuration,
                                                     Supplier<RunProcessHandle> launcher) {
        Optional<BuildSystem.BuildAction> action =
                JavaRunSupport.buildBeforeRunAction(configuration);
        if (action.isEmpty()) {
            return host.isUnloaded() ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        }
        JavaModule module = resolveDebugModule(configuration);
        BuildProgressTracker progress = new BuildProgressTracker(
                host.buildProgressAction(action.get()), host.descriptor(), module,
                update -> host.updateProgress(RUN_BUILD_PROGRESS_ID,
                        update.label(), update.percent()));
        if (!runBuildProgress.compareAndSet(null, progress)) {
            return host.isUnloaded() ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        }
        BuildProgressTracker.Update initial = progress.initial();
        host.showProgress(RUN_BUILD_PROGRESS_ID, initial.label());
        if (initial.percent() >= 0) {
            host.updateProgress(RUN_BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        boolean slot = false;
        try {
            slot = awaitBuildSlot();
            return host.isUnloaded() ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        } finally {
            if (slot) {
                host.buildRunning().set(false);
            }
            runBuildProgress.compareAndSet(progress, null);
            host.hideProgress(RUN_BUILD_PROGRESS_ID);
        }
    }

    public boolean awaitBuildSlot() {
        if (host.buildRunning().compareAndSet(false, true)) {
            return true;
        }
        host.updateProgress(RUN_BUILD_PROGRESS_ID, text("progress.waitingBuild",
                "Aguardando o build em andamento"), -1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUILD_SLOT_WAIT_MS);
        while (System.nanoTime() < deadline) {
            if (host.buildRunning().compareAndSet(false, true)) {
                return true;
            }
            try {
                Thread.sleep(BUILD_SLOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.info("Build anterior nao terminou em {} ms; seguindo com o Run", BUILD_SLOT_WAIT_MS);
        return false;
    }

    public RunChainHost chainHost() {
        return new RunChainHost() {

                    public List<RunConfigurationData> configurations() {
                return host.requestRunConfigurations();
            }

                    public RunProcessHandle execute(String configurationId, boolean debug) {
                return host.requestRunConfigurationExecution(configurationId, debug);
            }
        };
    }

    public JavaModule resolveDebugModule(RunConfigurationData configuration) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return null;
        }
        Object configured = configuration == null || configuration.getProperties() == null
                ? null : configuration.getProperties().get(JavaRunSupport.PROPERTY_MODULE);
        String name = configured == null ? "" : configured.toString().trim();
        return current.modules().stream().filter(module -> module.name().equals(name))
                .findFirst().orElse(current.rootModule());
    }
}
