package dtm.ide.ui;

import dtm.ide.test.JavaTest;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTestGutterLayerTest {

    private static JavaTest test(String method, int line) {
        return new JavaTest("com.app.FooTest", method, method,
                Path.of("FooTest.java"), line, false);
    }

    private static MouseEvent clickAt(int x) {
        Component source = new JPanel();
        return new MouseEvent(source, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, x, 5, 1, false);
    }

    @Test
    void mapsTestsFromOneBasedLinesToZeroBasedGutterLines() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });

        layer.setTests(List.of(test("a", 10), test("b", 20)));

        assertNotNull(layer.testAt(9));
        assertNotNull(layer.testAt(19));
        assertNull(layer.testAt(10));
    }

    @Test
    void recordClampsNonPositiveLinesSoTheIconLandsOnTheFirstLine() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });

        layer.setTests(List.of(test("a", 0)));

        assertNotNull(layer.testAt(0));
    }

    @Test
    void reloadingReplacesThePreviousDiscovery() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.setTests(List.of(test("b", 30)));

        assertNull(layer.testAt(9));
        assertNotNull(layer.testAt(29));
    }

    @Test
    void nullDiscoveryClearsTheIcons() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.setTests(null);

        assertTrue(layer.isEmpty());
    }

    @Test
    void insertingLinesPushesTheIconsDown() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.onLinesInserted(5, 3);

        assertNull(layer.testAt(9));
        assertNotNull(layer.testAt(12));
    }

    @Test
    void insertingBelowDoesNotMoveTheIcon() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.onLinesInserted(50, 3);

        assertNotNull(layer.testAt(9));
    }

    @Test
    void removingLinesPullsTheIconsUp() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.onLinesRemoved(2, 3);

        assertNotNull(layer.testAt(6));
    }

    @Test
    void removingTheLineDropsTheIcon() {
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> { });
        layer.setTests(List.of(test("a", 10)));

        layer.onLinesRemoved(9, 1);

        assertTrue(layer.isEmpty());
    }

    @Test
    void clickingTheIconAreaRunsTheTest() {
        List<JavaTest> clicked = new ArrayList<>();
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> clicked.add(test));
        layer.setTests(List.of(test("a", 10)));

        layer.onMouseClick(clickAt(3), 9);

        assertEquals(1, clicked.size());
        assertEquals("a", clicked.getFirst().methodName());
    }

    @Test
    void clickingFarFromTheIconIsIgnored() {
        List<JavaTest> clicked = new ArrayList<>();
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> clicked.add(test));
        layer.setTests(List.of(test("a", 10)));

        layer.onMouseClick(clickAt(200), 9);

        assertTrue(clicked.isEmpty());
    }

    @Test
    void clickingALineWithoutATestIsIgnored() {
        List<JavaTest> clicked = new ArrayList<>();
        JavaTestGutterLayer layer = new JavaTestGutterLayer((event, test) -> clicked.add(test));
        layer.setTests(List.of(test("a", 10)));

        layer.onMouseClick(clickAt(3), 42);

        assertTrue(clicked.isEmpty());
    }
}
