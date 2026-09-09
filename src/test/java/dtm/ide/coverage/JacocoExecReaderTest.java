package dtm.ide.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacocoExecReaderTest {

    @Test
    void missingExecFileIsReportedAsSuch(@TempDir Path root) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));

        CoverageReadResult result = JacocoExecReader.read(
                root.resolve("absent.exec"), List.of(classes), List.of());

        assertFalse(result.isSuccess());
        assertEquals(CoverageReadResult.Failure.MISSING_EXEC, result.failure());
        assertTrue(result.report().isEmpty());
    }

    @Test
    void nullExecFileIsReportedAsMissing() {
        CoverageReadResult result = JacocoExecReader.read(null, List.of(), List.of());

        assertEquals(CoverageReadResult.Failure.MISSING_EXEC, result.failure());
    }

    @Test
    void absentClassDirectoriesStopTheAnalysis(@TempDir Path root) throws Exception {
        Path exec = Files.writeString(root.resolve("jacoco.exec"), "");

        CoverageReadResult result = JacocoExecReader.read(
                exec, List.of(root.resolve("nowhere")), List.of());

        assertEquals(CoverageReadResult.Failure.NO_CLASSES, result.failure());
    }

    @Test
    void corruptedExecFileIsReportedInsteadOfThrowing(@TempDir Path root) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path exec = Files.write(root.resolve("jacoco.exec"),
                new byte[] {0x42, 0x13, 0x37, 0x01, 0x02});

        CoverageReadResult result = JacocoExecReader.read(exec, List.of(classes), List.of());

        assertFalse(result.isSuccess());
        assertEquals(CoverageReadResult.Failure.UNREADABLE_EXEC, result.failure());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void emptyExecFileYieldsAnEmptyReportWithoutFailing(@TempDir Path root) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path exec = Files.write(root.resolve("jacoco.exec"), new byte[0]);

        CoverageReadResult result = JacocoExecReader.read(exec, List.of(classes), List.of());

        assertTrue(result.isSuccess());
        assertTrue(result.report().isEmpty());
    }

    @Test
    void qualifiedNamesJoinPackageAndSourceFile() {
        assertEquals("com.app.Foo", JacocoExecReader.qualifiedNameOf("com/app", "Foo.java"));
        assertEquals("com.app.Foo", JacocoExecReader.qualifiedNameOf("com.app", "Foo.java"));
        assertEquals("Foo", JacocoExecReader.qualifiedNameOf("", "Foo.java"));
        assertEquals("Foo", JacocoExecReader.qualifiedNameOf(null, "Foo.java"));
    }

    @Test
    void sourcesAreResolvedAgainstTheFirstRootThatHasTheFile(@TempDir Path root) throws Exception {
        Path first = Files.createDirectories(root.resolve("a"));
        Path second = Files.createDirectories(root.resolve("b").resolve("com").resolve("app"));
        Path file = Files.writeString(second.resolve("Foo.java"), "class Foo {}");

        Path resolved = JacocoExecReader.resolveSource(
                List.of(first, root.resolve("b")), "com/app", "Foo.java");

        assertEquals(file.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void unresolvableSourceReturnsNull(@TempDir Path root) {
        assertNull(JacocoExecReader.resolveSource(List.of(root), "com/app", "Foo.java"));
        assertNull(JacocoExecReader.resolveSource(List.of(root), "com/app", ""));
        assertNull(JacocoExecReader.resolveSource(List.of(root), "com/app", null));
    }
}
