package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarCandidatesTest {

    @TempDir
    Path root;

    @Test
    void findsJarsInMavenAndGradleOutputDirectories() throws Exception {
        jar("target/demo-1.0.0.jar");
        jar("build/libs/demo-1.0.0.jar");

        List<String> names = JarCandidates.findInModule(module(root)).stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertEquals(2, names.size());
        assertTrue(names.stream().allMatch(name -> name.equals("demo-1.0.0.jar")));
    }

    @Test
    void ignoresSourcesJavadocAndShadeLeftovers() throws Exception {
        jar("target/demo.jar");
        jar("target/demo-sources.jar");
        jar("target/demo-javadoc.jar");
        jar("target/demo-tests.jar");
        jar("target/original-demo.jar");

        List<Path> found = JarCandidates.findInModule(module(root));

        assertEquals(1, found.size());
        assertEquals("demo.jar", found.getFirst().getFileName().toString());
    }

    @Test
    void ignoresFilesThatAreNotJars() throws Exception {
        jar("target/demo.zip");
        assertTrue(JarCandidates.findInModule(module(root)).isEmpty());
    }

    @Test
    void aModuleWithoutBuildOutputYieldsNoSuggestions() {
        assertTrue(JarCandidates.findInModule(module(root)).isEmpty());
        assertTrue(JarCandidates.findInModule(null).isEmpty());
    }

    @Test
    void scansEveryBuildableModuleWhenNoneIsSelected() throws Exception {
        Path api = root.resolve("api");
        Path web = root.resolve("web");
        Files.createDirectories(api.resolve("target"));
        Files.createDirectories(web.resolve("target"));
        Files.writeString(api.resolve("target/api.jar"), "");
        Files.writeString(web.resolve("target/web.jar"), "");

        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN,
                List.of(module(api), module(web)), false, false, 21, null);

        List<String> names = JarCandidates.find(descriptor, null).stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertEquals(List.of("api.jar", "web.jar"), names);
    }

    @Test
    void aNullDescriptorYieldsNoSuggestions() {
        assertTrue(JarCandidates.find(null, null).isEmpty());
    }

    @Test
    void directoriesNamedLikeJarsAreNotOffered() throws Exception {
        Files.createDirectories(root.resolve("target/fake.jar"));
        assertFalse(JarCandidates.isRunnableJar(root.resolve("target/fake.jar")));
    }

    private void jar(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }

    private static JavaModule module(Path moduleRoot) {
        return new JavaModule(moduleRoot, moduleRoot.getFileName().toString(), "com.example",
                "demo", "jar", List.of(), List.of(), moduleRoot.resolve("target/classes"));
    }
}
