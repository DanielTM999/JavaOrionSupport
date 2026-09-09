package dtm.ide.coverage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageGutterTest {

    private static FileCoverage coverage(Map<Integer, LineStatus> lines) {
        return new FileCoverage(Path.of("Foo.java"), lines, 0, 0);
    }

    private static Map<Integer, LineStatus> lines(Object... pairs) {
        Map<Integer, LineStatus> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((Integer) pairs[index], (LineStatus) pairs[index + 1]);
        }
        return map;
    }

    @Test
    void newLayerIsBuiltWithTheStripeDefaultsInsteadOfLombokZeroes() {
        CoverageGutterLayer layer = CoverageGutter.newLayer();

        assertEquals(CoverageGutter.STRIPE_WIDTH, layer.getStripeWidth());
        assertEquals(CoverageGutterLayer.Side.RIGHT, layer.getSide());
    }

    @Test
    void appliesOneColorPerExecutableLine() {
        CoverageGutterLayer layer = CoverageGutter.newLayer();

        CoverageGutter.apply(layer, coverage(lines(
                3, LineStatus.COVERED, 4, LineStatus.PARTIAL, 5, LineStatus.UNCOVERED)));

        assertTrue(layer.hasLineColor(3));
        assertTrue(layer.hasLineColor(4));
        assertTrue(layer.hasLineColor(5));
        assertFalse(layer.hasLineColor(6));
        assertNotEquals(layer.getLineColor(3), layer.getLineColor(5));
        assertNotEquals(layer.getLineColor(3), layer.getLineColor(4));
    }

    @Test
    void applyingAgainReplacesThePreviousRunInsteadOfAccumulating() {
        CoverageGutterLayer layer = CoverageGutter.newLayer();
        CoverageGutter.apply(layer, coverage(lines(3, LineStatus.COVERED, 9, LineStatus.UNCOVERED)));

        CoverageGutter.apply(layer, coverage(lines(3, LineStatus.UNCOVERED)));

        assertTrue(layer.hasLineColor(3));
        assertFalse(layer.hasLineColor(9));
    }

    @Test
    void emptyOrMissingCoverageLeavesTheGutterClean() {
        CoverageGutterLayer layer = CoverageGutter.newLayer();
        CoverageGutter.apply(layer, coverage(lines(3, LineStatus.COVERED)));

        CoverageGutter.apply(layer, null);
        assertFalse(layer.hasLineColor(3));

        CoverageGutter.apply(layer, coverage(lines(3, LineStatus.COVERED)));
        CoverageGutter.apply(layer, coverage(Map.of()));
        assertFalse(layer.hasLineColor(3));
    }

    @Test
    void clearRemovesEveryStripe() {
        CoverageGutterLayer layer = CoverageGutter.newLayer();
        CoverageGutter.apply(layer, coverage(lines(1, LineStatus.COVERED)));

        CoverageGutter.clear(layer);

        assertFalse(layer.hasLineColor(1));
    }

    @Test
    void nullLayerIsIgnored() {
        CoverageGutter.apply(null, coverage(lines(1, LineStatus.COVERED)));
        CoverageGutter.clear(null);
    }

    @Test
    void irrelevantLinesGetNoColor() {
        assertNull(CoverageGutter.colorOf(LineStatus.IRRELEVANT));
        assertNull(CoverageGutter.colorOf(null));
        assertNotNull(CoverageGutter.colorOf(LineStatus.COVERED));
        assertNotNull(CoverageGutter.colorOf(LineStatus.PARTIAL));
        assertNotNull(CoverageGutter.colorOf(LineStatus.UNCOVERED));
    }

    @Test
    void stripesAreTranslucentSoTheGutterStaysReadable() {
        assertEquals(190, CoverageGutter.colorOf(LineStatus.COVERED).getAlpha());
    }
}
