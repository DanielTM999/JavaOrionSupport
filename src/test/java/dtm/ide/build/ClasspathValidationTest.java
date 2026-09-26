package dtm.ide.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathValidationTest {

    @TempDir
    Path root;

    @Test
    void acceptsExistingJarAndMissingOutputDirectory() throws Exception {
        Path jar = Files.createFile(root.resolve("dependency.jar"));
        String classpath = root.resolve("build/classes").toString()
                + File.pathSeparator + jar;

        assertFalse(ClasspathValidation.hasMissingJar(classpath));
    }

    @Test
    void detectsMissingJar() {
        assertTrue(ClasspathValidation.hasMissingJar(root.resolve("missing.jar").toString()));
    }

    @Test
    void ignoresNonJarEntries() {
        assertFalse(ClasspathValidation.hasMissingJar(root.resolve("missing.classes").toString()));
    }

    @Test
    void acceptsBlankClasspath() {
        assertFalse(ClasspathValidation.hasMissingJar("  "));
    }

    @Test
    void recreatingAnOutputDirectoryKeepsTheFingerprint() throws Exception {
        Path jar = Files.writeString(root.resolve("dependency.jar"), "jar");
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        String classpath = classes + File.pathSeparator + jar;
        String before = ClasspathValidation.fingerprint(classpath);

        Files.delete(classes);
        String whileMissing = ClasspathValidation.fingerprint(classpath);
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("A.class"), "novo");

        assertEquals(before, whileMissing);
        assertEquals(before, ClasspathValidation.fingerprint(classpath));
    }

    @Test
    void aRepublishedJarChangesTheFingerprint() throws Exception {
        Path jar = Files.writeString(root.resolve("dependency.jar"), "jar");
        String before = ClasspathValidation.fingerprint(jar.toString());

        Files.writeString(jar, "jar republicado");

        assertNotEquals(before, ClasspathValidation.fingerprint(jar.toString()));
    }
}
