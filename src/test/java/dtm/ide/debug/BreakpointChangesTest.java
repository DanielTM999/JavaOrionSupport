package dtm.ide.debug;

import dtm.ide.api.extension.event.BreakpointChangeType;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.project.editor.BreakpointIde;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakpointChangesTest {

    private static final Path FILE = Path.of("Demo.java");

    @Test
    void editingOptionsOfADisabledBreakpointKeepsItDisabled() {
        BreakpointChanges.Update update = BreakpointChanges.resolve(event(
                new BreakpointIde(4, false, "i == 5", "3", null), true, BreakpointChangeType.CONDITION));

        assertFalse(update.enabled());
        assertEquals("i == 5", update.spec().condition());
        assertEquals("3", update.spec().hitCondition());
    }

    @Test
    void clearingTheOptionsKeepsAnActiveBreakpoint() {
        BreakpointChanges.Update update = BreakpointChanges.resolve(event(
                new BreakpointIde(4, true), true, BreakpointChangeType.CONDITION));

        assertTrue(update.enabled());
        assertEquals(JavaDebugSession.BreakpointSpec.plain(), update.spec());
    }

    @Test
    void togglingTheEnabledStatePreservesTheOptions() {
        BreakpointChanges.Update update = BreakpointChanges.resolve(event(
                new BreakpointIde(4, true, null, null, "i = {i}"), true, BreakpointChangeType.ENABLED_STATE));

        assertTrue(update.enabled());
        assertEquals("i = {i}", update.spec().logMessage());
    }

    @Test
    void aRemovedBreakpointIsDisabled() {
        BreakpointChanges.Update update = BreakpointChanges.resolve(event(
                new BreakpointIde(4, true, "x > 1"), false, BreakpointChangeType.BREAKPOINT));

        assertFalse(update.enabled());
    }

    @Test
    void anInvalidHitCountIsDropped() {
        JavaDebugSession.BreakpointSpec spec = JavaDebugSession.BreakpointSpec.of(
                new BreakpointIde(1, true, null, "zero", null));

        assertNull(spec.hitCondition());
        assertNull(new JavaDebugSession.BreakpointSpec(null, "0", null).hitCondition());
        assertEquals("7", new JavaDebugSession.BreakpointSpec(null, " 007 ", null).hitCondition());
    }

    private static BreakpointChangedEvent event(BreakpointIde breakpoint, boolean added,
                                                BreakpointChangeType type) {
        return new BreakpointChangedEvent(breakpoint, FILE, null, added, type, breakpoint.condition());
    }
}
