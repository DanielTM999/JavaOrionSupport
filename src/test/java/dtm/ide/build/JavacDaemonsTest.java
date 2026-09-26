package dtm.ide.build;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacDaemonsTest {

    @TempDir
    Path root;

    @AfterEach
    void stopDaemons() {
        JavacDaemons.shutdownAll();
    }

    @Test
    void theResidentCompilerCompilesAndReportsErrorsLikeJavac() throws Exception {
        Path output = Files.createDirectories(root.resolve("classes dir"));
        Path valid = Files.writeString(root.resolve("Valida.java"), "public class Valida { int x; }");
        Path broken = Files.writeString(root.resolve("Quebrada.java"),
                "public class Quebrada { Inexistente campo; }");
        ProcessRunner fallback = new ProcessRunner();

        List<String> first = new ArrayList<>();
        int ok = JavacDaemons.run(command(output, valid), root, Map.of(), first::add, fallback);
        List<String> second = new ArrayList<>();
        int failed = JavacDaemons.run(command(output, broken), root, Map.of(), second::add, fallback);

        assertEquals(0, ok);
        assertTrue(Files.isRegularFile(output.resolve("Valida.class")));
        assertNotEquals(0, failed);
        assertTrue(second.stream().anyMatch(line -> line.contains("Quebrada.java")
                && line.contains("error")));
        assertEquals(1, JavacDaemons.activeDaemons());
    }

    @Test
    void sourcesPassedInAnArgumentFileAreExpandedForTheResidentCompiler() throws Exception {
        Path output = Files.createDirectories(root.resolve("saida"));
        Path first = Files.writeString(root.resolve("Primeira.java"), "public class Primeira {}");
        Path second = Files.writeString(root.resolve("Segunda.java"), "public class Segunda { Primeira p; }");
        Path sources = JavacCommands.writeArgumentFile(List.of(first, second));
        List<String> command = new ArrayList<>(command(output, first).subList(0, 5));
        command.add("@" + sources);

        int exitCode = JavacDaemons.run(command, root, Map.of(), line -> { }, new ProcessRunner());

        assertEquals(0, exitCode);
        assertTrue(Files.isRegularFile(output.resolve("Segunda.class")));
        assertEquals(1, JavacDaemons.activeDaemons());
    }

    @Test
    void argumentsWithSpacesAndBackslashesAreQuoted() {
        assertEquals("\"C:\\\\dir com espaco\\\\A.java\"", JavacDaemons.quote("C:\\dir com espaco\\A.java"));
    }

    private static List<String> command(Path output, Path source) {
        Path javac = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac");
        return List.of(javac.toString(), "-d", output.toString(), "-encoding", "UTF-8",
                source.toString());
    }
}
