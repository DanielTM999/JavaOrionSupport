package dtm.ide.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdtLsStartupLogTest {

    @TempDir
    Path root;

    @Test
    void followsTheServerFromJvmBootToTheProjectImport() throws Exception {
        Path file = root.resolve(".log");
        Files.writeString(file, "!MESSAGE >> initialize\n!MESSAGE JavaLanguageServerPlugin is started\n");
        JdtLsStartupLog log = new JdtLsStartupLog(file);

        assertEquals(JdtLsStartupLog.Phase.BOOTING, log.poll());
        append(file, "!SESSION 2026-10-16\n!MESSAGE class org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin is started\n");
        assertEquals(JdtLsStartupLog.Phase.STARTING_PLUGINS, log.poll());
        append(file, "!MESSAGE >> initialize\n");
        assertEquals(JdtLsStartupLog.Phase.IMPORTING, log.poll());
        assertEquals("JDT LS lendo o projeto (Maven/Gradle)", log.describe());
    }

    @Test
    void tellsWhenTheWorkspaceIsBeingRecovered() throws Exception {
        Path file = root.resolve(".log");
        JdtLsStartupLog log = new JdtLsStartupLog(file);

        Files.writeString(file, "!MESSAGE The workspace exited with unsaved changes in the previous session; refreshing workspace to recover changes.\n");

        assertEquals("iniciando a JVM do JDT LS (recuperando o workspace, que foi fechado sem salvar)",
                log.describe());
    }

    @Test
    void startsOverWhenTheLogIsRotated() throws Exception {
        Path file = root.resolve(".log");
        Files.writeString(file, "x".repeat(4096));
        JdtLsStartupLog log = new JdtLsStartupLog(file);

        Files.writeString(file, "!MESSAGE >> initialize\n");

        assertEquals(JdtLsStartupLog.Phase.IMPORTING, log.poll());
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardOpenOption.APPEND);
    }
}
