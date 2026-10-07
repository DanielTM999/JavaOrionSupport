package dtm.ide.adapter;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.build.BuildSystem;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.TestDiscoverySupport;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.test.JUnitTestDiscovery;
import dtm.ide.test.JavaSemanticTestDiscovery;
import dtm.ide.test.JavaTest;
import dtm.ide.test.JavaTestProblems;
import dtm.ide.test.JavaTestRunner;
import dtm.ide.ui.JavaTestExplorerPanel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static dtm.ide.adapter.AdapterFailures.rootMessage;
import static dtm.ide.adapter.AdapterText.text;

public final class TestExplorerSupport implements JavaTestExplorerPanel.Host {

    public static final String TEST_DEBUG_PROGRESS_ID = "javaTestDebugBuild";

    private final AdapterHost host;

    public TestExplorerSupport(AdapterHost host) {
        this.host = host;
    }

    @Override
    public List<JavaTest> discover() {
        return JUnitTestDiscovery.discover(host.descriptor());
    }

    @Override
    public void discoverSemantic(List<JavaTest> provisional,
                                 java.util.function.Consumer<List<JavaTest>> onFinished) {
        JavaProjectDescriptor current = host.descriptor();
        JavaLanguageServer lsp = host.languageServer();
        TestDiscoverySupport discovery = lsp == null ? null : lsp.extension(TestDiscoverySupport.class);
        if (current == null || discovery == null || !discovery.isTestRunnerAvailable()) {
            onFinished.accept(provisional);
            return;
        }
        host.background().submit(() -> onFinished.accept(
                JavaSemanticTestDiscovery.enrich(current, discovery, provisional)));
    }

    @Override
    public void run(List<JavaTest> tests,
                    java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
        JavaProjectDescriptor current = host.descriptor();
        BuildSystem build = host.buildSystem();
        if (current == null || build == null) {
            onFinished.accept(null);
            return;
        }
        OutputPanelHandle panel = host.requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
        if (panel != null) {
            panel.clear();
            panel.show();
        }
        host.requestShowRunOutput();

        host.background().submit(() -> {
            JavaTestRunner runner = host.newTestRunner(current, build);
            JavaTestRunner.TestRun run = runner.run(tests, current.rootModule(),
                    line -> host.writeOutput(panel, line));
            host.publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
            host.setStatusBarText("Java: " + run.summary());
            onFinished.accept(run);
        });
    }

    @Override
    public void clearCoverage() {
        host.clearCoverage();
        Path root = host.projectRoot();
        if (root != null) {
            host.requestRefreshCodeLenses(root);
        }
    }

    @Override
    public boolean supportsCoverage() {
        JavaProjectDescriptor current = host.descriptor();
        return current != null && (current.isMaven() || current.isGradle());
    }

    @Override
    public void runWithCoverage(List<JavaTest> tests,
                                java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
        JavaProjectDescriptor current = host.descriptor();
        BuildSystem build = host.buildSystem();
        CoverageProvisioner provisioner = host.coverageProvisioner();
        if (current == null || build == null || provisioner == null) {
            onFinished.accept(null);
            return;
        }
        if (!current.isMaven() && !current.isGradle()) {
            host.setStatusBarText(text("coverage.unsupportedProject",
                    "Java: cobertura requer Maven ou Gradle"));
            onFinished.accept(null);
            return;
        }
        OutputPanelHandle panel = host.requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
        if (panel != null) {
            panel.clear();
            panel.show();
        }
        host.requestShowRunOutput();

        host.background().submit(() -> {
            Path agent = provisioner.ensureAgent().orElse(null);
            if (agent == null) {
                host.setStatusBarText(text("coverage.agentMissing",
                        "Java: nao foi possivel preparar o agente de cobertura"));
                onFinished.accept(null);
                return;
            }
            JavaTestRunner runner = host.newTestRunner(current, build);
            JavaTestRunner.CoverageRun coverageRun = runner.runWithCoverage(
                    tests, moduleOf(tests, current), agent, line -> host.writeOutput(panel, line));
            JavaTestRunner.TestRun run = coverageRun.testRun();
            host.publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
            host.setStatusBarText("Java: " + run.summary());
            onFinished.accept(run);
            host.readCoverage(coverageRun.execFile(), current);
        });
    }

    @Override
    public void debug(List<JavaTest> tests,
                      java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
        JavaProjectDescriptor current = host.descriptor();
        BuildSystem build = host.buildSystem();
        if (current == null || build == null) {
            onFinished.accept(null);
            return;
        }
        OutputPanelHandle panel = host.requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
        if (panel != null) {
            panel.clear();
            panel.show();
        }
        host.requestShowRunOutput();
        Runnable previous = host.pendingTestDebug().getAndSet(null);
        if (previous != null) {
            previous.run();
        }
        JavaModule targetModule = moduleOf(tests, current);
        JavaModule sourceModule = tests == null || tests.isEmpty() ? null
                : host.mostSpecificModule(current.modules(), tests.getFirst().file());
        JavaTestRunner runner = host.newTestRunner(current, build);
        BuildToolDebugListener listener;
        try {
            listener = host.openBuildDebugListener(
                    sourceModule == null ? targetModule : sourceModule, runner::cancel,
                    () -> host.hideProgress(TEST_DEBUG_PROGRESS_ID));
        } catch (IOException error) {
            String message = text("error.buildDebugListen",
                    "Nao foi possivel abrir a porta de debug:") + " " + rootMessage(error);
            host.writeOutput(panel, message);
            host.setStatusBarText("Java Debug: " + message);
            onFinished.accept(null);
            return;
        }
        Runnable abort = () -> {
            listener.close();
            runner.cancel();
        };
        host.pendingTestDebug().set(abort);
        host.showProgress(TEST_DEBUG_PROGRESS_ID, text("progress.testDebugBuild",
                "Compilando testes para depurar"), true, abort);
        host.requestSetRunButtonLoading(true);
        host.warmUpDebugAdapter();
        host.background().submit(() -> {
            try {
                JavaTestRunner.TestRun run = runner.debug(tests, targetModule,
                        listener.listenPort(), line -> host.writeOutput(panel, line));
                if (runner.isCancelled()) {
                    host.setStatusBarText(text("status.testDebugStopped",
                            "Java: depuracao de teste interrompida"));
                } else {
                    host.publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
                    host.setStatusBarText("Java: " + run.summary());
                }
                onFinished.accept(run);
            } finally {
                listener.close();
                host.pendingTestDebug().compareAndSet(abort, null);
                host.hideProgress(TEST_DEBUG_PROGRESS_ID);
                host.requestSetRunButtonRunning(host.hasRunningProcess() || host.debugActive());
            }
        });
    }

    private JavaModule moduleOf(List<JavaTest> tests, JavaProjectDescriptor current) {
        if (tests == null || tests.isEmpty()) {
            return current.rootModule();
        }
        return current.modules().stream()
                .filter(module -> module.contains(tests.getFirst().file()))
                .findFirst().orElse(current.rootModule());
    }

    @Override
    public void cancel() {
        JavaTestRunner running = host.activeTestRunner();
        if (running != null) {
            running.cancel();
        }
        BuildSystem build = host.currentBuildSystem();
        if (build != null) {
            build.cancel();
        }
    }

    @Override
    public void openFile(Path file, int line) {
        if (file == null) {
            return;
        }
        host.openAt(file, Math.max(0, line - 1), 0);
    }
}
