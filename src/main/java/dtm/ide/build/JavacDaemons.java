package dtm.ide.build;

import dtm.ide.run.OwnedRunProcesses;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public final class JavacDaemons {

    static final String DAEMON_CLASS = "dtm.ide.build.daemon.CompilerDaemon";
    static final String REQUEST = "COMPILE\t";
    static final String END = "\u0001END ";
    static final int UNAVAILABLE = 97;

    private static final long START_TIMEOUT_MS = 30_000;
    private static final Map<Path, Daemon> DAEMONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();

    private JavacDaemons() {
    }

    public static int run(List<String> command, Path workingDirectory, Map<String, String> environment,
                          Consumer<String> output, ProcessRunner fallback) {
        Path home = jdkHomeOf(command);
        if (home == null) {
            return fallback.run(command, workingDirectory, environment, output);
        }
        Daemon daemon = DAEMONS.compute(home, (key, existing) ->
                existing != null && existing.isAlive() ? existing : Daemon.start(key));
        if (daemon == null) {
            DAEMONS.remove(home);
            return fallback.run(command, workingDirectory, environment, output);
        }
        List<String> lines = new ArrayList<>();
        OptionalInt exitCode = daemon.compile(command.subList(1, command.size()), lines);
        if (exitCode.isPresent()) {
            lines.forEach(output);
            return exitCode.getAsInt();
        }
        DAEMONS.remove(home, daemon);
        daemon.close();
        if (daemon.cancelled()) {
            return -1;
        }
        log.info("Compilador residente indisponivel para {}; usando o javac direto", home);
        return fallback.run(command, workingDirectory, environment, output);
    }

    public static void cancelAll() {
        for (Daemon daemon : List.copyOf(DAEMONS.values())) {
            if (daemon.busy()) {
                daemon.cancel();
            }
        }
    }

    public static void shutdownAll() {
        for (Daemon daemon : List.copyOf(DAEMONS.values())) {
            daemon.close();
        }
        DAEMONS.clear();
    }

    static int activeDaemons() {
        return (int) DAEMONS.values().stream().filter(Daemon::isAlive).count();
    }

    static Path jdkHomeOf(List<String> command) {
        if (command == null || command.size() < 2) {
            return null;
        }
        Path javac = Path.of(command.getFirst()).toAbsolutePath().normalize();
        Path bin = javac.getParent();
        return bin == null || bin.getParent() == null ? null : bin.getParent();
    }

    static String argumentFileContent(List<String> arguments) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        for (String argument : arguments) {
            Path nested = argument.startsWith("@") ? Path.of(argument.substring(1)) : null;
            if (nested != null && Files.isRegularFile(nested)) {
                content.append(Files.readString(nested, StandardCharsets.UTF_8)).append(System.lineSeparator());
            } else {
                content.append(quote(argument)).append(System.lineSeparator());
            }
        }
        return content.toString();
    }

    static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void installShutdownHook() {
        if (SHUTDOWN_HOOK.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(JavacDaemons::shutdownAll,
                        "javac-daemons-shutdown"));
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private static final class Daemon {

        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean busy = new AtomicBoolean();

        private Daemon(Process process) {
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8));
            this.output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8));
        }

        static Daemon start(Path jdkHome) {
            Path location = daemonClasspath();
            Path java = jdkHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            if (location == null || !Files.isRegularFile(java)) {
                return null;
            }
            installShutdownHook();
            List<String> command = List.of(java.toString(), "-Xms64m", "-Xmx2g",
                    "-XX:+UseParallelGC", "-Dfile.encoding=UTF-8", "-cp", location.toString(),
                    DAEMON_CLASS);
            Daemon daemon = null;
            try {
                long launchGeneration = OwnedRunProcesses.launchGeneration();
                Process process = new ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                OwnedRunProcesses.register(process, launchGeneration);
                daemon = new Daemon(process);
                Daemon started = daemon;
                String ready = CompletableFuture.supplyAsync(started::readLine)
                        .get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (ready != null && ready.equals(END + 0)) {
                    log.info("Compilador residente iniciado com a JDK {}", jdkHome);
                    return daemon;
                }
                log.info("Compilador residente recusado pela JDK {}: {}", jdkHome, ready);
            } catch (Exception e) {
                log.debug("Falha ao iniciar o compilador residente em {}: {}", jdkHome, e.getMessage());
            }
            if (daemon != null) {
                daemon.close();
            }
            return null;
        }

        private static Path daemonClasspath() {
            try {
                return Path.of(JavacDaemons.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI());
            } catch (Exception e) {
                return null;
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        boolean cancelled() {
            return cancelled.get();
        }

        boolean busy() {
            return busy.get();
        }

        synchronized OptionalInt compile(List<String> arguments, List<String> lines) {
            Path argumentFile = null;
            busy.set(true);
            try {
                argumentFile = Files.createTempFile("orion-javac", ".args");
                String content = argumentFileContent(arguments);
                Files.writeString(argumentFile, content, StandardCharsets.UTF_8);
                input.write(REQUEST + argumentFile);
                input.newLine();
                input.flush();
                String line;
                while ((line = readLine()) != null) {
                    if (line.startsWith(END)) {
                        int exitCode = Integer.parseInt(line.substring(END.length()).trim());
                        return exitCode == UNAVAILABLE ? OptionalInt.empty()
                                : OptionalInt.of(exitCode);
                    }
                    lines.add(line);
                }
                return OptionalInt.empty();
            } catch (Exception e) {
                log.debug("Compilador residente falhou: {}", e.getMessage());
                return OptionalInt.empty();
            } finally {
                busy.set(false);
                JavacCommands.deleteQuietly(argumentFile);
            }
        }

        private String readLine() {
            try {
                return output.readLine();
            } catch (Exception e) {
                return null;
            }
        }

        void cancel() {
            cancelled.set(true);
            process.destroyForcibly();
        }

        void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }
}
