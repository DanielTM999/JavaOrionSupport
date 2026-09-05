package dtm.ide.lsp;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private Optional<String> detectVersion() {
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        return new LombokAgentResolver(sdkRoot).detectVersion(descriptor);
    }

    private void pom(String content) throws IOException {
        Files.writeString(root.resolve(JavaProjectConventions.POM_FILE), content);
    }
}
