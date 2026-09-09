package dtm.ide.coverage;

import dtm.ide.index.JavaLexicalSource;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Range;

import java.util.List;

public final class CoverageDisplay {

    private CoverageDisplay() {
    }

    public static String percent(double value) {
        return Math.round(value) + "%";
    }

    public static String summary(FileCoverage coverage) {
        if (coverage == null || coverage.isEmpty()) {
            return "";
        }
        return percent(coverage.linePercentage())
                + " (" + coverage.coveredLines() + "/" + coverage.totalLines() + ")";
    }

    public static String branchSummary(FileCoverage coverage) {
        if (coverage == null || coverage.totalBranches() <= 0) {
            return "";
        }
        return percent(coverage.branchPercentage())
                + " (" + coverage.coveredBranches() + "/" + coverage.totalBranches() + ")";
    }

    public static int lensLineOf(List<DocumentSymbol> outline) {
        if (outline == null) {
            return 0;
        }
        for (DocumentSymbol symbol : outline) {
            if (symbol == null || !JavaLexicalSource.isType(symbol.kind())) {
                continue;
            }
            Range range = symbol.selectionRange() == null ? symbol.range() : symbol.selectionRange();
            if (range != null && range.start() != null) {
                return Math.max(0, range.start().line());
            }
        }
        return 0;
    }
}
