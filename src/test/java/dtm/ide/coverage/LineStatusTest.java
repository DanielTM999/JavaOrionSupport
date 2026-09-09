package dtm.ide.coverage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineStatusTest {

    @Test
    void mapsEveryJacocoStatus() {
        assertEquals(LineStatus.IRRELEVANT, LineStatus.fromJacoco(LineStatus.JACOCO_EMPTY));
        assertEquals(LineStatus.UNCOVERED, LineStatus.fromJacoco(LineStatus.JACOCO_NOT_COVERED));
        assertEquals(LineStatus.COVERED, LineStatus.fromJacoco(LineStatus.JACOCO_FULLY_COVERED));
        assertEquals(LineStatus.PARTIAL, LineStatus.fromJacoco(LineStatus.JACOCO_PARTLY_COVERED));
    }

    @Test
    void unknownStatusIsTreatedAsIrrelevant() {
        assertEquals(LineStatus.IRRELEVANT, LineStatus.fromJacoco(-1));
        assertEquals(LineStatus.IRRELEVANT, LineStatus.fromJacoco(99));
    }

    @Test
    void onlyExecutableLinesAreDrawn() {
        assertFalse(LineStatus.IRRELEVANT.isExecutable());
        assertTrue(LineStatus.UNCOVERED.isExecutable());
        assertTrue(LineStatus.PARTIAL.isExecutable());
        assertTrue(LineStatus.COVERED.isExecutable());
    }

    @Test
    void partialCountsAsCoveredButUncoveredDoesNot() {
        assertTrue(LineStatus.COVERED.isCovered());
        assertTrue(LineStatus.PARTIAL.isCovered());
        assertFalse(LineStatus.UNCOVERED.isCovered());
        assertFalse(LineStatus.IRRELEVANT.isCovered());
    }
}
