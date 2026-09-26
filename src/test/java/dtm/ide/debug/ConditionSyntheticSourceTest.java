package dtm.ide.debug;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionSyntheticSourceTest {

    private static final Path FILE = Path.of("src", "demo", "Demo.java").toAbsolutePath().normalize();
    private static final String SOURCE = """
            package demo;
            public class Demo {
                private int total;
                Demo() {}
                void run(int count) {
                    total += count;
                }
            }
            """;

    @Test
    void theConditionIsInjectedBeforeTheBreakpointLineWithItsIndentation() {
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(FILE, SOURCE, 5, "count > 1");

        List<String> lines = synthetic.text().lines().toList();
        assertEquals("        if (count > 1) { }", lines.get(5));
        assertEquals("        total += count;", lines.get(6));
        assertEquals(FILE.getParent().resolve("Demo__OrionBreakpointCondition.java"), synthetic.path());
    }

    @Test
    void coordinatesMapBothWays() {
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(FILE, SOURCE, 5, "this.");

        assertEquals(5, synthetic.toSyntheticLine(0));
        assertEquals(8 + 4 + 5, synthetic.toSyntheticCol(0, 5));
        assertTrue(synthetic.coversSyntheticLine(5));
        assertFalse(synthetic.coversSyntheticLine(6));
        assertEquals(0, synthetic.toConditionLine(5));
        assertEquals(5, synthetic.toConditionCol(5, 17));
        assertEquals(0, synthetic.toConditionCol(5, 3));
    }

    @Test
    void multiLineConditionsKeepTheirLines() {
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(FILE, SOURCE, 5,
                "count > 1\n&& total < 10");

        List<String> lines = synthetic.text().lines().toList();
        assertEquals("        if (count > 1", lines.get(5));
        assertEquals("&& total < 10) { }", lines.get(6));
        assertEquals(2, synthetic.conditionLines());
        assertEquals(6, synthetic.toSyntheticLine(1));
        assertEquals(3, synthetic.toSyntheticCol(1, 3));
        assertEquals(1, synthetic.toConditionLine(6));
        assertEquals(4, synthetic.toConditionCol(6, 4));
    }

    @Test
    void theTopLevelTypeIsRenamedButStringsCommentsAndQualifiedNamesAreNot() {
        String source = """
                package demo;
                // Demo helper
                public class Demo {
                    String label = "Demo";
                    Demo copy() { return new Demo(); }
                    Object other = other.Demo.VALUE;
                }
                """;

        String text = ConditionSyntheticSource.build(FILE, source, 4, "true").text();

        assertTrue(text.contains("// Demo helper"), text);
        assertTrue(text.contains("public class Demo__OrionBreakpointCondition {"), text);
        assertTrue(text.contains("String label = \"Demo\";"), text);
        assertTrue(text.contains("Demo__OrionBreakpointCondition copy() { return new Demo__OrionBreakpointCondition(); }"), text);
        assertTrue(text.contains("other.Demo.VALUE"), text);
    }

    @Test
    void aLineBeyondTheEndIsClampedAndCrLfIsNormalized() {
        ConditionSyntheticSource synthetic = ConditionSyntheticSource.build(FILE,
                "class Demo {\r\n    int a;\r\n}", 40, "a > 0");

        assertFalse(synthetic.text().contains("\r"));
        assertEquals(2, synthetic.firstLine());
        assertTrue(synthetic.text().endsWith("if (a > 0) { }\n}"), synthetic.text());
    }
}
