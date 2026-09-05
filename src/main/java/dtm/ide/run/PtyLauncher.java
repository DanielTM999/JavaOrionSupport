package dtm.ide.run;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class PtyLauncher {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");
    private static volatile Boolean available;

    private PtyLauncher() {
    }

    record Result(Process process, boolean pty) {
    }

    static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            Class.forName("com.pty4j.PtyProcessBuilder", false, PtyLauncher.class.getClassLoader());
            ok = true;
        } catch (Throwable t) {
            ok = false;
            log.info("pty4j indisponivel; a saida usara pipes simples: {}", t.toString());
        }
        available = ok;
        return ok;
    }

    static Result launch(List<String> command, Path directory, Map<String, String> environment)
            throws IOException {
        if (isAvailable()) {
            if (IS_WINDOWS) {
                try {
                    return new Result(startPty(command, directory, environment, true), true);
                } catch (Throwable conPtyFailure) {
                    log.warn("ConPTY indisponivel ({}); usando WinPTY como fallback.",
                            conPtyFailure.toString());
                    try {
                        return new Result(startPty(command, directory, environment, false), true);
                    } catch (Throwable winPtyFailure) {
                        log.warn("Falha ao iniciar o WinPTY ({}); usando pipes simples.",
                                winPtyFailure.toString());
                    }
                }
            } else {
                try {
                    return new Result(startPty(command, directory, environment, false), true);
                } catch (Throwable t) {
                    log.warn("Falha ao iniciar o PTY ({}); usando pipes simples.", t.toString());
                }
            }
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(terminalEnvironment(environment));
        return new Result(builder.start(), false);
    }

    private static Process startPty(List<String> command, Path directory,
                                    Map<String, String> environment,
                                    boolean useConPty) throws IOException {
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(command.toArray(new String[0]))
                .setEnvironment(terminalEnvironment(environment))
                .setDirectory(directory == null ? System.getProperty("user.dir") : directory.toString())
                .setInitialColumns(120)
                .setInitialRows(30)
                .setConsole(false)
                .setUseWinConPty(useConPty)
                .start();
        log.info("Processo iniciado sob PTY pid={} cmd={}", process.pid(), command);
        return process;
    }

    private static Map<String, String> terminalEnvironment(Map<String, String> environment) {
        Map<String, String> merged = new HashMap<>(System.getenv());
        if (environment != null) {
            merged.putAll(environment);
        }
        merged.put("TERM", "xterm-256color");
        merged.put("COLORTERM", "truecolor");
        return merged;
    }
}
