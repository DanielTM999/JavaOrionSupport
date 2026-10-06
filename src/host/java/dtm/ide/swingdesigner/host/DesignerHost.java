package dtm.ide.swingdesigner.host;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class DesignerHost {

    public static final long IDLE_TIMEOUT_MS = 15 * 60_000L;
    public static final int PROTOCOL = 1;

    private DesignerHost() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");
        DataOutputStream protocolOut = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(FileDescriptor.out), 64 * 1024));
        DataInputStream protocolIn = new DataInputStream(new BufferedInputStream(
                new FileInputStream(FileDescriptor.in), 64 * 1024));
        final Frames frames = new Frames(protocolIn, protocolOut);
        System.setOut(new PrintStream(new LogStream(frames, "out"), true, "UTF-8"));
        System.setErr(new PrintStream(new LogStream(frames, "err"), true, "UTF-8"));
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                System.err.println("[" + thread.getName() + "] " + Instantiator.describe(error));
            }
        });

        final HostSession session = new HostSession();
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        startWatchdog(session, lastActivity);

        Map<String, Object> ready = new LinkedHashMap<String, Object>();
        ready.put("event", "ready");
        ready.put("protocol", PROTOCOL);
        ready.put("javaVersion", System.getProperty("java.version"));
        frames.write(ready, null);

        Frames.Frame frame;
        while ((frame = frames.read()) != null) {
            lastActivity.set(System.currentTimeMillis());
            Object id = frame.header.get("id");
            String op = Json.string(frame.header, "op");
            if ("shutdown".equals(op)) {
                respond(frames, id, new LinkedHashMap<String, Object>(), null);
                break;
            }
            byte[][] blob = new byte[1][];
            try {
                Map<String, Object> result = session.handle(op, frame.header, blob);
                respond(frames, id, result, blob[0]);
            } catch (Throwable error) {
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("id", id);
                response.put("ok", Boolean.FALSE);
                response.put("error", Instantiator.describe(error));
                frames.write(response, null);
            }
            lastActivity.set(System.currentTimeMillis());
        }
        session.dispose();
        System.exit(0);
    }

    private static void respond(Frames frames, Object id, Map<String, Object> result, byte[] blob)
            throws IOException {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("id", id);
        response.put("ok", Boolean.TRUE);
        response.put("result", result);
        frames.write(response, blob);
    }

    private static void startWatchdog(final HostSession session, final AtomicLong lastActivity) {
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    boolean idle = System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS;
                    if (idle && !session.hasVisibleWindows()) {
                        System.exit(0);
                    }
                }
            }
        }, "designer-host-idle");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static final class LogStream extends OutputStream {

        private final Frames frames;
        private final String stream;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        LogStream(Frames frames, String stream) {
            this.frames = frames;
            this.stream = stream;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') {
                flushLine();
                return;
            }
            bytes.write(b);
            if (bytes.size() > 8192) {
                flushLine();
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (bytes.size() > 0) {
                flushLine();
            }
        }

        private void flushLine() throws IOException {
            String line = new String(bytes.toByteArray(), "UTF-8");
            bytes.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("event", "log");
            event.put("stream", stream);
            event.put("text", line);
            frames.write(event, null);
        }
    }
}
