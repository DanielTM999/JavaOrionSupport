package dtm.ide.lsp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class JdtLsStartupLog {
    private static final int MAX_READ_BYTES = 256 * 1024;

    enum Phase {
        BOOTING("iniciando a JVM do JDT LS"),
        STARTING_PLUGINS("JDT LS carregando os plugins"),
        IMPORTING("JDT LS lendo o projeto (Maven/Gradle)");

        private final String description;

        Phase(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    private final Path file;
    private long offset;
    private boolean recovering;
    private Phase phase = Phase.BOOTING;

    JdtLsStartupLog(Path file) {
        this.file = file;
        this.offset = size(file);
    }

    synchronized String describe() {
        Phase current = poll();
        return recovering
                ? current.description() + " (recuperando o workspace, que foi fechado sem salvar)"
                : current.description();
    }

    synchronized Phase poll() {
        String appended = readAppended();
        if (appended.contains("exited with unsaved changes")) {
            recovering = true;
        }
        if (appended.contains(">> initialize")) {
            phase = Phase.IMPORTING;
        } else if (appended.contains("JavaLanguageServerPlugin is started")
                && phase.ordinal() < Phase.STARTING_PLUGINS.ordinal()) {
            phase = Phase.STARTING_PLUGINS;
        }
        return phase;
    }

    private String readAppended() {
        long current = size(file);
        if (current < offset) {
            offset = 0;
        }
        if (current == offset) {
            return "";
        }
        try (RandomAccessFile log = new RandomAccessFile(file.toFile(), "r")) {
            long start = Math.max(offset, current - MAX_READ_BYTES);
            byte[] bytes = new byte[(int) (current - start)];
            log.seek(start);
            log.readFully(bytes);
            offset = current;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    private static long size(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException error) {
            return 0;
        }
    }
}
