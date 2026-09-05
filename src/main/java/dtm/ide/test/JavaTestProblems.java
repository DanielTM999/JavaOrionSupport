package dtm.ide.test;

import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildResult;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts test report failures to navigable problems while retaining compiler diagnostics. */
public final class JavaTestProblems {

    private JavaTestProblems() {
    }

    public static BuildResult withTestFailures(JavaTestRunner.TestRun run, List<JavaTest> tests) {
        if (run == null) {
            return null;
        }
        BuildResult build = run.buildResult();
        List<BuildDiagnostic> problems = new ArrayList<>(
                build == null ? List.of() : build.diagnostics());
        for (TestResult result : run.results()) {
            if (!result.isFailure()) {
                continue;
            }
            JavaTest test = findTest(tests, result);
            Path file = test == null ? null : test.file();
            int line = failureLine(result.stackTrace(), file == null ? null : file.getFileName());
            if (line == 0 && test != null) {
                line = test.line();
            }
            String selector = result.className() + (result.methodName().isBlank()
                    ? "" : "#" + result.methodName());
            String reason = result.message().isBlank() ? result.status().name() : result.message();
            problems.add(new BuildDiagnostic(file, line, 1, DiagnosticSeverity.ERROR,
                    selector + ": " + reason, "JUnit"));
        }
        boolean testFailed = run.results().stream().anyMatch(TestResult::isFailure);
        int exitCode = build == null ? (testFailed ? 1 : 0)
                : testFailed ? Math.max(1, build.exitCode()) : build.exitCode();
        Duration duration = build == null ? Duration.ZERO : build.duration();
        String command = build == null ? "tests" : build.command();
        return new BuildResult(exitCode, problems, duration, command);
    }

    private static JavaTest findTest(List<JavaTest> tests, TestResult result) {
        if (tests == null) {
            return null;
        }
        return tests.stream().filter(test -> test.className().equals(result.className()))
                .filter(test -> test.methodName().isBlank()
                        || result.methodName().equals(test.methodName())
                        || result.methodName().startsWith(test.methodName() + "("))
                .findFirst()
                .orElseGet(() -> tests.stream()
                        .filter(test -> test.className().equals(result.className()))
                        .findFirst().orElse(null));
    }

    static int failureLine(String stackTrace, Path fileName) {
        if (stackTrace == null || stackTrace.isBlank() || fileName == null) {
            return 0;
        }
        Pattern location = Pattern.compile("\\(" + Pattern.quote(fileName.toString())
                + ":(\\d+)\\)");
        Matcher matcher = location.matcher(stackTrace);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
