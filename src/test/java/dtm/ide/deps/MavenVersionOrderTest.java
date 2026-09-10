package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenVersionOrderTest {

    @Test
    void sortsNumericVersionsNaturallyInDescendingOrder() {
        List<String> versions = new ArrayList<>(List.of("2.0.0", "10.0.0", "1.12.0"));
        versions.sort(MavenVersionOrder.DESCENDING);
        assertEquals(List.of("10.0.0", "2.0.0", "1.12.0"), versions);
    }

    @Test
    void releaseSortsAfterItsReleaseCandidate() {
        assertTrue(MavenVersionOrder.compare("2.0.0", "2.0.0-RC1") > 0);
    }
}
