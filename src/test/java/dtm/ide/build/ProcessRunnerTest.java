package dtm.ide.build;

import dtm.ide.run.OwnedRunProcesses;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void cancelTerminatesWrapperAndChildProcess() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        AtomicLong childPid = new AtomicLong(-1);
        CompletableFuture<Integer> execution = CompletableFuture.supplyAsync(() -> runner.run(
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        WrapperProcess.class.getName()), Path.of("."), Map.of(), line -> {
                            if (line.startsWith("CHILD_PID=")) {
                                childPid.set(Long.parseLong(line.substring("CHILD_PID=".length())));
                            }
                        }));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (childPid.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(childPid.get() > 0);
        runner.cancel();
        assertNotEquals(0, execution.get(5, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void cancelBeforeRunKillsTheProcessRightAfterItStarts() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        runner.cancel();

        int exit = CompletableFuture.supplyAsync(() -> runner.run(
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        SleepingProcess.class.getName()), Path.of("."), Map.of(), line -> {
                        }))
                .get(10, TimeUnit.SECONDS);

        assertEquals(-1, exit);
        assertFalse(runner.isRunning());
    }

    @Test
    void pluginUnloadTerminatesAnActiveBuildAndItsChild() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        AtomicLong childPid = new AtomicLong(-1);
        CompletableFuture<Integer> execution = CompletableFuture.supplyAsync(() -> runner.run(
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        WrapperProcess.class.getName()), Path.of("."), Map.of(), line -> {
                            if (line.startsWith("CHILD_PID=")) {
                                childPid.set(Long.parseLong(line.substring("CHILD_PID=".length())));
                            }
                        }));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (childPid.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(childPid.get() > 0);

        OwnedRunProcesses.shutdownAll();

        assertNotEquals(0, execution.get(5, TimeUnit.SECONDS));
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    public static final class WrapperProcess {
        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(javaExecutable(), "-cp",
                    System.getProperty("java.class.path"), SleepingProcess.class.getName()).start();
            System.out.println("CHILD_PID=" + child.pid());
            System.out.flush();
            child.waitFor();
        }
    }

    public static final class SleepingProcess {
        public static void main(String[] args) throws Exception {
            Thread.sleep(TimeUnit.MINUTES.toMillis(2));
        }
    }
}
