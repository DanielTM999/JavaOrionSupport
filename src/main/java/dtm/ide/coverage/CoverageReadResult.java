package dtm.ide.coverage;

public record CoverageReadResult(CoverageReport report, Failure failure, String detail) {

    public enum Failure {
        NONE,
        MISSING_EXEC,
        UNREADABLE_EXEC,
        UNSUPPORTED_BYTECODE,
        NO_CLASSES
    }

    public CoverageReadResult {
        report = report == null ? CoverageReport.EMPTY : report;
        failure = failure == null ? Failure.NONE : failure;
        detail = detail == null ? "" : detail;
    }

    public static CoverageReadResult of(CoverageReport report) {
        return new CoverageReadResult(report, Failure.NONE, "");
    }

    public static CoverageReadResult failed(Failure failure, String detail) {
        return new CoverageReadResult(CoverageReport.EMPTY, failure, detail);
    }

    public boolean isSuccess() {
        return failure == Failure.NONE;
    }
}
