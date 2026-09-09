package dtm.ide.coverage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageReportTest {

    private static FileCoverage coverage(Path file, Map<Integer, LineStatus> lines,
                                         int coveredBranches, int totalBranches) {
        return new FileCoverage(file, lines, coveredBranches, totalBranches);
    }

    private static Map<Integer, LineStatus> lines(Object... pairs) {
        Map<Integer, LineStatus> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((Integer) pairs[index], (LineStatus) pairs[index + 1]);
        }
        return map;
    }

    @Test
    void irrelevantLinesAreNotStoredAsExecutable() {
        FileCoverage file = coverage(Path.of("Foo.java"),
                lines(1, LineStatus.IRRELEVANT, 2, LineStatus.COVERED), 0, 0);

        assertEquals(1, file.totalLines());
        assertEquals(LineStatus.IRRELEVANT, file.statusAt(1));
        assertEquals(LineStatus.COVERED, file.statusAt(2));
    }

    @Test
    void partialLinesCountAsCovered() {
        FileCoverage file = coverage(Path.of("Foo.java"),
                lines(1, LineStatus.COVERED, 2, LineStatus.PARTIAL, 3, LineStatus.UNCOVERED), 1, 2);

        assertEquals(3, file.totalLines());
        assertEquals(2, file.coveredLines());
        assertEquals(50d, file.branchPercentage());
    }

    @Test
    void emptyFileHasZeroPercentageInsteadOfDividingByZero() {
        FileCoverage file = coverage(Path.of("Empty.java"), Map.of(), 0, 0);

        assertTrue(file.isEmpty());
        assertEquals(0d, file.linePercentage());
        assertEquals(0d, file.branchPercentage());
    }

    @Test
    void coveredBranchesNeverExceedTheTotal() {
        FileCoverage file = coverage(Path.of("Foo.java"), lines(1, LineStatus.COVERED), 9, 2);

        assertEquals(2, file.coveredBranches());
    }

    @Test
    void totalsAggregateEveryClass() {
        CoverageReport report = new CoverageReport(Map.of(), Map.of(
                "com.app.A", coverage(null, lines(1, LineStatus.COVERED, 2, LineStatus.UNCOVERED), 1, 2),
                "com.app.B", coverage(null, lines(1, LineStatus.COVERED, 2, LineStatus.COVERED), 2, 2)));

        CoverageReport.Totals totals = report.totals();

        assertEquals(3, totals.coveredLines());
        assertEquals(4, totals.totalLines());
        assertEquals(75d, totals.linePercentage());
        assertEquals(3, totals.coveredBranches());
        assertEquals(4, totals.totalBranches());
    }

    @Test
    void packageTotalsOnlyCountTheirOwnPackage() {
        CoverageReport report = new CoverageReport(Map.of(), Map.of(
                "com.app.web.A", coverage(null, lines(1, LineStatus.COVERED), 0, 0),
                "com.app.core.B", coverage(null, lines(1, LineStatus.UNCOVERED, 2, LineStatus.UNCOVERED), 0, 0)));

        assertEquals(100d, report.forPackage("com.app.web").linePercentage());
        assertEquals(0d, report.forPackage("com.app.core").linePercentage());
        assertTrue(report.forPackage("com.app.missing").isEmpty());
    }

    @Test
    void defaultPackageOnlyCountsClassesWithoutAPackage() {
        CoverageReport report = new CoverageReport(Map.of(), Map.of(
                "Loose", coverage(null, lines(1, LineStatus.COVERED), 0, 0),
                "com.app.A", coverage(null, lines(1, LineStatus.UNCOVERED,
                        2, LineStatus.UNCOVERED, 3, LineStatus.UNCOVERED), 0, 0)));

        CoverageReport.Totals totals = report.forPackage("");

        assertEquals(1, totals.totalLines());
        assertEquals(100d, totals.linePercentage());
    }

    @Test
    void nestedClassesFallBackToTheirTopLevelSource() {
        FileCoverage outer = coverage(null, lines(1, LineStatus.COVERED), 0, 0);
        CoverageReport report = new CoverageReport(Map.of(), Map.of("com.app.Outer", outer));

        assertTrue(report.forClass("com.app.Outer").isPresent());
        assertTrue(report.forClass("com.app.Outer$Inner").isPresent());
        assertTrue(report.forClass("com.app.Other").isEmpty());
        assertTrue(report.forClass(null).isEmpty());
    }

    @Test
    void filesAreLookedUpByNormalizedAbsolutePath() {
        Path file = Path.of("src", "main", "java", "Foo.java").toAbsolutePath();
        FileCoverage value = coverage(file, lines(1, LineStatus.COVERED), 0, 0);
        CoverageReport report = new CoverageReport(Map.of(file, value), Map.of());

        assertTrue(report.forFile(file).isPresent());
        assertTrue(report.forFile(file.getParent().resolve(".").resolve("Foo.java")).isPresent());
        assertTrue(report.forFile(null).isEmpty());
    }

    @Test
    void emptyReportReportsItself() {
        assertTrue(CoverageReport.EMPTY.isEmpty());
        assertTrue(CoverageReport.EMPTY.totals().isEmpty());
        assertFalse(CoverageReport.EMPTY.forFile(Path.of("Foo.java")).isPresent());
    }
}
