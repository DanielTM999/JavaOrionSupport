package dtm.ide.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsWorkspaceLeaseTest {

    @TempDir
    Path root;

    @Test
    void aSecondWindowOnTheSameProjectGetsItsOwnWorkspace() throws Exception {
        Path base = root.resolve("Projeto-abc");
        try (JdtLsWorkspaceLease first = JdtLsWorkspaceLease.acquire(base);
             JdtLsWorkspaceLease second = JdtLsWorkspaceLease.acquire(base)) {
            assertEquals(base.toAbsolutePath().normalize(), first.workspace());
            assertEquals(root.resolve("Projeto-abc-w2").toAbsolutePath().normalize(), second.workspace());
        }
        try (JdtLsWorkspaceLease again = JdtLsWorkspaceLease.acquire(base)) {
            assertEquals(base.toAbsolutePath().normalize(), again.workspace());
        }
    }

    @Test
    void theRecordedServerIsFoundWhileAliveAndForgottenAfterItExits() throws Exception {
        Path workspace = root.resolve("ws");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                JdtLsServiceTest.IdleServer.class.getName()).start();
        try (JdtLsWorkspaceLease lease = JdtLsWorkspaceLease.acquire(workspace)) {
            assertEquals('R', child.getInputStream().read());
            lease.recordServer(child.toHandle());

            assertEquals(child.pid(), lease.recordedServer().orElseThrow().pid());

            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            assertTrue(lease.recordedServer().isEmpty());
        } finally {
            child.destroyForcibly();
        }
        assertFalse(Files.exists(workspace.resolve(JdtLsWorkspaceLease.PID_FILE)));
    }

    @Test
    void theWorkspaceIsRecognizedInAWindowsCommandLine() {
        Path workspace = Path.of("D:\\Programs\\Orion\\sdk\\workspaces\\1.60.0\\SwingTools-aedd5f6a");
        String commandLine = "\"C:\\jdk\\bin\\java.exe\" -Xmx2G -jar "
                + "D:\\jdtls\\plugins\\org.eclipse.equinox.launcher_1.7.0.jar -configuration "
                + "D:\\jdtls\\config_win -data D:\\Programs\\Orion\\sdk\\workspaces\\1.60.0\\SwingTools-aedd5f6a";

        assertTrue(JdtLsWorkspaceLease.commandLineUsesWorkspace(commandLine, workspace));
        assertFalse(JdtLsWorkspaceLease.commandLineUsesWorkspace(commandLine + "-w2", workspace));
        assertFalse(JdtLsWorkspaceLease.commandLineUsesWorkspace(
                "java -data D:\\Programs\\Orion\\sdk\\workspaces\\1.60.0\\SwingTools-aedd5f6a", workspace));
    }
}
