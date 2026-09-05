package dtm.ide.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportParserTest {

    @TempDir
    Path root;

    @Test
    void readsPassedFailedErroredAndSkipped() throws IOException {
        Path report = report("target/surefire-reports/TEST-com.example.ATest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.ATest" tests="4" failures="1" errors="1" skipped="1">
                  <testcase name="passa" classname="com.example.ATest" time="0.012"/>
                  <testcase name="falha" classname="com.example.ATest" time="0.5">
                    <failure message="esperado 1 mas foi 2" type="AssertionFailedError">
                    org.opentest4j.AssertionFailedError: esperado 1 mas foi 2
                        at com.example.ATest.falha(ATest.java:20)
                    </failure>
                  </testcase>
                  <testcase name="erra" classname="com.example.ATest" time="0.1">
                    <error message="NullPointerException" type="java.lang.NullPointerException">
                    java.lang.NullPointerException
                    </error>
                  </testcase>
                  <testcase name="pulado" classname="com.example.ATest" time="0">
                    <skipped message="desativado"/>
                  </testcase>
                </testsuite>
                """);

        List<TestResult> results = SurefireReportParser.readReport(report);

        assertEquals(4, results.size());
        assertEquals(TestResult.Status.PASSED, results.getFirst().status());
        assertEquals(TestResult.Status.FAILED, results.get(1).status());
        assertEquals(TestResult.Status.ERROR, results.get(2).status());
        assertEquals(TestResult.Status.SKIPPED, results.get(3).status());
    }

    @Test
    void readsTheFailureMessageAndStackTrace() throws IOException {
        Path report = report("target/surefire-reports/TEST-com.example.ATest.xml", """
                <testsuite name="com.example.ATest">
                  <testcase name="falha" classname="com.example.ATest" time="0.5">
                    <failure message="esperado 1 mas foi 2" type="AssertionFailedError">
                    org.opentest4j.AssertionFailedError: esperado 1 mas foi 2
                        at com.example.ATest.falha(ATest.java:20)
                    </failure>
                  </testcase>
                </testsuite>
                """);

        TestResult result = SurefireReportParser.readReport(report).getFirst();

        assertEquals("esperado 1 mas foi 2", result.message());
        assertTrue(result.stackTrace().contains("ATest.java:20"));
        assertTrue(result.isFailure());
        assertFalse(result.isSuccess());
        assertEquals("com.example.ATest#falha", result.key());
    }

    @Test
    void convertsSecondsToMilliseconds() {
        assertEquals(12, SurefireReportParser.parseDurationMs("0.012"));
        assertEquals(500, SurefireReportParser.parseDurationMs("0.5"));
        assertEquals(1500, SurefireReportParser.parseDurationMs("1.5"));
    }

    @Test
    void acceptsCommaAsDecimalSeparator() {
        assertEquals(500, SurefireReportParser.parseDurationMs("0,5"),
                "o build pode ter rodado numa JVM com localidade pt-BR");
    }

    @Test
    void malformedDurationsBecomeZero() {
        assertEquals(0, SurefireReportParser.parseDurationMs(""));
        assertEquals(0, SurefireReportParser.parseDurationMs(null));
        assertEquals(0, SurefireReportParser.parseDurationMs("nao-e-numero"));
    }

    @Test
    void readsMavenAndGradleReportDirectories() throws IOException {
        report("target/surefire-reports/TEST-com.example.ATest.xml",
                suiteWith("com.example.ATest", "a"));
        report("build/test-results/test/TEST-com.example.BTest.xml",
                suiteWith("com.example.BTest", "b"));

        List<TestResult> results = SurefireReportParser.readModuleReports(root);

        assertEquals(2, results.size());
    }

    @Test
    void ignoresFilesThatAreNotReports() throws IOException {
        report("target/surefire-reports/TEST-com.example.ATest.xml",
                suiteWith("com.example.ATest", "a"));
        report("target/surefire-reports/com.example.ATest.txt", "saida de texto");
        report("target/surefire-reports/outro.xml", suiteWith("com.example.CTest", "c"));

        assertEquals(1, SurefireReportParser.readModuleReports(root).size());
    }

    @Test
    void missingDirectoryYieldsNothing() {
        assertTrue(SurefireReportParser.readModuleReports(root).isEmpty());
        assertTrue(SurefireReportParser.readModuleReports(null).isEmpty());
        assertTrue(SurefireReportParser.readDirectory(root.resolve("nao-existe")).isEmpty());
    }

    @Test
    void truncatedReportsDoNotThrow() throws IOException {
        Path report = report("target/surefire-reports/TEST-com.example.ATest.xml",
                "<testsuite name=\"com.example.ATest\"><testcase name=\"a\"");

        assertTrue(SurefireReportParser.readReport(report).isEmpty());
    }

    @Test
    void clearingRemovesOldReportsSoResultsDoNotMix() throws IOException {
        Path report = report("target/surefire-reports/TEST-com.example.ATest.xml",
                suiteWith("com.example.ATest", "a"));

        SurefireReportParser.clearReports(root);

        assertFalse(Files.exists(report));
        assertTrue(SurefireReportParser.readModuleReports(root).isEmpty());
    }

    private static String suiteWith(String className, String methodName) {
        return """
                <testsuite name="%s">
                  <testcase name="%s" classname="%s" time="0.01"/>
                </testsuite>
                """.formatted(className, methodName, className);
    }

    private Path report(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
