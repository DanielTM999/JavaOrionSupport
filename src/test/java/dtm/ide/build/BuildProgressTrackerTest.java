package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProgressTrackerTest {

    @TempDir
    Path root;

    @Test
    void reportsTheCurrentMavenModule() {
        Fixture fixture = fixture();
        List<BuildProgressTracker.Update> updates = new ArrayList<>();
        BuildProgressTracker tracker = new BuildProgressTracker(
                "Compilando", fixture.descriptor(), null, updates::add);

        tracker.accept("\u001B[32m[INFO] Building orders 1.0.0 [1/2]\u001B[0m");
        tracker.accept("[INFO] --- maven-compiler-plugin:compile @ billing ---");

        assertEquals("Compilando - orders (1/2)", updates.get(0).label());
        assertEquals("Compilando - billing (2/2)", updates.get(1).label());
        assertTrue(updates.get(1).percent() >= updates.get(0).percent());
    }

    @Test
    void reportsANestedGradleModule() {
        Fixture fixture = fixture();
        List<BuildProgressTracker.Update> updates = new ArrayList<>();
        BuildProgressTracker tracker = new BuildProgressTracker(
                "Compilando", fixture.descriptor(), null, updates::add);

        tracker.accept("> Task :services:billing:compileJava");

        assertEquals("Compilando - billing (2/2)", updates.getFirst().label());
    }

    @Test
    void usesJdtProgressAndItsPercentage() {
        Fixture fixture = fixture();
        List<BuildProgressTracker.Update> updates = new ArrayList<>();
        BuildProgressTracker tracker = new BuildProgressTracker(
                "Compilando", fixture.descriptor(), null, updates::add);

        tracker.acceptProgress("Compiling orders", 37);

        assertEquals(new BuildProgressTracker.Update(
                "Compilando - orders (1/2)", 37), updates.getFirst());
    }

    @Test
    void aModuleBuildStartsWithTheSelectedModule() {
        Fixture fixture = fixture();
        BuildProgressTracker tracker = new BuildProgressTracker(
                "Compilando", fixture.descriptor(), fixture.billing(), update -> { });

        assertEquals(new BuildProgressTracker.Update("Compilando - billing", -1),
                tracker.initial());
    }

    @Test
    void ordinaryOutputDoesNotReplaceTheProgressMessage() {
        Fixture fixture = fixture();
        List<BuildProgressTracker.Update> updates = new ArrayList<>();
        BuildProgressTracker tracker = new BuildProgressTracker(
                "Compilando", fixture.descriptor(), null, updates::add);

        tracker.accept("Downloading orders dependency");
        tracker.accept("warning in billing source");

        assertTrue(updates.isEmpty());
    }

    private Fixture fixture() {
        JavaModule aggregator = module(root, "workspace", "workspace", "pom");
        JavaModule orders = module(root.resolve("orders"), "orders", "orders", "jar");
        JavaModule billing = module(root.resolve("services/billing"),
                "billing", "billing", "jar");
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root,
                JavaProjectKind.MAVEN_MULTIMODULE, List.of(aggregator, orders, billing),
                false, false, 21, null);
        return new Fixture(descriptor, billing);
    }

    private static JavaModule module(Path path, String name, String artifact, String packaging) {
        return new JavaModule(path, name, "example", artifact, packaging,
                List.of(path.resolve("src/main/java")),
                List.of(path.resolve("src/test/java")), path.resolve("target/classes"));
    }

    private record Fixture(JavaProjectDescriptor descriptor, JavaModule billing) {
    }
}
