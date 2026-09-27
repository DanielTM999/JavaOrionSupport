package dtm.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JavaIdeAdapterDiskBaselineTest {

    @TempDir
    Path dir;

    @Test
    void anEditorMovedWithUnsavedRenameEditsIsNotTakenAsMatchingTheDisk() throws Exception {
        Path moved = dir.resolve("Colaborador.java");
        Files.writeString(moved, "public class Funcionario {\r\n}\r\n", StandardCharsets.UTF_8);
        String unsavedEditor = "public class Colaborador {\n}\n";

        String baseline = JavaIdeAdapter.diskBaselineFor(moved, unsavedEditor);

        assertEquals("public class Funcionario {\n}\n", baseline);
        assertNotEquals(unsavedEditor, baseline);
    }

    @Test
    void anEditorOpenedFromDiskMatchesItsBaseline() throws Exception {
        Path file = dir.resolve("A.java");
        Files.writeString(file, "class A {\r\n}\r\n", StandardCharsets.UTF_8);
        String editor = "class A {\n}\n";

        assertEquals(editor, JavaIdeAdapter.diskBaselineFor(file, editor));
    }

    @Test
    void aFileThatOnlyExistsInTheEditorUsesTheEditorText() {
        Path missing = dir.resolve("Novo.java");

        assertEquals("class Novo {}", JavaIdeAdapter.diskBaselineFor(missing, "class Novo {}"));
    }
}
