package dtm.ide.build.daemon;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;

public final class CompilerDaemon {

    public static final String REQUEST = "COMPILE\t";
    public static final String END = "\u0001END ";
    public static final int UNAVAILABLE = 97;
    public static final long IDLE_TIMEOUT_MS = 10 * 60_000L;
    public static final int MAX_COMPILATIONS = 150;

    private static final String UTF_8 = "UTF-8";

    private CompilerDaemon() {
    }

    public static void main(String[] args) throws Exception {
        final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                        System.exit(0);
                    }
                }
            }
        }, "compiler-daemon-idle");
        watchdog.setDaemon(true);
        watchdog.start();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, UTF_8));
        out.println(END + (compiler == null ? UNAVAILABLE : 0));
        int compilations = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.startsWith(REQUEST)) {
                continue;
            }
            lastActivity.set(System.currentTimeMillis());
            String argumentFile = line.substring(REQUEST.length());
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            int exitCode;
            if (compiler == null) {
                exitCode = UNAVAILABLE;
            } else {
                PrintStream sink = new PrintStream(captured, true, UTF_8);
                try {
                    exitCode = compiler.run(null, sink, sink, "@" + argumentFile);
                } catch (Throwable error) {
                    sink.println("error: " + error);
                    exitCode = UNAVAILABLE;
                } finally {
                    sink.flush();
                }
            }
            for (String output : captured.toString(UTF_8).split("\\R")) {
                if (!output.isEmpty()) {
                    out.println(output);
                }
            }
            out.println(END + exitCode);
            lastActivity.set(System.currentTimeMillis());
            if (++compilations >= MAX_COMPILATIONS) {
                break;
            }
        }
        System.exit(0);
    }
}
