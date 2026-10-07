package dtm.ide.lsp.api;

import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;

import java.util.List;

public record JavaCodeLens(Range range, String title, String command,
                           List<Location> locations, Status status) {
    public JavaCodeLens(Range range, String title, String command, List<Location> locations) {
        this(range, title, command, locations, Status.COMPLETE);
    }
    public JavaCodeLens {
        range = range == null ? Range.point(0, 0) : range;
        title = title == null ? "" : title;
        command = command == null ? "" : command;
        locations = JavaNavigation.unique(locations);
    }
}
