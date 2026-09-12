package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorClasspathTest {

    @TempDir
    Path root;

    @TempDir
    Path repository;

    @Test
    void siblingJarBecomesTheModuleOutputDirectory() {
        String classpath = jar("com.example", "user", "1.1.0");

        String result = ReactorClasspath.substituteWorkspaceModules(classpath, descriptor(),
                module("web"), false);

        assertEquals(root.resolve("user/target/classes").toString(), result);
    }

    @Test
    void thirdPartyJarsAreKeptUntouched() {
        String spring = jar("org.springframework", "spring-core", "6.2.0");

        String result = ReactorClasspath.substituteWorkspaceModules(spring, descriptor(),
                module("web"), false);

        assertEquals(spring, result);
    }

    @Test
    void theSameArtifactIdUnderAnotherGroupIsNotSubstituted() {
        String other = jar("org.other", "user", "1.1.0");

        String result = ReactorClasspath.substituteWorkspaceModules(other, descriptor(),
                module("web"), false);

        assertEquals(other, result);
    }

    @Test
    void theModuleBeingBuiltIsNotSubstituted() {
        String own = jar("com.example", "web", "1.1.0");

        String result = ReactorClasspath.substituteWorkspaceModules(own, descriptor(),
                module("web"), false);

        assertEquals(own, result);
    }

    @Test
    void orderIsPreservedAndDuplicatesAreRemoved() {
        String classpath = String.join(File.pathSeparator,
                jar("com.example", "user", "1.1.0"),
                jar("org.springframework", "spring-core", "6.2.0"),
                jar("com.example", "user", "1.1.0"));

        String result = ReactorClasspath.substituteWorkspaceModules(classpath, descriptor(),
                module("web"), false);

        List<String> entries = List.of(result.split(java.util.regex.Pattern.quote(
                File.pathSeparator)));
        assertEquals(2, entries.size());
        assertEquals(root.resolve("user/target/classes").toString(), entries.get(0));
        assertTrue(entries.get(1).endsWith("spring-core-6.2.0.jar"));
    }

    @Test
    void testsClassifierMapsToTheTestOutputDirectory() {
        String classpath = repository.resolve("com/example/user/1.1.0/user-1.1.0-tests.jar")
                .toString();

        String result = ReactorClasspath.substituteWorkspaceModules(classpath, descriptor(),
                module("web"), true);

        assertEquals(root.resolve("user/target/test-classes").toString(), result);
    }

    @Test
    void aggregatorModulesAreIgnored() {
        JavaModule aggregator = new JavaModule(root, "parent", "com.example", "parent", "pom",
                List.of(), List.of(), root.resolve("target/classes"));
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root,
                JavaProjectKind.MAVEN_MULTIMODULE, List.of(aggregator), false, false, 21, null);
        String parent = jar("com.example", "parent", "1.1.0");

        assertEquals(parent, ReactorClasspath.substituteWorkspaceModules(parent, descriptor,
                module("web"), false));
    }

    @Test
    void aSingleModuleProjectKeepsItsClasspathUntouched() {
        JavaModule only = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN,
                List.of(only), true, false, 21, null);
        String classpath = String.join(File.pathSeparator,
                jar("org.springframework", "spring-core", "6.2.0"),
                jar("com.example", "demo", "1.0.0"));

        assertEquals(classpath,
                ReactorClasspath.substituteWorkspaceModules(classpath, descriptor, only, false));
    }

    @Test
    void blankInputIsHandled() {
        assertEquals("", ReactorClasspath.substituteWorkspaceModules(null, descriptor(),
                module("web"), false));
        assertEquals("   ", ReactorClasspath.substituteWorkspaceModules("   ", descriptor(),
                module("web"), false));
    }

    @Test
    void matchesRequiresTheFullGroupPath() {
        JavaModule user = module("user");

        assertTrue(ReactorClasspath.matches(
                repository.resolve("com/example/user/1.1.0/user-1.1.0.jar"), user));
        assertFalse(ReactorClasspath.matches(
                repository.resolve("example/user/1.1.0/user-1.1.0.jar"), user));
        assertFalse(ReactorClasspath.matches(
                repository.resolve("com/example/user/1.1.0/outro-1.1.0.jar"), user));
    }

    private String jar(String groupId, String artifactId, String version) {
        return repository.resolve(groupId.replace('.', '/'))
                .resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + ".jar").toString();
    }

    private JavaProjectDescriptor descriptor() {
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN_MULTIMODULE,
                List.of(module("web"), module("user")), true, false, 21, null);
    }

    private JavaModule module(String artifactId) {
        Path moduleRoot = root.resolve(artifactId);
        return new JavaModule(moduleRoot, artifactId, "com.example", artifactId, "jar",
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                moduleRoot.resolve("target/classes"));
    }
}
