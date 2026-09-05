package dtm.ide.build;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public final class ProcessRunner {

    private final AtomicReference<Process> current = new AtomicReference<>();

    public int run(List<String> command, Path workingDirectory, Map<String, String> environment,
                   Consumer<String> output) {
        if (command == null || command.isEmpty()) {
            return -1;
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }

        Process process;
        try {
            process = builder.start();
        } catch (Exception e) {
            emit(output, "Nao foi possivel executar: " + String.join(" ", command));
            emit(output, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return -1;
        }
        current.set(process);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                emit(output, line);
            }
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process);
            return -1;
        } catch (Exception e) {
            log.debug("Falha ao ler a saida do build: {}", e.getMessage());
            return -1;
        } finally {
            current.compareAndSet(process, null);
        }
    }

    public void cancel() {
        Process process = current.getAndSet(null);
        if (process == null || !process.isAlive()) {
            return;
        }
        terminate(process);
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        process.destroy();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroy();
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }

    public boolean isRunning() {
        Process process = current.get();
        return process != null && process.isAlive();
    }

    private static void emit(Consumer<String> output, String line) {
        if (output != null) {
            try {
                output.accept(line);
            } catch (Exception e) {
                log.debug("Consumidor da saida do build falhou: {}", e.getMessage());
            }
        }
    }
}
