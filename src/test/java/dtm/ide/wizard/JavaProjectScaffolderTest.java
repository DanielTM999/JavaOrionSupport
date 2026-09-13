package dtm.ide.wizard;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.run.MainClassScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectScaffolderTest {

    @TempDir
    Path workspace;

    @Test
    void createsAMavenApplicationThatTheAdapterRecognizes() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "minha-app");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);

        assertNotNull(descriptor, "o projeto gerado deve ser reconhecido pelo plugin");
        assertEquals(JavaProjectKind.MAVEN, descriptor.kind());
        assertEquals("minha-app", descriptor.rootModule().artifactId());
        assertEquals(21, descriptor.jdkMajor().orElseThrow());
    }

    @Test
    void theGeneratedApplicationHasARunnableEntryPoint() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "minha-app");

        List<MainClassScanner.MainClass> mainClasses =
                MainClassScanner.scan(JavaProjectConventions.describe(project));

        assertEquals(1, mainClasses.size());
        assertEquals("com.example.minha.app.MinhaAppApplication",
                mainClasses.getFirst().qualifiedName());
    }

    @Test
    void generatedSourcesLandInThePackageDirectory() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "demo");

        Path main = project.resolve("src/main/java/com/example/demo/DemoApplication.java");
        Path test = project.resolve("src/test/java/com/example/demo/DemoApplicationTest.java");

        assertTrue(Files.isRegularFile(main));
        assertTrue(Files.isRegularFile(test));
        assertTrue(Files.readString(main).startsWith("package com.example.demo;"));
    }

    @Test
    void theLibraryTemplateHasNoMainClass() throws IOException {
        Path project = create(JavaTemplate.MAVEN_LIBRARY, "minha-lib");

        assertTrue(MainClassScanner.scan(JavaProjectConventions.describe(project)).isEmpty());
        assertTrue(Files.isRegularFile(
                project.resolve("src/main/java/com/example/minha/lib/Biblioteca.java")));
    }

    @Test
    void theGeneratedPomDeclaresJUnitForTheGeneratedTest() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "demo");

        String pom = Files.readString(project.resolve("pom.xml"));

        assertTrue(pom.contains("junit-jupiter"));
        assertTrue(pom.contains("<scope>test</scope>"));
    }

    @Test
    void createsAggregatorAndChildModules() throws IOException {
        Path project = create(JavaTemplate.MAVEN_MULTIMODULE, "plataforma");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);

        assertEquals(JavaProjectKind.MAVEN_MULTIMODULE, descriptor.kind());
        assertEquals(3, descriptor.modules().size(), "o agregador mais os dois modulos padrao");
        assertTrue(descriptor.rootModule().isAggregator());
        assertTrue(Files.isRegularFile(project.resolve("core/pom.xml")));
        assertTrue(Files.isRegularFile(project.resolve("app/pom.xml")));
    }

    @Test
    void honoursExplicitModuleNames() throws IOException {
        Path project = workspace.resolve("plataforma");
        JavaProjectScaffolder.create(new JavaProjectScaffolder.ProjectRequest(
                project, JavaTemplate.MAVEN_MULTIMODULE, "com.example", "plataforma",
                "com.example.plataforma", 21, List.of("dominio", "infra", "web")));

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);

        assertEquals(4, descriptor.modules().size());
        assertTrue(Files.isRegularFile(project.resolve("dominio/pom.xml")));
        assertTrue(Files.isRegularFile(project.resolve("web/pom.xml")));
    }

    @Test
    void createsAGradleProjectWithKotlinDsl() throws IOException {
        Path project = create(JavaTemplate.GRADLE_APPLICATION, "gradle-app");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);

        assertEquals(JavaProjectKind.GRADLE, descriptor.kind());
        assertEquals(21, descriptor.jdkMajor().orElseThrow());
        assertTrue(Files.isRegularFile(project.resolve("build.gradle.kts")));
        assertTrue(Files.isRegularFile(project.resolve("settings.gradle.kts")));
    }

    @Test
    void turnsAnInitializrProjectIntoASpringMultiModuleReactor() throws Exception {
        Path generated = workspace.resolve("initializr-output");
        Files.createDirectories(generated.resolve("src/main/java/com/example/plataforma"));
        Files.writeString(generated.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.1.1.RELEASE</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>plataforma</artifactId>
                    <version>2.0.0</version>
                    <name>plataforma</name>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);
        Files.writeString(generated.resolve("src/main/java/com/example/plataforma/PlataformaApplication.java"),
                "package com.example.plataforma; public class PlataformaApplication {}\n");
        Files.writeString(generated.resolve("mvnw.cmd"), "@echo off\n");

        Path project = workspace.resolve("plataforma-spring");
        JavaProjectScaffolder.ProjectRequest request = new JavaProjectScaffolder.ProjectRequest(
                project, JavaTemplate.MAVEN_MULTIMODULE, "com.example", "plataforma", "2.0.0",
                "Plataforma web", "com.example.plataforma", 21,
                List.of("web", "core", "persistence"));

        SpringMultiModuleScaffolder.assemble(request, generated);

        String root = Files.readString(project.resolve("pom.xml"));
        String web = Files.readString(project.resolve("web/pom.xml"));
        assertTrue(root.contains("<version>4.1.1</version>"));
        assertFalse(root.contains("4.1.1.RELEASE"));
        assertTrue(root.contains("<packaging>pom</packaging>"));
        assertTrue(root.contains("<module>web</module>"));
        assertTrue(root.contains("<module>core</module>"));
        assertTrue(root.contains("<dependencyManagement>"));
        assertTrue(web.contains("<artifactId>spring-boot-starter-web</artifactId>"));
        assertTrue(web.contains("<artifactId>spring-boot-maven-plugin</artifactId>"));
        assertTrue(web.contains("<artifactId>core</artifactId>"));
        assertTrue(web.contains("<relativePath>../pom.xml</relativePath>"));
        assertTrue(Files.isRegularFile(project.resolve(
                "web/src/main/java/com/example/plataforma/PlataformaApplication.java")));
        assertTrue(Files.isRegularFile(project.resolve(
                "core/src/main/java/com/example/plataforma/core/CoreModule.java")));
        assertTrue(Files.isRegularFile(project.resolve("mvnw.cmd")));

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);
        assertEquals(JavaProjectKind.MAVEN_MULTIMODULE, descriptor.kind());
        assertEquals(4, descriptor.modules().size());
    }

    @Test
    void theGradleBuildPointsToTheGeneratedMainClass() throws IOException {
        Path project = create(JavaTemplate.GRADLE_APPLICATION, "gradle-app");

        String build = Files.readString(project.resolve("build.gradle.kts"));

        assertTrue(build.contains("mainClass.set(\"com.example.gradle.app.GradleAppApplication\")"));
        assertTrue(build.contains("useJUnitPlatform()"));
    }

    @Test
    void createsAPlainJavaFolder() throws IOException {
        Path project = create(JavaTemplate.PLAIN_JAVA, "exercicio");

        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(project);

        assertEquals(JavaProjectKind.PLAIN_JAVA, descriptor.kind());
        assertTrue(Files.isRegularFile(
                project.resolve("src/com/example/exercicio/Main.java")));
        assertFalse(Files.exists(project.resolve("pom.xml")));
    }

    @Test
    void fillsInSensibleDefaultsForBlankFields() {
        JavaProjectScaffolder.ProjectRequest request = new JavaProjectScaffolder.ProjectRequest(
                workspace.resolve("Demo"), JavaTemplate.MAVEN_APPLICATION, "", "", "", 0, null);

        assertEquals("com.example", request.groupId());
        assertEquals("demo", request.artifactId());
        assertEquals("1.0.0-SNAPSHOT", request.version());
        assertEquals("", request.description());
        assertEquals("com.example.demo", request.packageName());
        assertEquals(21, request.javaVersion());
    }

    @Test
    void theVersionChosenInTheWizardReachesThePom() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "demo", "2.3.1", "");

        String pom = Files.readString(project.resolve("pom.xml"));

        assertTrue(pom.contains("<version>2.3.1</version>"));
        assertFalse(pom.contains("1.0.0-SNAPSHOT"));
    }

    @Test
    void theVersionAlsoTiesTheChildModulesToTheAggregator() throws IOException {
        Path project = create(JavaTemplate.MAVEN_MULTIMODULE, "plataforma", "4.0.0", "");

        String parent = Files.readString(project.resolve("pom.xml"));
        String child = Files.readString(project.resolve("core/pom.xml"));

        assertTrue(parent.contains("<version>4.0.0</version>"));
        assertTrue(child.contains("<version>4.0.0</version>"),
                "o modulo filho herda a versao declarada no agregador");
    }

    @Test
    void theGradleBuildCarriesTheVersionAndDescription() throws IOException {
        Path project = create(JavaTemplate.GRADLE_APPLICATION, "gradle-app", "0.9.0", "Meu app");

        String build = Files.readString(project.resolve("build.gradle.kts"));

        assertTrue(build.contains("version = \"0.9.0\""));
        assertTrue(build.contains("description = \"Meu app\""));
    }

    @Test
    void anEmptyDescriptionLeavesNoEmptyTagBehind() throws IOException {
        Path pomProject = create(JavaTemplate.MAVEN_APPLICATION, "demo", "1.0.0", "");
        Path gradleProject = create(JavaTemplate.GRADLE_APPLICATION, "g", "1.0.0", "");

        assertFalse(Files.readString(pomProject.resolve("pom.xml")).contains("<description>"));
        assertFalse(Files.readString(gradleProject.resolve("build.gradle.kts")).contains("description ="));
    }

    @Test
    void theDescriptionIsEscapedBeforeGoingIntoTheXml() throws IOException {
        Path project = create(JavaTemplate.MAVEN_APPLICATION, "demo", "1.0.0", "Faturamento & Caixa");

        String pom = Files.readString(project.resolve("pom.xml"));

        assertTrue(pom.contains("<description>Faturamento &amp; Caixa</description>"));
    }

    @Test
    void derivesTheMainClassNameFromTheArtifact() {
        assertEquals("MinhaAppApplication", request("minha-app").mainClassName());
        assertEquals("DemoApplication", request("demo").mainClassName());
        assertEquals("MinhaAppApplication", request("minha_app").mainClassName());
    }

    @Test
    void gitignoreMatchesTheBuildSystem() throws IOException {
        String maven = Files.readString(
                create(JavaTemplate.MAVEN_APPLICATION, "m").resolve(".gitignore"));
        String gradle = Files.readString(
                create(JavaTemplate.GRADLE_APPLICATION, "g").resolve(".gitignore"));

        assertTrue(maven.contains("/target/"));
        assertTrue(gradle.contains("/build/"));
        assertTrue(gradle.contains("/.gradle/"));
    }

    private Path create(JavaTemplate template, String name) throws IOException {
        return JavaProjectScaffolder.create(new JavaProjectScaffolder.ProjectRequest(
                workspace.resolve(name), template, "com.example", name,
                "com.example." + name.replace('-', '.').replace('_', '.'), 21, List.of()));
    }

    private Path create(JavaTemplate template, String name, String version, String description)
            throws IOException {
        return JavaProjectScaffolder.create(new JavaProjectScaffolder.ProjectRequest(
                workspace.resolve(name + "-" + version), template, "com.example", name, version,
                description, "com.example." + name.replace('-', '.').replace('_', '.'), 21, List.of()));
    }

    private JavaProjectScaffolder.ProjectRequest request(String artifactId) {
        return new JavaProjectScaffolder.ProjectRequest(workspace.resolve(artifactId),
                JavaTemplate.MAVEN_APPLICATION, "com.example", artifactId,
                "com.example", 21, List.of());
    }
}
