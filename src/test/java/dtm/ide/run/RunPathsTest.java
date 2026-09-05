package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunPathsTest {

    @TempDir
    Path root;

    private JavaProjectDescriptor descriptor;
    private JavaModule module;

    @BeforeEach
    void setUp() {
        module = new JavaModule(root.resolve("api"), "api", "com.example", "api", "jar",
                List.of(), List.of(), root.resolve("api/target/classes"));
        descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(module),
                false, false, 21, null);
    }

    @Test
    void relativePathsResolveAgainstTheModuleNotTheIdeDirectory() {
        assertEquals(root.resolve("api/config"),
                RunPaths.resolve("config", module, descriptor).orElseThrow());
    }

    @Test
    void withoutAModuleTheProjectRootIsTheBase() {
        assertEquals(root.resolve("config"),
                RunPaths.resolve("config", null, descriptor).orElseThrow());
    }

    @Test
    void absolutePathsAreKeptAsTheyAre() {
        Path absolute = root.resolve("outro/lugar").toAbsolutePath();
        assertEquals(absolute, RunPaths.resolve(absolute.toString(), module, descriptor)
                .orElseThrow());
    }

    @Test
    void blankValuesResolveToNothing() {
        assertTrue(RunPaths.resolve("", module, descriptor).isEmpty());
        assertTrue(RunPaths.resolve(null, module, descriptor).isEmpty());
        assertTrue(RunPaths.resolve("   ", module, descriptor).isEmpty());
    }

    @Test
    void aPathInsideTheModuleIsShownRelative() {
        assertEquals("target/demo.jar",
                RunPaths.relativize(root.resolve("api/target/demo.jar"), module, descriptor));
    }

    @Test
    void aPathOutsideTheModuleStaysAbsolute() {
        Path outside = root.resolve("fora/demo.jar").toAbsolutePath().normalize();
        assertEquals(outside.toString(), RunPaths.relativize(outside, module, descriptor));
    }

    @Test
    void malformedPathsAreDetectedWithoutThrowing() {
        assertFalse(RunPaths.isMalformed("target/demo.jar"));
        assertFalse(RunPaths.isMalformed(""));
        assertFalse(RunPaths.isMalformed(null));
    }

    @Test
    void withoutAProjectThereIsNoBase() {
        assertTrue(RunPaths.base(null, null).isEmpty());
        assertTrue(RunPaths.resolve("config", null, null).isEmpty());
    }
}
