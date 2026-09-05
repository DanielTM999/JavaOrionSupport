package dtm.ide.test;

import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class JavaTestRunner {

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
                return buildResult == null ? "nenhum teste executado" : buildResult.summary();
            }
            return passed() + " passou, " + failed() + " falhou, " + skipped() + " pulado";
        }
    }

    private final JavaProjectDescriptor descriptor;
    private final BuildSystem buildSystem;

    public JavaTestRunner(JavaProjectDescriptor descriptor, BuildSystem buildSystem) {
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
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

        BuildRequest request = BuildRequest.of(BuildSystem.BuildAction.TEST, target)
                .withArguments(selectorArguments(tests));

        BuildResult buildResult = buildSystem.execute(request, output);
        List<TestResult> results = SurefireReportParser.readModuleReports(
                target == null ? descriptor.root() : target.root());

        return new TestRun(buildResult, results);
    }

    public TestRun debug(List<JavaTest> tests, JavaModule module, int debugPort,
                         Consumer<String> output) {
        if (buildSystem == null || descriptor == null || debugPort <= 0) {
            return new TestRun(null, List.of());
        }
        JavaModule target = module == null ? descriptor.rootModule() : module;
        Path reportRoot = target == null ? descriptor.root() : target.root();
        SurefireReportParser.clearReports(reportRoot);
        List<String> arguments = new ArrayList<>(selectorArguments(tests));
        Path initScript = null;
        try {
            if (descriptor.isGradle()) {
                initScript = Files.createTempFile("orion-gradle-test-debug", ".gradle");
                String agent = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:%d"
                        .formatted(debugPort);
                Files.writeString(initScript, """
                        allprojects {
                            tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                                maxParallelForks = 1
                                jvmArgs '%s'
                            }
                        }
                        """.formatted(agent));
                arguments.add("--init-script");
                arguments.add(initScript.toString());
            } else {
                arguments.add("-DforkCount=1");
                arguments.add("-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,"
                        + "server=y,suspend=y,address=*:%d".formatted(debugPort));
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
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (Exception ignored) {
                }
            }
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
