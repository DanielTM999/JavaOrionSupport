package dtm.ide.coverage;

public enum LineStatus {

    IRRELEVANT,
    UNCOVERED,
    PARTIAL,
    COVERED;

    public static final int JACOCO_EMPTY = 0;
    public static final int JACOCO_NOT_COVERED = 1;
    public static final int JACOCO_FULLY_COVERED = 2;
    public static final int JACOCO_PARTLY_COVERED = 3;

    public static LineStatus fromJacoco(int status) {
        return switch (status) {
            case JACOCO_NOT_COVERED -> UNCOVERED;
            case JACOCO_FULLY_COVERED -> COVERED;
            case JACOCO_PARTLY_COVERED -> PARTIAL;
            default -> IRRELEVANT;
        };
    }

    public boolean isExecutable() {
        return this != IRRELEVANT;
    }

    public boolean isCovered() {
        return this == COVERED || this == PARTIAL;
    }
}
