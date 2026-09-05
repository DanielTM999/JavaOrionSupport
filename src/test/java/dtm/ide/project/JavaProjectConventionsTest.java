package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectConventionsTest {

    @TempDir
    Path root;

    @Test
    void describeSingleModuleMavenProject() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Files.createDirectories(root.resolve("src/main/java/com/example"));

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertNotNull(descriptor);
        assertEquals(JavaProjectKind.MAVEN, descriptor.kind());
        assertEquals(1, descriptor.modules().size());
        JavaModule module = descriptor.rootModule();
        assertEquals("demo", module.artifactId());
        assertEquals("com.example", module.groupId());
        assertEquals("com.example:demo", module.coordinates());
        assertEquals(root.resolve("target/classes"), module.outputDir());
    }

    @Test
    void describeMultiModuleMavenProjectIncludingNestedModules() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>web</module>
                  </modules>
                </project>
                """);
        write(root.resolve("core/pom.xml"), """
                <project>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId></parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        write(root.resolve("web/pom.xml"), """
                <project>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId></parent>
                  <artifactId>web</artifactId>
                  <packaging>war</packaging>
                </project>
                """);

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertNotNull(descriptor);
        assertEquals(JavaProjectKind.MAVEN_MULTIMODULE, descriptor.kind());
        assertEquals(3, descriptor.modules().size());
        assertTrue(descriptor.rootModule().isAggregator());
        assertEquals(2, descriptor.buildableModules().size());
        assertTrue(descriptor.modules().stream().anyMatch(JavaModule::isWebArchive));
    }

    @Test
    void moduleOfPicksTheMostSpecificModule() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <artifactId>parent</artifactId>
                  <packaging>pom</packaging>
                  <modules><module>core</module></modules>
                </project>
                """);
        write(root.resolve("core/pom.xml"), "<project><artifactId>core</artifactId></project>");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        Path source = root.resolve("core/src/main/java/App.java");

        assertEquals("core", descriptor.moduleOf(source).orElseThrow().artifactId());
    }

    @Test
    void inheritsGroupIdFromParentWhenNotDeclared() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>com.example.platform</groupId>
                    <artifactId>platform-parent</artifactId>
                  </parent>
                  <artifactId>service</artifactId>
                </project>
                """);

        JavaModule module = JavaProjectConventions.describe(root).rootModule();

        assertEquals("com.example.platform", module.groupId());
        assertEquals("service", module.artifactId());
    }

    @Test
    void readsNamespacedPomTags() throws IOException {
        write(root.resolve("pom.xml"), """
                <mvn:project xmlns:mvn="http://maven.apache.org/POM/4.0.0">
                  <mvn:artifactId>namespaced</mvn:artifactId>
                </mvn:project>
                """);

        assertEquals("namespaced", JavaProjectConventions.describe(root).rootModule().artifactId());
    }

    @Test
    void describeSingleProjectGradleBuild() throws IOException {
        write(root.resolve("build.gradle.kts"), "plugins { id(\"java\") }");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertNotNull(descriptor);
        assertEquals(JavaProjectKind.GRADLE, descriptor.kind());
        assertEquals(1, descriptor.modules().size());
        assertEquals(root.resolve("build/classes/java/main"), descriptor.rootModule().outputDir());
    }

    @Test
    void describeGradleMultiProjectFromSettings() throws IOException {
        write(root.resolve("settings.gradle"), """
                rootProject.name = 'demo'
                include 'core', 'web'
                include ':tools:cli'
                """);
        write(root.resolve("build.gradle"), "plugins { id 'java' }");
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("web"));
        Files.createDirectories(root.resolve("tools/cli"));

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertEquals(JavaProjectKind.GRADLE_MULTIPROJECT, descriptor.kind());
        assertEquals(4, descriptor.modules().size());
        assertTrue(descriptor.modules().stream().anyMatch(module -> module.name().equals("tools:cli")));
    }

    @Test
    void ignoresIncludesInsideComments() throws IOException {
        write(root.resolve("settings.gradle"), """
                include 'core'
                // include 'disabled'
                /* include 'alsoDisabled' */
                """);
        write(root.resolve("build.gradle"), "plugins { id 'java' }");
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("disabled"));
        Files.createDirectories(root.resolve("alsoDisabled"));

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertEquals(2, descriptor.modules().size());
        assertFalse(descriptor.modules().stream().anyMatch(module -> module.name().equals("disabled")));
    }

    @Test
    void describePlainJavaFolder() throws IOException {
        write(root.resolve("src/Main.java"), "public class Main { }");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertNotNull(descriptor);
        assertEquals(JavaProjectKind.PLAIN_JAVA, descriptor.kind());
        assertEquals(root.resolve("src"), descriptor.rootModule().sourceRoots().getFirst());
        assertEquals(root.resolve(".orion/out"), descriptor.rootModule().outputDir());
    }

    @Test
    void detectsPlainJavaBuriedUnderPackageDirectories() throws IOException {
        write(root.resolve("src/main/java/com/example/deep/nested/App.java"), """
                package com.example.deep.nested;

                public class App { }
                """);

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertNotNull(descriptor, "a hierarquia de pacotes nao pode esconder o projeto");
        assertEquals(JavaProjectKind.PLAIN_JAVA, descriptor.kind());
        assertEquals(root.resolve("src/main/java"), descriptor.rootModule().sourceRoots().getFirst());
    }

    @Test
    void ignoresJavaFilesFoundOnlyInsideBuildOutput() throws IOException {
        write(root.resolve("target/generated-sources/Gerado.java"), "public class Gerado { }");
        write(root.resolve("README.md"), "sem fontes de verdade");

        assertFalse(JavaProjectConventions.supports(root));
    }

    @Test
    void describeWorkspaceOfSiblingProjects() throws IOException {
        write(root.resolve("alpha/pom.xml"), "<project><artifactId>alpha</artifactId></project>");
        write(root.resolve("beta/build.gradle"), "plugins { id 'java' }");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertEquals(JavaProjectKind.JAVA_WORKSPACE, descriptor.kind());
        assertEquals(2, descriptor.modules().size());
    }

    @Test
    void unwrapsWorkspaceWithASingleNestedProject() throws IOException {
        write(root.resolve("alpha/pom.xml"), "<project><artifactId>alpha</artifactId></project>");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertEquals(JavaProjectKind.MAVEN, descriptor.kind());
        assertEquals(root.resolve("alpha"), descriptor.root());
    }

    @Test
    void rejectsFolderWithoutJavaContent() throws IOException {
        write(root.resolve("README.md"), "sem java aqui");

        assertFalse(JavaProjectConventions.supports(root));
        assertNull(JavaProjectConventions.describe(root));
    }

    @Test
    void detectsSpringBootFromStarterParent() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.0</version>
                  </parent>
                  <artifactId>demo</artifactId>
                </project>
                """);

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertTrue(descriptor.springBoot());
        assertTrue(descriptor.spring());
    }

    @Test
    void detectsSpringBootFromAnnotatedSourceInPlainJavaProject() throws IOException {
        write(root.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                @SpringBootApplication
                public class App { }
                """);

        assertTrue(JavaProjectConventions.describe(root).springBoot());
    }

    @Test
    void skipsAnnotatedSourceFallbackForBuildProjectsWithoutSpringHints() throws IOException {
        write(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        write(root.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                @SpringBootApplication
                public class App { }
                """);

        assertFalse(JavaProjectConventions.describe(root).springBoot());
    }

    @Test
    void plainJavaProjectIsNotSpring() throws IOException {
        write(root.resolve("src/Main.java"), "public class Main { }");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        assertFalse(descriptor.spring());
        assertFalse(descriptor.springBoot());
    }

    @Test
    void readsJdkVersionFromMavenCompilerRelease() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <artifactId>demo</artifactId>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);

        assertEquals(21, JavaProjectConventions.describe(root).jdkMajor().orElseThrow());
    }

    @Test
    void normalizesLegacyMavenSourceLevel() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <artifactId>demo</artifactId>
                  <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                </project>
                """);

        assertEquals(8, JavaProjectConventions.describe(root).jdkMajor().orElseThrow());
    }

    @Test
    void readsJdkVersionFromGradleToolchain() throws IOException {
        write(root.resolve("build.gradle.kts"), """
                java {
                    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
                }
                """);

        assertEquals(17, JavaProjectConventions.describe(root).jdkMajor().orElseThrow());
    }

    @Test
    void pinnedJdkOverridesTheBuildFile() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <artifactId>demo</artifactId>
                  <properties><java.version>17</java.version></properties>
                </project>
                """);
        write(root.resolve(".orion/java.properties"), "jdk.version=25");

        assertEquals(25, JavaProjectConventions.describe(root).jdkMajor().orElseThrow());
    }

    @Test
    void missingJdkDeclarationYieldsEmpty() throws IOException {
        write(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");

        assertTrue(JavaProjectConventions.describe(root).jdkMajor().isEmpty());
    }

    @Test
    void findsBuildWrapper() throws IOException {
        write(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        write(root.resolve("mvnw"), "#!/bin/sh");
        write(root.resolve("mvnw.cmd"), "@echo off");

        assertTrue(JavaProjectConventions.describe(root).wrapperPath().isPresent());
    }

    @Test
    void handlesPathAcceptsJavaSourcesAndBuildFiles() {
        assertTrue(JavaProjectConventions.handlesPath(Path.of("App.java")));
        assertTrue(JavaProjectConventions.handlesPath(Path.of("pom.xml")));
        assertTrue(JavaProjectConventions.handlesPath(Path.of("application.yml")));
        assertTrue(JavaProjectConventions.handlesPath(Path.of("build.gradle.kts")));
        assertTrue(JavaProjectConventions.handlesPath(Path.of("com/example/service")));
        assertFalse(JavaProjectConventions.handlesPath(Path.of("notes.md")));
    }

    @Test
    void ignoredFoldersCoverBuildOutput() {
        assertTrue(JavaProjectConventions.isIgnoredFolder(Path.of("/p/target")));
        assertTrue(JavaProjectConventions.isIgnoredFolder(Path.of("/p/build")));
        assertFalse(JavaProjectConventions.isIgnoredFolder(Path.of("/p/src")));
    }

    @Test
    void javaSourceScanPrunesIgnoredSubtreesAndHonorsLimit() throws IOException {
        Path first = root.resolve("src/main/java/First.java");
        write(first, "class First {}");
        write(root.resolve("src/main/java/Second.java"), "class Second {}");
        write(root.resolve("target/generated-sources/Hidden.java"), "class Hidden {}");

        var sources = JavaProjectConventions.javaSources(root, 0, 1);

        assertEquals(1, sources.size());
        assertFalse(sources.stream().anyMatch(path -> path.endsWith("Hidden.java")));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
