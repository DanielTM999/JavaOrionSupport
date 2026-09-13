package dtm.ide;

import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JavaIdeAdapterLocationTest {

    @Test
    void preservesDifferentOccurrencesOnTheSameLine() {
        Location fromLanguageServer = Location.of("file:///D:/project/App.java",
                Range.of(11, 8, 11, 19));
        Location fromSafeDeleteScanner = Location.of("file:///D:/project/App.java",
                Range.of(11, 28, 11, 39));

        assertNotEquals(JavaIdeAdapter.locationKey(fromLanguageServer),
                JavaIdeAdapter.locationKey(fromSafeDeleteScanner));
    }
}
