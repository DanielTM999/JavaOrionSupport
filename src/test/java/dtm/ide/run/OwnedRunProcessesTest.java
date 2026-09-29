package dtm.ide.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedRunProcessesTest {

    @Test
    void unloadTerminatesEveryLocalRunIncludingDuplicateConfigurations() throws Exception {
        Process first = idleJvm();
        Process second = idleJvm();
        try {
            long generation = OwnedRunProcesses.launchGeneration();
            OwnedRunProcesses.register(first, generation);
            OwnedRunProcesses.register(second, generation);

            OwnedRunProcesses.shutdownAll();

            assertTrue(first.waitFor(3, TimeUnit.SECONDS));
            assertTrue(second.waitFor(3, TimeUnit.SECONDS));
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @Test
    void processStartedBeforeUnloadCannotEscapeLateRegistration() throws Exception {
        long generation = OwnedRunProcesses.launchGeneration();
        OwnedRunProcesses.shutdownAll();
        Process late = idleJvm();
        try {
            OwnedRunProcesses.register(late, generation);
            assertTrue(late.waitFor(3, TimeUnit.SECONDS));
        } finally {
            late.destroyForcibly();
        }
    }

    private static Process idleJvm() throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), Idle.class.getName()).start();
    }

    public static final class Idle {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}
