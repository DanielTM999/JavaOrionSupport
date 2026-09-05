package dtm.ide.test;

public record TestResult(
        String className,
        String methodName,
        Status status,
        long durationMs,
        String message,
        String stackTrace
) {

    public enum Status {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED
    }

    public TestResult {
        className = className == null ? "" : className.trim();
        methodName = methodName == null ? "" : methodName.trim();
        status = status == null ? Status.PASSED : status;
        message = message == null ? "" : message.trim();
        stackTrace = stackTrace == null ? "" : stackTrace.strip();
        durationMs = Math.max(0, durationMs);
    }

    public boolean isSuccess() {
        return status == Status.PASSED;
    }

    public boolean isFailure() {
        return status == Status.FAILED || status == Status.ERROR;
    }

    public String key() {
        return className + "#" + methodName;
    }
}
