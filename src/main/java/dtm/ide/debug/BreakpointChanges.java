package dtm.ide.debug;

import dtm.ide.api.extension.event.BreakpointChangeType;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.project.editor.BreakpointIde;

public final class BreakpointChanges {

    public record Update(boolean enabled, JavaDebugSession.BreakpointSpec spec) {
    }

    private BreakpointChanges() {
    }

    public static Update resolve(BreakpointChangedEvent event) {
        BreakpointIde breakpoint = event == null ? null : event.getBreakpointIde();
        if (breakpoint == null) {
            return new Update(false, JavaDebugSession.BreakpointSpec.plain());
        }
        JavaDebugSession.BreakpointSpec spec = JavaDebugSession.BreakpointSpec.of(breakpoint);
        boolean enabled = event.getChangeType() == BreakpointChangeType.BREAKPOINT
                ? event.isBreakpointAdded() && breakpoint.active()
                : breakpoint.active();
        return new Update(enabled, spec);
    }
}
