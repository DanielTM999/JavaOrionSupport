package dtm.ide.swingdesigner;

import dtm.ide.build.BuildResult;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.swingdesigner.catalog.ClasspathEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModuleSessionRuntimeWorkspaceTest {

    @TempDir
    Path root;

    @Test
    void siblingModulesAndResourcesJoinTheRuntimeClasspathAfterTheModuleItself() throws IOException {
        JavaModule lib = module("lib", true);
        JavaModule app = module("app", true);
        JavaModule pending = module("pending", false);
        JavaModule parent = new JavaModule(root, "parent", "demo", "parent", "pom", List.of(), List.of(),
                root.resolve("target/classes"));
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN_MULTIMODULE,
                List.of(parent, lib, app, pending), false, false, 21, null);
        ModuleSession session = new ModuleSession(lib, environment(descriptor));
        Path libraryJar = root.resolve("dep.jar");

        List<Path> runtime = session.runtimeWorkspace(List.of(
                ClasspathEntry.workspace(lib.outputDir(), "lib"),
                ClasspathEntry.library(libraryJar)));

        assertEquals(List.of(
                lib.outputDir(),
                root.resolve("lib/src/main/resources"),
                app.outputDir(),
                root.resolve("app/src/main/resources"),
                root.resolve("pending/src/main/resources")), runtime);
        assertFalse(runtime.contains(libraryJar));
    }

    private JavaModule module(String name, boolean built) throws IOException {
        Path moduleRoot = root.resolve(name);
        Files.createDirectories(moduleRoot.resolve("src/main/java"));
        Files.createDirectories(moduleRoot.resolve("src/main/resources/img"));
        if (built) {
            Files.createDirectories(moduleRoot.resolve("target/classes"));
        }
        return new JavaModule(moduleRoot, name, "demo", name, "jar",
                List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/resources")),
                List.of(), moduleRoot.resolve("target/classes"));
    }

    private static SwingDesignerEnvironment environment(JavaProjectDescriptor descriptor) {
        return new SwingDesignerEnvironment() {
            @Override
            public JavaProjectDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public JdkInstallation projectJdk() {
                return null;
            }

            @Override
            public Optional<String> runtimeClasspath(JavaModule module) {
                return Optional.empty();
            }

            @Override
            public BuildResult compile(JavaModule module, Consumer<String> output) {
                return null;
            }

            @Override
            public void output(String line) {
            }
        };
    }
}
