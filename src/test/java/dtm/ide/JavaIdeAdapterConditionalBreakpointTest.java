package dtm.ide;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaIdeAdapterConditionalBreakpointTest {

    @Test
    void conditionalBreakpointsAreOfferedOnlyForJavaSources() {
        JavaIdeAdapter adapter = new JavaIdeAdapter();

        assertTrue(adapter.isConditionalBreakpointEnabled(Path.of("src", "Main.java")));
        assertTrue(adapter.isConditionalBreakpointEnabled(Path.of("Upper.JAVA")));
        assertFalse(adapter.isConditionalBreakpointEnabled(Path.of("pom.xml")));
        assertFalse(adapter.isConditionalBreakpointEnabled(null));
    }
}
