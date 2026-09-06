package dtm.ide.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoStageProcessTerminatorTest {

    @Test
    void firstRequestOnlyAttemptsTheGracefulStop() {
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicBoolean graceful = new AtomicBoolean();
        FakeProcessHandle root = new FakeProcessHandle(1, new ArrayList<>());
        TwoStageProcessTerminator terminator = new TwoStageProcessTerminator(
                root, alive::get, () -> graceful.set(true));

        terminator.terminate();

        assertTrue(graceful.get());
        assertTrue(root.isAlive());
        assertTrue(terminator.isAlive());
    }

    @Test
    void secondRequestForceTerminatesTheWholeTreeChildrenFirst() {
        List<Long> terminationOrder = new ArrayList<>();
        FakeProcessHandle grandchild = new FakeProcessHandle(3, terminationOrder);
        FakeProcessHandle child = new FakeProcessHandle(2, terminationOrder, grandchild);
        FakeProcessHandle root = new FakeProcessHandle(1, terminationOrder, child);
        TwoStageProcessTerminator terminator = new TwoStageProcessTerminator(
                root, root::isAlive, () -> { });

        terminator.terminate();
        terminator.terminate();

        assertEquals(List.of(3L, 2L, 1L), terminationOrder);
        assertFalse(root.isAlive());
        assertFalse(child.isAlive());
        assertFalse(grandchild.isAlive());
    }

    @Test
    void fallsBackToTheProcessItselfWhenTheHandleIsUnsupported() {
        UnsupportedHandleProcess process = new UnsupportedHandleProcess();
        TwoStageProcessTerminator terminator = new TwoStageProcessTerminator(process);

        terminator.terminate();
        assertTrue(process.destroyed.get());
        assertTrue(process.isAlive());

        terminator.terminate();
        assertTrue(process.forciblyDestroyed.get());
        assertFalse(process.isAlive());
    }

    private static final class UnsupportedHandleProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean forciblyDestroyed = new AtomicBoolean();

        @Override public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }
        @Override public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }
        @Override public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public long pid() { throw new UnsupportedOperationException("pid"); }
        @Override public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("toHandle");
        }
        @Override public void destroy() { destroyed.set(true); }
        @Override public Process destroyForcibly() {
            forciblyDestroyed.set(true);
            alive.set(false);
            return this;
        }
    }

    private static final class FakeProcessHandle implements ProcessHandle {

        private final long pid;
        private final List<Long> terminationOrder;
        private final List<ProcessHandle> children;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private FakeProcessHandle(long pid, List<Long> terminationOrder,
                                  ProcessHandle... children) {
            this.pid = pid;
            this.terminationOrder = terminationOrder;
            this.children = List.of(children);
        }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return children.stream(); }
        @Override public Stream<ProcessHandle> descendants() {
            return children.stream().flatMap(child -> Stream.concat(Stream.of(child),
                    child.descendants()));
        }
        @Override public Info info() { return new FakeInfo(); }
        @Override public CompletableFuture<ProcessHandle> onExit() {
            return alive.get() ? new CompletableFuture<>()
                    : CompletableFuture.completedFuture(this);
        }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { return false; }
        @Override public boolean destroyForcibly() {
            if (alive.compareAndSet(true, false)) {
                terminationOrder.add(pid);
            }
            return true;
        }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }

    private static final class FakeInfo implements ProcessHandle.Info {
        @Override public Optional<String> command() { return Optional.empty(); }
        @Override public Optional<String> commandLine() { return Optional.empty(); }
        @Override public Optional<String[]> arguments() { return Optional.empty(); }
        @Override public Optional<Instant> startInstant() { return Optional.empty(); }
        @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
        @Override public Optional<String> user() { return Optional.empty(); }
    }
}
