package dtm.ide.lsp;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
final class JdtLsWorkspaceLease implements AutoCloseable {

    static final String LOCK_FILE = ".orion-jdtls.lock";
    static final String PID_FILE = ".orion-jdtls.pid";
    static final int MAX_WORKSPACES = 8;
    private static final long SCAN_TIMEOUT_MS = 10_000;
    private static final Set<Path> HELD_IN_PROCESS = ConcurrentHashMap.newKeySet();

    private final Path workspace;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile boolean released;

    private JdtLsWorkspaceLease(Path workspace, FileChannel channel, FileLock lock) {
        this.workspace = workspace;
        this.channel = channel;
        this.lock = lock;
    }

    static JdtLsWorkspaceLease acquire(Path base) throws IOException {
        if (base == null) {
            throw new IOException("Workspace do JDT LS indisponivel");
        }
        for (int index = 1; index <= MAX_WORKSPACES; index++) {
            JdtLsWorkspaceLease lease = tryAcquire(candidate(base, index));
            if (lease != null) {
                return lease;
            }
        }
        throw new IOException("Todos os workspaces do JDT LS deste projeto estao em uso");
    }

    static Path candidate(Path base, int index) {
        Path normalized = base.toAbsolutePath().normalize();
        return index <= 1 ? normalized
                : normalized.resolveSibling(normalized.getFileName() + "-w" + index);
    }

    static JdtLsWorkspaceLease tryAcquire(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        if (!HELD_IN_PROCESS.add(normalized)) {
            return null;
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(normalized);
            channel = FileChannel.open(normalized.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return new JdtLsWorkspaceLease(normalized, channel, lock);
            }
        } catch (IOException | OverlappingFileLockException e) {
            log.debug("Workspace do JDT LS ocupado {}: {}", normalized, e.getMessage());
        }
        closeQuietly(channel);
        HELD_IN_PROCESS.remove(normalized);
        return null;
    }

    Path workspace() {
        return workspace;
    }

    void recordServer(ProcessHandle server) {
        if (server == null || released) {
            return;
        }
        long started = server.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        try {
            Files.writeString(workspace.resolve(PID_FILE), server.pid() + " " + started,
                    StandardCharsets.US_ASCII);
        } catch (IOException e) {
            log.debug("Falha ao registrar o pid do JDT LS em {}: {}", workspace, e.getMessage());
        }
    }

    Optional<ProcessHandle> recordedServer() {
        return recordedServer(workspace);
    }

    static Optional<ProcessHandle> recordedServer(Path workspace) {
        Path file = workspace.resolve(PID_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String[] parts = Files.readString(file, StandardCharsets.US_ASCII).trim().split("\\s+");
            long pid = Long.parseLong(parts[0]);
            long started = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            if (pid == ProcessHandle.current().pid()) {
                return Optional.empty();
            }
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(handle -> started == 0L || handle.info().startInstant()
                            .map(Instant::toEpochMilli)
                            .map(actual -> Math.abs(actual - started) < 1_000)
                            .orElse(false));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    boolean metadataInUse() {
        Path metadataLock = workspace.resolve(".metadata").resolve(".lock");
        if (!Files.isRegularFile(metadataLock)) {
            return false;
        }
        try (FileChannel probe = FileChannel.open(metadataLock, StandardOpenOption.WRITE)) {
            FileLock acquired = probe.tryLock();
            if (acquired == null) {
                return true;
            }
            acquired.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    List<ProcessHandle> serversHoldingMetadata() {
        if (!metadataInUse()) {
            return List.of();
        }
        List<ProcessHandle> found = new ArrayList<>();
        recordedServer().ifPresent(found::add);
        if (isWindows()) {
            for (Long pid : windowsServersFor(workspace)) {
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                        .filter(handle -> found.stream().noneMatch(known -> known.pid() == pid))
                        .ifPresent(found::add);
            }
        }
        return found;
    }

    static List<Long> windowsServersFor(Path workspace) {
        String script = "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\""
                + " | ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<Long> pids = new ArrayList<>();
        Process scan = null;
        try {
            scan = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            scan.getOutputStream().close();
            String output = new String(scan.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            scan.waitFor(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (String line : output.split("\\R")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                try {
                    long pid = Long.parseLong(line.substring(0, tab).trim());
                    if (pid != ProcessHandle.current().pid()
                            && commandLineUsesWorkspace(line.substring(tab + 1), workspace)) {
                        pids.add(pid);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            log.debug("Falha ao listar processos do JDT LS: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (scan != null && scan.isAlive()) {
                scan.destroyForcibly();
            }
        }
        return pids;
    }

    static boolean commandLineUsesWorkspace(String commandLine, Path workspace) {
        if (commandLine == null || workspace == null) {
            return false;
        }
        List<String> tokens = tokenize(commandLine);
        if (tokens.stream().noneMatch(token -> token.toLowerCase(Locale.ROOT)
                .contains("org.eclipse.equinox.launcher"))) {
            return false;
        }
        String expected = workspace.toAbsolutePath().normalize().toString();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if ("-data".equals(tokens.get(i))) {
                try {
                    String candidate = Path.of(tokens.get(i + 1)).toAbsolutePath().normalize().toString();
                    if (expected.equalsIgnoreCase(candidate)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    if (expected.equalsIgnoreCase(tokens.get(i + 1))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean inToken = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                inToken = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        released = true;
        if (recordedServer().isEmpty()) {
            try {
                Files.deleteIfExists(workspace.resolve(PID_FILE));
            } catch (IOException ignored) {
            }
        }
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        closeQuietly(channel);
        HELD_IN_PROCESS.remove(workspace);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
