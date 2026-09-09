package dtm.ide.coverage;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class CoverageReport {

    public record Totals(int coveredLines, int totalLines, int coveredBranches, int totalBranches) {

        public static final Totals EMPTY = new Totals(0, 0, 0, 0);

        public Totals plus(Totals other) {
            if (other == null) {
                return this;
            }
            return new Totals(
                    coveredLines + other.coveredLines,
                    totalLines + other.totalLines,
                    coveredBranches + other.coveredBranches,
                    totalBranches + other.totalBranches);
        }

        public double linePercentage() {
            return percentage(coveredLines, totalLines);
        }

        public double branchPercentage() {
            return percentage(coveredBranches, totalBranches);
        }

        public boolean isEmpty() {
            return totalLines == 0 && totalBranches == 0;
        }
    }

    public static final CoverageReport EMPTY = new CoverageReport(Map.of(), Map.of());

    private final Map<Path, FileCoverage> byFile;
    private final Map<String, FileCoverage> byClass;

    public CoverageReport(Map<Path, FileCoverage> byFile, Map<String, FileCoverage> byClass) {
        Map<Path, FileCoverage> files = new LinkedHashMap<>();
        if (byFile != null) {
            byFile.forEach((path, coverage) -> {
                if (path != null && coverage != null) {
                    files.put(path.toAbsolutePath().normalize(), coverage);
                }
            });
        }
        Map<String, FileCoverage> classes = new LinkedHashMap<>();
        if (byClass != null) {
            byClass.forEach((name, coverage) -> {
                if (name != null && !name.isBlank() && coverage != null) {
                    classes.put(name, coverage);
                }
            });
        }
        this.byFile = Map.copyOf(files);
        this.byClass = Map.copyOf(classes);
    }

    public boolean isEmpty() {
        return byFile.isEmpty() && byClass.isEmpty();
    }

    public Optional<FileCoverage> forFile(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFile.get(file.toAbsolutePath().normalize()));
    }

    public Optional<FileCoverage> forClass(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Optional.empty();
        }
        FileCoverage direct = byClass.get(qualifiedName);
        if (direct != null) {
            return Optional.of(direct);
        }
        return Optional.ofNullable(byClass.get(topLevelNameOf(qualifiedName)));
    }

    public Totals forPackage(String packageName) {
        String prefix = packageName == null ? "" : packageName.trim();
        return totalsOf(byClass.entrySet().stream()
                .filter(entry -> packageOf(entry.getKey()).equals(prefix))
                .map(Map.Entry::getValue)
                .toList());
    }

    public Totals totals() {
        return totalsOf(byClass.values());
    }

    public Map<Path, FileCoverage> files() {
        return byFile;
    }

    public Map<String, FileCoverage> classes() {
        return byClass;
    }

    static Totals totalsOf(Collection<FileCoverage> coverages) {
        Totals accumulated = Totals.EMPTY;
        if (coverages == null) {
            return accumulated;
        }
        for (FileCoverage coverage : coverages) {
            if (coverage == null) {
                continue;
            }
            accumulated = accumulated.plus(new Totals(
                    coverage.coveredLines(), coverage.totalLines(),
                    coverage.coveredBranches(), coverage.totalBranches()));
        }
        return accumulated;
    }

    static String topLevelNameOf(String qualifiedName) {
        int nested = qualifiedName.indexOf('$');
        return nested < 0 ? qualifiedName : qualifiedName.substring(0, nested);
    }

    static String packageOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }

    static double percentage(int covered, int total) {
        return total <= 0 ? 0d : (covered * 100d) / total;
    }
}
