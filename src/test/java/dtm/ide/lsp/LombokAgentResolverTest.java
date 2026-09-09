package dtm.ide.lsp;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LombokAgentResolverTest {

    @TempDir
    Path root;

    @TempDir
    Path sdkRoot;

    @Test
    void readsTheVersionDeclaredInTheDependency() throws IOException {
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.30</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(Optional.of("1.18.30"), detectVersion());
    }

    @Test
    void resolvesTheVersionThroughAProperty() throws IOException {
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <properties>
                        <lombok.version>1.18.42</lombok.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(Optional.of("1.18.42"), detectVersion());
    }

    @Test
    void findsLombokDeclaredOnlyAsAnAnnotationProcessorPath() throws IOException {
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <build><plugins><plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration><annotationProcessorPaths><path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.36</version>
                        </path></annotationProcessorPaths></configuration>
                    </plugin></plugins></build>
                </project>
                """);

        assertEquals(Optional.of("1.18.36"), detectVersion());
    }

    @Test
    void readsTheGradleShortNotation() throws IOException {
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    compileOnly 'org.projectlombok:lombok:1.18.34'
                    annotationProcessor 'org.projectlombok:lombok:1.18.34'
                }
                """);

        assertEquals(Optional.of("1.18.34"), detectVersion());
    }

    @Test
    void aProjectWithoutLombokAsksForNothing() throws IOException {
        pom("<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");

        assertTrue(detectVersion().isEmpty());
    }

    @Test
    void aJarAlreadyInTheCacheIsNotDownloadedAgain() throws IOException {
        Path cached = sdkRoot.resolve("lombok").resolve("lombok-9.9.9.jar");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "nao e um jar de verdade, mas existe");

        assertEquals(Optional.of(cached), new LombokAgentResolver(sdkRoot).jarFor("9.9.9"));
    }

    @Test
    void withoutASdkRootThereIsNowhereToCacheADownload() {
        assertTrue(new LombokAgentResolver(null).jarFor("0.0.0-inexistente").isEmpty());
        assertTrue(new LombokAgentResolver(sdkRoot).jarFor("").isEmpty());
    }

    @Test
    void readsTheKotlinDslNotation() throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies {
                    compileOnly("org.projectlombok:lombok:1.18.38")
                    annotationProcessor("org.projectlombok:lombok:1.18.38")
                }
                """);

        assertEquals(Optional.of("1.18.38"), detectVersion());
    }

    @Test
    void readsTheVersionFromTheGradleCatalog() throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies {
                    compileOnly(libs.lombok)
                    annotationProcessor(libs.lombok)
                }
                """);
        Files.createDirectories(root.resolve("gradle"));
        Files.writeString(root.resolve("gradle/libs.versions.toml"), """
                [versions]
                lombok = "1.18.40"

                [libraries]
                lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
                """);

        assertEquals(Optional.of("1.18.40"), detectVersion());
    }

    @Test
    void readsTheShortCatalogNotation() {
        assertEquals(Optional.of("1.18.32"), LombokAgentResolver.catalogVersion("""
                [libraries]
                lombok = "org.projectlombok:lombok:1.18.32"
                """));
    }

    @Test
    void aLombokJarFromTheResolvedClasspathIsPreferred() throws IOException {
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <dependencies><dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.30</version>
                    </dependency></dependencies>
                </project>
                """);
        Path jar = agentJar(root.resolve("lombok-1.18.30.jar"));

        LombokAgentResolver.Agent agent = new LombokAgentResolver(sdkRoot)
                .resolveAgent(JavaProjectConventions.describe(root), List.of(jar));

        assertTrue(agent.declared());
        assertEquals(jar, agent.jar());
        assertEquals("1.18.30", agent.version());
    }

    @Test
    void aProjectWithoutLombokDoesNotAskForAnAgent() throws IOException {
        pom("<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");

        LombokAgentResolver.Agent agent = new LombokAgentResolver(sdkRoot)
                .resolveAgent(JavaProjectConventions.describe(root), List.of());

        assertFalse(agent.declared());
        assertNull(agent.jar());
    }

    @Test
    void aCorruptedJarIsRejectedAndRemovedFromTheCache() throws IOException {
        Path cached = sdkRoot.resolve("lombok").resolve("lombok-1.18.31.jar");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "conteudo corrompido");
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <dependencies><dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.31</version>
                    </dependency></dependencies>
                </project>
                """);
        Path tested = agentJar(sdkRoot.resolve("lombok")
                .resolve("lombok-" + LombokAgentResolver.TESTED_VERSION + ".jar"));

        LombokAgentResolver.Agent agent = withoutLocalRepositories(() ->
                new LombokAgentResolver(sdkRoot)
                        .resolveAgent(JavaProjectConventions.describe(root), List.of()));

        assertFalse(Files.exists(cached), "o jar corrompido deveria sair do cache");
        assertEquals(tested, agent.jar());
        assertEquals(LombokAgentResolver.TESTED_VERSION, agent.version());
    }

    @Test
    void aDetectedLombokWithoutAnyUsableJarFailsVisibly() throws IOException {
        pom("""
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <dependencies><dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.31</version>
                    </dependency></dependencies>
                </project>
                """);

        LombokAgentResolver.Agent agent = withoutLocalRepositories(() ->
                new LombokAgentResolver(null)
                        .resolveAgent(JavaProjectConventions.describe(root), List.of()));

        assertTrue(agent.declared());
        assertNull(agent.jar());
        assertFalse(agent.isUsable());
        assertNotNull(agent.failure());
    }

    @Test
    void onlyAJarWithTheLombokAgentManifestIsAccepted() throws IOException {
        assertTrue(LombokAgentResolver.isUsableAgentJar(agentJar(root.resolve("good.jar"))));
        assertFalse(LombokAgentResolver.isUsableAgentJar(root.resolve("missing.jar")));

        Path text = root.resolve("broken.jar");
        Files.writeString(text, "nao e um jar");
        assertFalse(LombokAgentResolver.isUsableAgentJar(text));

        Path withoutPremain = root.resolve("plain.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(withoutPremain),
                new Manifest())) {
            jar.putNextEntry(new ZipEntry("a.txt"));
            jar.closeEntry();
        }
        assertFalse(LombokAgentResolver.isUsableAgentJar(withoutPremain));
    }

    @Test
    void theTestedAgentFollowsTheVersionValidatedByThePlugin() {
        assertEquals("1.18.48", LombokAgentResolver.TESTED_VERSION);
    }

    private LombokAgentResolver.Agent withoutLocalRepositories(
            java.util.function.Supplier<LombokAgentResolver.Agent> resolution) throws IOException {
        String previous = System.getProperty("user.home");
        Path isolated = Files.createDirectories(sdkRoot.resolve("home"));
        System.setProperty("user.home", isolated.toString());
        try {
            return resolution.get();
        } finally {
            if (previous == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previous);
            }
        }
    }

    private static Path agentJar(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "lombok.launch.Agent");
        try (OutputStream output = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new ZipEntry("lombok/launch/Agent.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return target;
    }

    private Optional<String> detectVersion() {
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        return new LombokAgentResolver(sdkRoot).detectVersion(descriptor);
    }

    private void pom(String content) throws IOException {
        Files.writeString(root.resolve(JavaProjectConventions.POM_FILE), content);
    }
}
