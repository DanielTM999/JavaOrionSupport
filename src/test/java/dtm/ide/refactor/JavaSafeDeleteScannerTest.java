package dtm.ide.refactor;

import dtm.stools.component.panels.editor.code.api.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSafeDeleteScannerTest {

    @TempDir
    Path root;

    @Test
    void findsUsagesOfANewSourceEvenWithoutTheLanguageServerIndex() throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/example"));
        Path deleted = sourceRoot.resolve("TesteDelete.java");
        Files.writeString(deleted, "package example; public class TesteDelete {}");
        Path consumer = sourceRoot.resolve("App.java");
        Files.writeString(consumer, """
                package example;
                class App {
                    TesteDelete value = new TesteDelete();
                }
                """);

        List<Location> usages = JavaSafeDeleteScanner.findExternalUsages(root, List.of(deleted));

        assertEquals(2, usages.size());
        assertEquals(consumer.toUri().toString(), usages.getFirst().uri());
        assertEquals(2, usages.getFirst().range().start().line());
    }

    @Test
    void ignoresNamesInCommentsStringsBuildOutputAndTheDeletedSelection() throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/example"));
        Path deleted = sourceRoot.resolve("TesteDelete.java");
        Files.writeString(deleted, "class TesteDelete { TesteDelete self; }");
        Files.writeString(sourceRoot.resolve("Other.java"), """
                class Other {
                    // TesteDelete comment
                    String name = "TesteDelete";
                }
                """);
        Path generated = Files.createDirectories(root.resolve("target/generated"))
                .resolve("Generated.java");
        Files.writeString(generated, "class Generated { TesteDelete value; }");

        assertEquals(List.of(),
                JavaSafeDeleteScanner.findExternalUsages(root, List.of(deleted)));
    }
}
