package dtm.ide.navigation;

import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaNavigationTest {
    private final Path file = Path.of("Demo.java").toAbsolutePath();
    private Location at(int col) { return Location.of(file.toUri().toString(), Range.of(0, col, 0, col + 1)); }

    @Test void declarationRequiresTheSelectedIdentifierAndNotOnlyItsLine() {
        assertTrue(JavaNavigation.contains(at(4), file, 0, 4));
        assertFalse(JavaNavigation.contains(at(4), file, 0, 5));
        assertFalse(JavaNavigation.contains(at(4), file, 0, 12));
        assertFalse(JavaNavigation.contains(at(4), Path.of("Other.java"), 0, 4));
    }

    @Test void deduplicatesOnlyTheSameOccurrence() {
        assertEquals(List.of(at(4), at(8)), JavaNavigation.unique(List.of(at(4), at(8), at(4))));
    }

    @Test void documentUsagesExcludeOtherFilesButProjectUsagesKeepThem() {
        var other = Location.of(Path.of("Other.java").toAbsolutePath().toUri().toString(), Range.point(1, 1));
        var result = new JavaNavigation.Result(JavaNavigation.Status.COMPLETE, List.of(at(4), other));
        assertEquals(List.of(at(4)), JavaNavigation.restrict(result, JavaNavigation.Extent.DOCUMENT, file).locations());
        assertEquals(result, JavaNavigation.restrict(result, JavaNavigation.Extent.PROJECT, file));
    }

    @Test void lensCommandsKeepTheirOperation() {
        assertEquals(JavaNavigation.Kind.IMPLEMENTATION, JavaNavigation.Kind.forLens("java.show.implementations"));
        assertEquals(JavaNavigation.Kind.REFERENCES, JavaNavigation.Kind.forLens("java.show.references"));
        assertNull(JavaNavigation.Kind.forLens("unknown"));
    }
}
