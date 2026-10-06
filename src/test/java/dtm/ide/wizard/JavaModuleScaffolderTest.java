package dtm.ide.wizard;
import dtm.ide.project.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class JavaModuleScaffolderTest {
    @TempDir Path root;
    JavaProjectDescriptor project(JavaProjectKind kind) {
        return new JavaProjectDescriptor(root, kind, List.of(new JavaModule(root, "parent", "test", "parent",
                "pom", List.of(), List.of(), null)), false, false, 25, null);
    }
    @Test void emptyModulesAndCommentsArePreserved() {
        String original = "<project><!-- keep --><packaging>jar</packaging><modules/></project>";
        String result = JavaModuleScaffolder.registerMavenModule(original, "child", true);
        assertTrue(result.contains("<!-- keep -->"));
        assertEquals("pom", MavenPom.parseContent(result).value("packaging"));
        assertEquals(List.of("child"), MavenPom.parseContent(result).values("modules", "module"));
    }
    @Test void groovyGradleUsesGroovyBuildFile() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'parent'\n");
        var plan = JavaModuleScaffolder.prepare(project(JavaProjectKind.GRADLE), root, "child");
        JavaModuleScaffolder.create(plan);
        assertTrue(Files.exists(root.resolve("child/build.gradle")));
        assertTrue(Files.readString(root.resolve("settings.gradle")).contains("include(\":child\")"));
    }
    @Test void mavenCreatesAndRegistersChildWithParent() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging></project>");
        var plan = JavaModuleScaffolder.prepare(project(JavaProjectKind.MAVEN), root, "child");
        assertFalse(plan.convertsPackaging()); JavaModuleScaffolder.create(plan);
        assertTrue(MavenPom.parse(root.resolve("pom.xml")).values("modules", "module").contains("child"));
        assertEquals("parent", MavenPom.parse(root.resolve("child/pom.xml")).value("parent", "artifactId"));
        assertTrue(Files.isDirectory(root.resolve("child/src/test/java")));
        assertThrows(IllegalArgumentException.class, () -> JavaModuleScaffolder.prepare(project(JavaProjectKind.MAVEN), root, "child"));
    }
    @Test void preservesGradleDslAndExistingSettings() throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"parent\"\n");
        var plan = JavaModuleScaffolder.prepare(project(JavaProjectKind.GRADLE), root, "child");
        JavaModuleScaffolder.create(plan);
        assertTrue(Files.readString(root.resolve("settings.gradle.kts")).contains("include(\":child\")"));
        assertTrue(Files.exists(root.resolve("child/build.gradle.kts")));
    }
    @Test void concurrentParentChangeDoesNotCreateModule() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "");
        var plan = JavaModuleScaffolder.prepare(project(JavaProjectKind.GRADLE), root, "child");
        Files.writeString(root.resolve("settings.gradle"), "// changed");
        assertThrows(java.io.IOException.class, () -> JavaModuleScaffolder.create(plan));
        assertFalse(Files.exists(root.resolve("child")));
    }
}
