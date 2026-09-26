package dtm.ide.debug;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public final class BuildToolDebugListener implements AutoCloseable {

    public interface Attacher {
        void attach(JavaAttachTarget target, Consumer<Boolean> detached);
    }

    private final Attacher attacher;
    private final Runnable cancelProcess;
    private final Executor executor;
    private final int port;
    private final Deque<JdwpRelay> pending = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile JdwpRelay listening;
    private JdwpRelay active;
    private boolean attached;

    private BuildToolDebugListener(Attacher attacher, Runnable cancelProcess, Executor executor,
                                   JdwpRelay first) {
        this.attacher = attacher;
        this.cancelProcess = cancelProcess == null ? () -> {
        } : cancelProcess;
        this.executor = executor == null ? command -> Thread.startVirtualThread(command) : executor;
        this.port = first.listenPort();
        this.listening = first;
    }

    public static BuildToolDebugListener open(Attacher attacher, Runnable cancelProcess,
                                              Executor executor) throws IOException {
        JdwpRelay first = JdwpRelay.open(JavaAttachTarget.LOCALHOST, 0);
        BuildToolDebugListener listener =
                new BuildToolDebugListener(attacher, cancelProcess, executor, first);
        Thread thread = new Thread(listener::acceptLoop, "orion-build-debug-accept");
        thread.setDaemon(true);
        thread.start();
        return listener;
    }

    public int listenPort() {
        return port;
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void acceptLoop() {
        JdwpRelay relay = listening;
        while (relay != null && !closed.get()) {
            try {
                relay.awaitTarget(0);
            } catch (IOException error) {
                relay.close();
                return;
            }
            enqueue(relay);
            try {
                relay = JdwpRelay.open(JavaAttachTarget.LOCALHOST, port);
            } catch (IOException error) {
                log.warn("Nao foi possivel reabrir a porta de debug {}: {}", port, error.getMessage());
                return;
            }
            listening = relay;
            if (closed.get()) {
                relay.close();
                return;
            }
        }
    }

    private synchronized void enqueue(JdwpRelay relay) {
        if (closed.get()) {
            relay.close();
            return;
        }
        pending.addLast(relay);
        attachNext();
    }

    private synchronized void attachNext() {
        if (attached || closed.get()) {
            return;
        }
        JdwpRelay next = pending.pollFirst();
        if (next == null) {
            return;
        }
        attached = true;
        active = next;
        JavaAttachTarget target = new JavaAttachTarget(JavaAttachTarget.LOCALHOST,
                next.adapterPort(), JavaAttachTarget.DEFAULT_TIMEOUT, true);
        executor.execute(() -> attacher.attach(target, this::detached));
    }

    private void detached(Boolean stopRequested) {
        synchronized (this) {
            attached = false;
            if (active != null) {
                active.close();
            }
            active = null;
        }
        if (Boolean.TRUE.equals(stopRequested)) {
            close();
            cancelProcess.run();
            return;
        }
        attachNext();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        JdwpRelay current = listening;
        if (current != null) {
            current.close();
        }
        synchronized (this) {
            pending.forEach(JdwpRelay::close);
            pending.clear();
        }
    }
}
