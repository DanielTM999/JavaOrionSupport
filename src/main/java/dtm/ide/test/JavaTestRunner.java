package dtm.ide.test;

import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.BuildToolDebug;
import dtm.ide.build.incremental.IncrementalJavaBuilder;
import dtm.ide.coverage.CoverageAgent;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class JavaTestRunner {

    private static final String REPORTS_DIR = "target/surefire-reports";
    private static final String TEST_CLASSES_DIR = "target/test-classes";

    public record TestRun(BuildResult buildResult, List<TestResult> results) {

        public TestRun {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public long passed() {
            return results.stream().filter(TestResult::isSuccess).count();
        }

        public long failed() {
            return results.stream().filter(TestResult::isFailure).count();
        }

        public long skipped() {
            return results.stream()
                    .filter(result -> result.status() == TestResult.Status.SKIPPED)
                    .count();
        }

        public String summary() {
            if (results.isEmpty()) {
                String build = buildResult == null ? "" : buildResult.summary();
                return build.isEmpty() ? "nenhum teste executado" : build;
            }
            return passed() + " passou, " + failed() + " falhou, " + skipped() + " pulado";
        }
    }

    private final JavaProjectDescriptor descriptor;
    private final BuildSystem buildSystem;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final JUnitPlatformLauncher platform;
    private volatile IncrementalJavaBuilder activeBuilder;
    private volatile boolean cancelled;

    public JavaTestRunner(JavaProjectDescriptor descriptor, BuildSystem buildSystem) {
        this(descriptor, buildSystem, null, null);
    }

    public JavaTestRunner(JavaProjectDescriptor descriptor, BuildSystem buildSystem,
                          Supplier<JdkInstallation> jdkSupplier, JUnitPlatformLauncher platform) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
        this.jdkSupplier = jdkSupplier;
        this.platform = platform;
    }

    private JavaModule moduleOfTests(List<JavaTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        JavaModule found = null;
        for (JavaTest test : tests) {
            if (test.file() == null) {
                return null;
            }
            JavaModule owner = descriptor.modules().stream()
                    .filter(candidate -> !candidate.isAggregator() && candidate.contains(test.file()))
                    .findFirst().orElse(null);
            if (owner == null || (found != null && !found.equals(owner))) {
                return null;
            }
            found = owner;
        }
        return found;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
        IncrementalJavaBuilder builder = activeBuilder;
        if (builder != null) {
            builder.cancel();
        }
        if (platform != null) {
            platform.cancel();
        }
        if (buildSystem != null) {
            buildSystem.cancel();
        }
    }

    Optional<TestRun> runOnPlatform(List<JavaTest> tests, JavaModule requested,
                                    List<String> jvmArguments, Consumer<String> output) {
        JavaModule module = requested != null && requested.isAggregator()
                ? moduleOfTests(tests) : requested;
        if (platform == null || jdkSupplier == null || descriptor == null || buildSystem == null
                || module == null || !descriptor.isMaven() || descriptor.isGradle()
                || !JUnitPlatformLauncher.selectable(tests)
                || JUnitPlatformLauncher.surefireCustomized(module.root().resolve("pom.xml"),
                        descriptor.root().resolve("pom.xml"))) {
            return Optional.empty();
        }
        JdkInstallation jdk = jdkSupplier.get();
        IncrementalJavaBuilder builder = new IncrementalJavaBuilder(descriptor, () -> buildSystem,
                jdkSupplier);
        if (jdk == null || !builder.isApplicable(module)) {
            return Optional.empty();
        }
        Optional<String> classpath = buildSystem.resolveTestClasspath(module);
        if (classpath.isEmpty() || JUnitPlatformLauncher.usesTestNg(classpath.get())
                || !JUnitPlatformLauncher.hasJUnitEngine(classpath.get())) {
            return Optional.empty();
        }
        Optional<String> version = JUnitPlatformLauncher.platformVersion(classpath.get());
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<Path>> consoleJars = platform.consoleJars(version.get(), output);
        if (consoleJars.isEmpty()) {
            return Optional.empty();
        }
        Instant started = Instant.now();
        activeBuilder = builder;
        BuildResult build;
        try {
            build = builder.build(module, true, output);
        } finally {
            activeBuilder = null;
        }
        if (!build.successful() || cancelled) {
            return Optional.of(new TestRun(build, List.of()));
        }
        Path reports = module.root().resolve(REPORTS_DIR);
        SurefireReportParser.clearReports(module.root());
        List<String> command = JUnitPlatformLauncher.command(jdk, version.get(), classpath.get(),
                consoleJars.get(), jvmArguments, tests, module.root(),
                module.root().resolve(TEST_CLASSES_DIR), reports);
        int exitCode = platform.execute(command, module.root(),
                Map.of("JAVA_HOME", jdk.home().toString()), output);
        List<TestResult> results = SurefireReportParser.readModuleReports(module.root());
        if (results.isEmpty() && exitCode != 0 && exitCode != 1 && !cancelled) {
            log.info("JUnit Platform encerrou com codigo {} sem relatorios; usando o Maven", exitCode);
            return Optional.empty();
        }
        BuildResult result = new BuildResult(exitCode, build.diagnostics(),
                Duration.between(started, Instant.now()), "junit-platform " + version.get());
        return Optional.of(new TestRun(result, results));
    }

    public TestRun runAll(JavaModule module, Consumer<String> output) {
        return run(List.of(), module, output);
    }

    /** Executa um escopo de configuracao ({@code java.test}) em vez de uma lista de testes. */
    public TestRun run(TestScope scope, String target, JavaModule module,
                       Consumer<String> output) {
        if (buildSystem == null || descriptor == null) {
            return new TestRun(null, List.of());
        }
        JavaModule resolved = module == null ? descriptor.rootModule() : module;
        Path reportRoot = resolved == null ? descriptor.root() : resolved.root();
        SurefireReportParser.clearReports(reportRoot);

        if (scope == null || scope == TestScope.ALL || scope == TestScope.CLASS || scope == TestScope.METHOD) {
            List<JavaTest> selected = scope == null || scope == TestScope.ALL
                    ? List.of() : TestSelectors.asTests(scope, target);
            if (scope == null || scope == TestScope.ALL || !selected.isEmpty()) {
                Optional<TestRun> fast = runOnPlatform(selected, resolved, List.of(), output);
                if (fast.isPresent()) {
                    return fast.get();
                }
            }
        }
        if (cancelled) {
            return new TestRun(null, List.of());
        }

        BuildResult result = buildSystem.execute(
                BuildRequest.of(BuildSystem.BuildAction.TEST, resolved)
                        .withArguments(TestSelectors.forBuildTool(
                                descriptor.isGradle(), scope, target)),
                output);
        return new TestRun(result, SurefireReportParser.readModuleReports(reportRoot));
    }

    public TestRun run(List<JavaTest> tests, JavaModule module, Consumer<String> output) {
        if (buildSystem == null || descriptor == null) {
            return new TestRun(null, List.of());
        }
        JavaModule target = module == null ? descriptor.rootModule() : module;
        SurefireReportParser.clearReports(target == null ? descriptor.root() : target.root());

        Optional<TestRun> fast = runOnPlatform(tests, target, List.of(), output);
        if (fast.isPresent()) {
            return fast.get();
        }
        if (cancelled) {
            return new TestRun(null, List.of());
        }

        BuildRequest request = BuildRequest.of(BuildSystem.BuildAction.TEST, target)
                .withArguments(selectorArguments(tests));

        BuildResult buildResult = buildSystem.execute(request, output);
        List<TestResult> results = SurefireReportParser.readModuleReports(
                target == null ? descriptor.root() : target.root());

        return new TestRun(buildResult, results);
    }

    public record CoverageRun(TestRun testRun, Path execFile) {

        public CoverageRun {
            testRun = testRun == null ? new TestRun(null, List.of()) : testRun;
        }

        public boolean hasExecFile() {
            return execFile != null && Files.isRegularFile(execFile);
        }
    }

    public CoverageRun runWithCoverage(List<JavaTest> tests, JavaModule module, Path agentJar,
                                       Consumer<String> output) {
        if (buildSystem == null || descriptor == null || agentJar == null) {
            return new CoverageRun(null, null);
        }
        JavaModule target = module == null ? descriptor.rootModule() : module;
        Path reportRoot = target == null ? descriptor.root() : target.root();
        SurefireReportParser.clearReports(reportRoot);

        Path execFile = CoverageAgent.execFileFor(reportRoot);
        if (!prepareExecFile(execFile, output)) {
            return new CoverageRun(null, null);
        }
        Optional<TestRun> fast = runOnPlatform(tests, target,
                List.of(CoverageAgent.agentArgument(agentJar, execFile, false)), output);
        if (fast.isPresent()) {
            return new CoverageRun(fast.get(), execFile);
        }
        if (cancelled) {
            return new CoverageRun(null, null);
        }

        List<String> arguments = new ArrayList<>(selectorArguments(tests));
        Path initScript = null;
        try {
            if (descriptor.isGradle()) {
                initScript = Files.createTempFile("orion-gradle-coverage", ".gradle");
                Files.writeString(initScript, CoverageAgent.gradleInitScript(agentJar, execFile));
                arguments.add("--init-script");
                arguments.add(initScript.toString());
            } else {
                arguments.addAll(CoverageAgent.mavenArguments(agentJar, execFile));
            }
            BuildResult result = buildSystem.execute(
                    BuildRequest.of(BuildSystem.BuildAction.TEST, target).withArguments(arguments),
                    output);
            return new CoverageRun(
                    new TestRun(result, SurefireReportParser.readModuleReports(reportRoot)),
                    execFile);
        } catch (Exception error) {
            if (output != null) {
                output.accept(error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage());
            }
            return new CoverageRun(null, null);
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean prepareExecFile(Path execFile, Consumer<String> output) {
        if (execFile == null) {
            return false;
        }
        try {
            Files.createDirectories(execFile.getParent());
            Files.deleteIfExists(execFile);
            return true;
        } catch (Exception error) {
            if (output != null) {
                output.accept(error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage());
            }
            return false;
        }
    }

    public TestRun debug(List<JavaTest> tests, JavaModule module, int listenPort,
                         Consumer<String> output) {
        if (buildSystem == null || descriptor == null || listenPort <= 0) {
            return new TestRun(null, List.of());
        }
        JavaModule target = module == null ? descriptor.rootModule() : module;
        Path reportRoot = target == null ? descriptor.root() : target.root();
        SurefireReportParser.clearReports(reportRoot);
        String agent = BuildToolDebug.listenAgent(listenPort);
        Optional<TestRun> fast = runOnPlatform(tests, target, List.of(agent), output);
        if (fast.isPresent()) {
            return fast.get();
        }
        if (cancelled) {
            return new TestRun(null, List.of());
        }
        List<String> arguments = new ArrayList<>(selectorArguments(tests));
        try {
            if (descriptor.isGradle()) {
                arguments.addAll(BuildToolDebug.gradleTestDebugArguments(agent));
            } else {
                arguments.add("-DforkCount=1");
                arguments.add("-Dmaven.surefire.debug=" + agent);
            }
            BuildResult result = buildSystem.execute(
                    BuildRequest.of(BuildSystem.BuildAction.TEST, target).withArguments(arguments),
                    output);
            return new TestRun(result, SurefireReportParser.readModuleReports(reportRoot));
        } catch (Exception error) {
            if (output != null) {
                output.accept(error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage());
            }
            return new TestRun(null, List.of());
        }
    }

    List<String> selectorArguments(List<JavaTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        if (descriptor.isGradle()) {
            for (JavaTest test : tests) {
                arguments.add("--tests");
                arguments.add(test.isClassLevel()
                        ? test.className()
                        : test.className() + "." + test.methodName());
            }
            return arguments;
        }
        List<String> selectors = tests.stream().map(JavaTest::selector).toList();
        arguments.add("-Dtest=" + String.join(",", selectors));
        arguments.add("-DfailIfNoTests=false");
        return arguments;
    }
}
