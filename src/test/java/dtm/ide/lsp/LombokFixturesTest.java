package dtm.ide.lsp;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LombokFixturesTest {

    @TempDir
    Path sdkRoot;

    @Test
    void detectsLombokInTheMavenFixture() {
        JavaProjectDescriptor descriptor = describe("lombok-maven");

        assertTrue(descriptor.isMaven());
        assertEquals(Optional.of("1.18.42"),
                new LombokAgentResolver(sdkRoot).detectVersion(descriptor));
    }

    @Test
    void detectsLombokInTheGradleFixture() {
        JavaProjectDescriptor descriptor = describe("lombok-gradle");

        assertTrue(descriptor.isGradle());
        assertEquals(Optional.of("1.18.42"),
                new LombokAgentResolver(sdkRoot).detectVersion(descriptor));
    }

    @Test
    void detectsLombokDeclaredThroughTheGradleCatalog() {
        JavaProjectDescriptor descriptor = describe("lombok-gradle-catalog");

        assertEquals(Optional.of("1.18.42"),
                new LombokAgentResolver(sdkRoot).detectVersion(descriptor));
    }

    @Test
    void theFixturesCoverTheAnnotationsThatGenerateMembers() {
        String maven = sourcesOf("lombok-maven");
        String gradle = sourcesOf("lombok-gradle");

        for (String annotation : List.of("@Getter", "@Setter", "@Builder",
                "@RequiredArgsConstructor", "@Slf4j", "@Accessors(chain = true)")) {
            assertTrue(maven.contains(annotation), "faltou " + annotation + " no fixture Maven");
        }
        for (String annotation : List.of("@Data", "@Value", "@With", "@SuperBuilder", "@Slf4j")) {
            assertTrue(gradle.contains(annotation), "faltou " + annotation + " no fixture Gradle");
        }
        assertTrue(maven.contains("Customer.builder()"));
        assertTrue(gradle.contains("Order.builder()"));
    }

    static Path fixture(String name) {
        try {
            return Path.of(LombokFixturesTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JavaProjectDescriptor describe(String name) {
        return JavaProjectConventions.describe(fixture(name));
    }

    private static String sourcesOf(String name) {
        try (Stream<Path> tree = Files.walk(fixture(name))) {
            return tree.filter(path -> path.toString().endsWith(".java"))
                    .map(LombokFixturesTest::read)
                    .reduce("", (left, right) -> left + "\n" + right);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
