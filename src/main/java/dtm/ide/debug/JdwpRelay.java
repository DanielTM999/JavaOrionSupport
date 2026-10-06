package dtm.ide.debug;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class JdwpRelay implements AutoCloseable {

    private final ServerSocket targetServer;
    private final ServerSocket adapterServer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Thread> pumps = new ArrayList<>();

    private volatile Socket target;
    private volatile Socket adapter;

    private JdwpRelay(ServerSocket targetServer, ServerSocket adapterServer) {
        this.targetServer = targetServer;
        this.adapterServer = adapterServer;
    }

    public static JdwpRelay open(String bindHost, int port) throws IOException {
        ServerSocket targetServer = new ServerSocket();
        try {
            targetServer.setReuseAddress(true);
            targetServer.bind(new InetSocketAddress(
                    bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost.trim(), port));
            ServerSocket adapterServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            return new JdwpRelay(targetServer, adapterServer);
        } catch (IOException error) {
            closeQuietly(targetServer);
            throw error;
        }
    }

    public int adapterPort() {
        return adapterServer.getLocalPort();
    }

    public int listenPort() {
        return targetServer.getLocalPort();
    }

    public void awaitTarget(int timeoutMillis) throws IOException {
        targetServer.setSoTimeout(Math.max(0, timeoutMillis));
        target = targetServer.accept();
        target.setTcpNoDelay(true);
        closeQuietly(targetServer);
        log.info("JVM remota conectada ao relay JDWP a partir de {}",
                target.getRemoteSocketAddress());
        startPump();
    }

    private void startPump() {
        Thread accept = new Thread(() -> {
            try {
                Socket connected = adapterServer.accept();
                connected.setTcpNoDelay(true);
                adapter = connected;
                closeQuietly(adapterServer);
                pump(target, connected, "jvm->adapter");
                pump(connected, target, "adapter->jvm");
            } catch (IOException error) {
                if (!closed.get()) {
                    log.warn("Falha ao aceitar o debug adapter no relay JDWP: {}",
                            error.getMessage());
                    close();
                }
            }
        }, "orion-jdwp-relay-accept");
        accept.setDaemon(true);
        register(accept);
        accept.start();
    }

    private void pump(Socket from, Socket to, String name) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream input = from.getInputStream();
                 OutputStream output = to.getOutputStream()) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException error) {
                if (!closed.get()) {
                    log.debug("Relay JDWP {} encerrado: {}", name, error.getMessage());
                }
            } finally {
                close();
            }
        }, "orion-jdwp-relay-" + name);
        thread.setDaemon(true);
        register(thread);
        thread.start();
    }

    private void register(Thread thread) {
        synchronized (pumps) {
            pumps.add(thread);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(targetServer);
        closeQuietly(adapterServer);
        closeQuietly(target);
        closeQuietly(adapter);
    }

    public boolean isClosed() {
        return closed.get();
    }

    void awaitShutdown(long millis) throws InterruptedException {
        List<Thread> snapshot;
        synchronized (pumps) {
            snapshot = List.copyOf(pumps);
        }
        for (Thread thread : snapshot) {
            thread.join(millis);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
