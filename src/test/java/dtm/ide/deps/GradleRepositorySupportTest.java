package dtm.ide.deps;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleRepositorySupportTest {

    @TempDir
    Path root;

    @Test
    void recognizesMavenLocalInGroovyAndKotlinBuilds() throws Exception {
        JavaModule module = module(root);
        JavaProjectDescriptor descriptor = descriptor(module);
        Files.writeString(root.resolve("build.gradle"),
                "repositories { mavenLocal()\n mavenCentral() }");
        assertTrue(GradleRepositorySupport.hasMavenLocal(descriptor, module));

        Files.delete(root.resolve("build.gradle"));
        Files.writeString(root.resolve("settings.gradle.kts"), """
                dependencyResolutionManagement {
                    repositories { mavenLocal() }
                }
                """);
        assertTrue(GradleRepositorySupport.hasMavenLocal(descriptor, module));
    }

    @Test
    void commentsDoNotEnableLocalResolution() throws Exception {
        JavaModule module = module(root);
        Files.writeString(root.resolve("build.gradle"), """
                repositories {
                    // mavenLocal()
                    mavenCentral()
                }
                println("mavenLocal()")
                """);

        assertFalse(GradleRepositorySupport.hasMavenLocal(descriptor(module), module));
    }

    @Test
    void groovySettingsAlsoEnableLocalResolution() throws Exception {
        JavaModule module = module(root);
        Files.writeString(root.resolve("settings.gradle"), """
                dependencyResolutionManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }
                }
                """);

        assertTrue(GradleRepositorySupport.hasMavenLocal(descriptor(module), module));
    }

    @Test
    void gradleBuildsWithoutMavenLocalBlockLocalOnlyVersions() throws Exception {
        JavaModule module = module(root);
        Files.writeString(root.resolve("build.gradle.kts"),
                "repositories { mavenCentral() }");

        assertFalse(GradleRepositorySupport.hasMavenLocal(descriptor(module), module));
    }

    @Test
    void mavenProjectsAlwaysResolveFromTheLocalRepository() {
        JavaModule module = module(root);
        JavaProjectDescriptor maven = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN,
                List.of(module), false, false, 21, null);

        assertTrue(GradleRepositorySupport.hasMavenLocal(maven, module));
        assertFalse(GradleRepositorySupport.hasMavenLocal(null, module));
    }

    private static JavaProjectDescriptor descriptor(JavaModule module) {
        return new JavaProjectDescriptor(module.root(), JavaProjectKind.GRADLE, List.of(module),
                false, false, 21, null);
    }

    private static JavaModule module(Path root) {
        return new JavaModule(root, "demo", "", "demo", "jar", List.of(), List.of(),
                root.resolve("build/classes/java/main"));
    }
}
