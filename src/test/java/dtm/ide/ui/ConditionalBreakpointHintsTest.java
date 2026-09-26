package dtm.ide.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionalBreakpointHintsTest {

    @Test
    void onlyTheFirstVariablesBecomeChipsAndTheRestAreCounted() {
        assertEquals("+1", ConditionalBreakpointHints.overflow(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));
        assertEquals("", ConditionalBreakpointHints.overflow(List.of("x", "y")));
        assertEquals("", ConditionalBreakpointHints.overflow(null));
    }
}
