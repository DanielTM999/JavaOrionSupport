package dtm.ide.test;

import dtm.ide.build.BuildResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTestProblemsTest {

    @Test
    void addsANavigableProblemForATestFailure() {
        Path file = Path.of("src/test/java/example/ServiceTest.java").toAbsolutePath();
        JavaTest test = new JavaTest("example.ServiceTest", "calculates", "", file, 12, false);
        TestResult failure = new TestResult("example.ServiceTest", "calculates",
                TestResult.Status.FAILED, 4, "expected 2 but was 3",
                "at example.ServiceTest.calculates(ServiceTest.java:27)");
        BuildResult build = new BuildResult(1, List.of(), Duration.ofMillis(4), "mvn test");

        BuildResult enriched = JavaTestProblems.withTestFailures(
                new JavaTestRunner.TestRun(build, List.of(failure)), List.of(test));

        assertEquals(1, enriched.diagnostics().size());
        assertEquals(file, enriched.diagnostics().getFirst().file());
        assertEquals(27, enriched.diagnostics().getFirst().line());
        assertEquals("JUnit", enriched.diagnostics().getFirst().source());
        assertTrue(enriched.diagnostics().getFirst().message().contains("expected 2"));
    }
}
