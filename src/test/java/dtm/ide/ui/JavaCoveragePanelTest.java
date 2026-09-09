package dtm.ide.ui;

import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.FileCoverage;
import dtm.ide.coverage.LineStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCoveragePanelTest {

    private static FileCoverage coverage(Path file, int covered, int uncovered,
                                         int coveredBranches, int totalBranches) {
        Map<Integer, LineStatus> lines = new LinkedHashMap<>();
        int line = 1;
        for (int i = 0; i < covered; i++) {
            lines.put(line++, LineStatus.COVERED);
        }
        for (int i = 0; i < uncovered; i++) {
            lines.put(line++, LineStatus.UNCOVERED);
        }
        return new FileCoverage(file, lines, coveredBranches, totalBranches);
    }

    private static CoverageReport report(Map<String, FileCoverage> classes) {
        return new CoverageReport(Map.of(), classes);
    }

    @Test
    void listsWorstCoverageFirst() {
        Map<String, FileCoverage> classes = new LinkedHashMap<>();
        classes.put("com.app.Good", coverage(null, 9, 1, 0, 0));
        classes.put("com.app.Bad", coverage(null, 1, 9, 0, 0));
        classes.put("com.app.Half", coverage(null, 5, 5, 0, 0));

        List<JavaCoveragePanel.Row> rows = JavaCoveragePanel.rowsOf(report(classes));

        assertEquals(3, rows.size());
        assertEquals("com.app.Bad", rows.get(0).qualifiedName());
        assertEquals("com.app.Half", rows.get(1).qualifiedName());
        assertEquals("com.app.Good", rows.get(2).qualifiedName());
    }

    @Test
    void skipsClassesWithoutExecutableLines() {
        Map<String, FileCoverage> classes = new LinkedHashMap<>();
        classes.put("com.app.Empty", coverage(null, 0, 0, 0, 0));
        classes.put("com.app.Real", coverage(null, 1, 1, 0, 0));

        List<JavaCoveragePanel.Row> rows = JavaCoveragePanel.rowsOf(report(classes));

        assertEquals(1, rows.size());
        assertEquals("com.app.Real", rows.getFirst().qualifiedName());
    }

    @Test
    void splitsQualifiedNameIntoPackageAndSimpleName() {
        JavaCoveragePanel.Row row = JavaCoveragePanel.rowsOf(report(
                Map.of("com.app.web.Controller", coverage(null, 1, 1, 0, 0)))).getFirst();

        assertEquals("Controller", row.simpleName());
        assertEquals("com.app.web", row.packageName());
    }

    @Test
    void defaultPackageHasNoPackageLabel() {
        JavaCoveragePanel.Row row = JavaCoveragePanel.rowsOf(report(
                Map.of("Loose", coverage(null, 1, 1, 0, 0)))).getFirst();

        assertEquals("Loose", row.simpleName());
        assertEquals("", row.packageName());
    }

    @Test
    void percentagesComeFromTheCounters() {
        JavaCoveragePanel.Row row = JavaCoveragePanel.rowsOf(report(
                Map.of("com.app.A", coverage(null, 3, 1, 1, 4)))).getFirst();

        assertEquals(75d, row.linePercentage());
        assertEquals(25d, row.branchPercentage());
    }

    @Test
    void zeroTotalsNeverDivideByZero() {
        JavaCoveragePanel.Row row = new JavaCoveragePanel.Row("A", null, 0, 0, 0, 0);

        assertEquals(0d, row.linePercentage());
        assertEquals(0d, row.branchPercentage());
    }

    @Test
    void keepsTheResolvedSourceFileForNavigation() {
        Path file = Path.of("src", "main", "java", "Foo.java").toAbsolutePath();
        JavaCoveragePanel.Row row = JavaCoveragePanel.rowsOf(report(
                Map.of("com.app.Foo", coverage(file, 1, 1, 0, 0)))).getFirst();

        assertEquals(file, row.file());
    }

    @Test
    void unresolvedSourceKeepsANullFileInsteadOfFailing() {
        JavaCoveragePanel.Row row = JavaCoveragePanel.rowsOf(report(
                Map.of("com.app.Foo", coverage(null, 1, 1, 0, 0)))).getFirst();

        assertNull(row.file());
    }

    @Test
    void emptyReportProducesNoRows() {
        assertTrue(JavaCoveragePanel.rowsOf(null).isEmpty());
        assertTrue(JavaCoveragePanel.rowsOf(CoverageReport.EMPTY).isEmpty());
    }

    @Test
    void colorBandsFollowTheUsualThresholds() {
        assertEquals(JavaCoveragePanel.colorFor(95d), JavaCoveragePanel.colorFor(80d));
        assertEquals(JavaCoveragePanel.colorFor(60d), JavaCoveragePanel.colorFor(50d));
        assertEquals(JavaCoveragePanel.colorFor(10d), JavaCoveragePanel.colorFor(0d));
        assertTrue(JavaCoveragePanel.colorFor(80d) != JavaCoveragePanel.colorFor(79d));
        assertTrue(JavaCoveragePanel.colorFor(50d) != JavaCoveragePanel.colorFor(49d));
    }
}
