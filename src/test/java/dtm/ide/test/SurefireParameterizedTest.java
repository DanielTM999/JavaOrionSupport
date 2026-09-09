package dtm.ide.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireParameterizedTest {

    @Test
    void stripsParameterSignatureAndInvocationIndex() {
        assertEquals("calculaQuantidade",
                SurefireReportParser.baseMethodName("calculaQuantidade(int, String)[1]"));
        assertEquals("calculaQuantidade",
                SurefireReportParser.baseMethodName("calculaQuantidade[3]"));
        assertEquals("calculaTotalComDesconto",
                SurefireReportParser.baseMethodName("calculaTotalComDesconto"));
    }

    @Test
    void toleratesMissingOrOddNames() {
        assertEquals("", SurefireReportParser.baseMethodName(null));
        assertEquals("", SurefireReportParser.baseMethodName("   "));
        assertEquals("", SurefireReportParser.baseMethodName("[1]"));
    }

    @Test
    void mergesEveryInvocationIntoASingleResult() {
        List<TestResult> merged = SurefireReportParser.mergeInvocations(List.of(
                new TestResult("A", "param", TestResult.Status.PASSED, 10, "", ""),
                new TestResult("A", "param", TestResult.Status.PASSED, 15, "", "")));

        assertEquals(1, merged.size());
        assertEquals(25, merged.getFirst().durationMs());
        assertEquals(TestResult.Status.PASSED, merged.getFirst().status());
    }

    @Test
    void oneFailedInvocationFailsTheWholeTest() {
        List<TestResult> merged = SurefireReportParser.mergeInvocations(List.of(
                new TestResult("A", "param", TestResult.Status.PASSED, 10, "", ""),
                new TestResult("A", "param", TestResult.Status.FAILED, 5, "boom", "trace"),
                new TestResult("A", "param", TestResult.Status.PASSED, 10, "", "")));

        assertEquals(1, merged.size());
        assertEquals(TestResult.Status.FAILED, merged.getFirst().status());
        assertEquals("boom", merged.getFirst().message());
    }

    @Test
    void errorOutranksFailure() {
        List<TestResult> merged = SurefireReportParser.mergeInvocations(List.of(
                new TestResult("A", "param", TestResult.Status.FAILED, 1, "f", ""),
                new TestResult("A", "param", TestResult.Status.ERROR, 1, "e", "")));

        assertEquals(TestResult.Status.ERROR, merged.getFirst().status());
    }

    @Test
    void differentMethodsAreNotMerged() {
        List<TestResult> merged = SurefireReportParser.mergeInvocations(List.of(
                new TestResult("A", "one", TestResult.Status.PASSED, 1, "", ""),
                new TestResult("A", "two", TestResult.Status.PASSED, 1, "", ""),
                new TestResult("B", "one", TestResult.Status.PASSED, 1, "", "")));

        assertEquals(3, merged.size());
    }

    @Test
    void parameterizedReportMatchesTheDiscoveredMethodKey(@TempDir Path root) throws Exception {
        Path report = root.resolve("TEST-com.app.FooTest.xml");
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.app.FooTest" tests="4">
                  <testcase name="plain" classname="com.app.FooTest" time="0.01"/>
                  <testcase name="param(int, String)[1]" classname="com.app.FooTest" time="0.01"/>
                  <testcase name="param(int, String)[2]" classname="com.app.FooTest" time="0.02"/>
                  <testcase name="param(int, String)[3]" classname="com.app.FooTest" time="0.01"/>
                </testsuite>
                """);

        List<TestResult> results = SurefireReportParser.readReport(report);

        assertEquals(2, results.size());
        TestResult param = results.stream()
                .filter(result -> "param".equals(result.methodName()))
                .findFirst().orElse(null);
        assertNotNull(param, "o teste parametrizado precisa aparecer pelo nome do metodo");
        assertEquals("com.app.FooTest#param", param.key());
        assertEquals(40, param.durationMs());

        JavaTest discovered = new JavaTest("com.app.FooTest", "param", "param",
                Path.of("FooTest.java"), 10, true);
        assertTrue(results.stream().anyMatch(result -> result.key().equals(
                discovered.className() + "#" + discovered.methodName())));
    }

    @Test
    void nestedClassResultsKeepTheirOwnClassName(@TempDir Path root) throws Exception {
        Path report = root.resolve("TEST-com.app.FooTest$Inner.xml");
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.app.FooTest$Inner" tests="1">
                  <testcase name="nested" classname="com.app.FooTest$Inner" time="0.01"/>
                </testsuite>
                """);

        List<TestResult> results = SurefireReportParser.readReport(report);

        assertEquals(1, results.size());
        assertEquals("com.app.FooTest$Inner#nested", results.getFirst().key());
    }
}
