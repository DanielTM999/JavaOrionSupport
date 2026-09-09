package dtm.ide.coverage;

import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class FileCoverage {

    private final Path file;
    private final SortedMap<Integer, LineStatus> lines;
    private final int coveredBranches;
    private final int totalBranches;

    public FileCoverage(Path file, Map<Integer, LineStatus> lines,
                        int coveredBranches, int totalBranches) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        SortedMap<Integer, LineStatus> copy = new TreeMap<>();
        if (lines != null) {
            lines.forEach((line, status) -> {
                if (line != null && line > 0 && status != null && status.isExecutable()) {
                    copy.put(line, status);
                }
            });
        }
        this.lines = java.util.Collections.unmodifiableSortedMap(copy);
        this.totalBranches = Math.max(0, totalBranches);
        this.coveredBranches = Math.min(Math.max(0, coveredBranches), this.totalBranches);
    }

    public Path file() {
        return file;
    }

    public SortedMap<Integer, LineStatus> lines() {
        return lines;
    }

    public LineStatus statusAt(int line) {
        return lines.getOrDefault(line, LineStatus.IRRELEVANT);
    }

    public int totalLines() {
        return lines.size();
    }

    public int coveredLines() {
        return (int) lines.values().stream().filter(LineStatus::isCovered).count();
    }

    public int coveredBranches() {
        return coveredBranches;
    }

    public int totalBranches() {
        return totalBranches;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public double linePercentage() {
        return CoverageReport.percentage(coveredLines(), totalLines());
    }

    public double branchPercentage() {
        return CoverageReport.percentage(coveredBranches, totalBranches);
    }
}
