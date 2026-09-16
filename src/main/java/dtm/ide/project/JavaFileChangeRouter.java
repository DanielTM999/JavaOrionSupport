package dtm.ide.project;

import dtm.ide.spring.config.SpringConfigSupport;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Slf4j
public final class JavaFileChangeRouter {

    private static final long DEBOUNCE_MS = 250;
    private static final int MAX_SCAN_DEPTH = 24;
    private static final long MAX_SCAN_FILES = 2_000;

    private static final Set<String> IGNORED_SEGMENTS = Set.of(
            "target", "build", "out", "bin", ".git", ".gradle", ".mvn", ".idea",
            ".orion", ".settings", "node_modules");

    public enum Change {
        CREATED,
        MODIFIED,
        DELETED
    }

    public enum FileRole {
        JAVA,
        SPRING_CONFIG,
        BUILD
    }

    public interface Listener {
        void onProjectFileChanged(Path file, FileRole role, Change change, boolean editorManaged);
    }

    private final Listener listener;
    private final Predicate<Path> editorManaged;
    private final ScheduledExecutorService scheduler;
    private final Map<Path, Pending> pending = new ConcurrentHashMap<>();

    private boolean closed;
    private long generation;

    private record Pending(ScheduledFuture<?> task, boolean created, boolean directory,
                           long generation) {
    }

    public JavaFileChangeRouter(Listener listener, Predicate<Path> editorManaged) {
        this.listener = listener;
        this.editorManaged = editorManaged == null ? path -> false : editorManaged;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "java-file-change-router");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void accept(Path changedPath, WatchEvent.Kind<?> kind) {
        if (changedPath == null || kind == null) {
            return;
        }
        Path file = changedPath.toAbsolutePath().normalize();
        if (isIgnored(file)) {
            return;
        }
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            schedule(file, false, true);
            return;
        }
        boolean created = kind == StandardWatchEventKinds.ENTRY_CREATE;
        if (created && Files.isDirectory(file)) {
            schedule(file, true, true);
            return;
        }
        if (roleOf(file) == null) {
            return;
        }
        schedule(file, created, false);
    }

    public void acceptCreated(Path createdPath) {
        if (createdPath == null) {
            return;
        }
        Path file = createdPath.toAbsolutePath().normalize();
        if (roleOf(file) == null) {
            return;
        }
        schedule(file, true, false);
    }

    public void acceptDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        Path root = directory.toAbsolutePath().normalize();
        if (isIgnored(root)) {
            return;
        }
        schedule(root, false, true);
    }

    private synchronized void schedule(Path file, boolean created, boolean directory) {
        if (closed) return;
        pending.compute(file, (path, previous) -> {
            boolean wasCreated = created || (previous != null && previous.created());
            if (previous != null) {
                previous.task().cancel(false);
            }
            long ticket = ++generation;
            ScheduledFuture<?> task = scheduler.schedule(() -> dispatch(path, ticket),
                    DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            return new Pending(task, wasCreated, directory, ticket);
        });
    }

    private synchronized Pending claim(Path file, long ticket) {
        Pending current = pending.get(file);
        if (closed || current == null || current.generation() != ticket) return null;
        pending.remove(file, current);
        return current;
    }

    void dispatch(Path file, long ticket) {
        Pending removed = claim(file, ticket);
        if (removed == null) return;
        if (removed.directory()) {
            scanDirectory(file, removed.created());
            return;
        }
        FileRole role = roleOf(file);
        if (role == null) {
            return;
        }
        Change change;
        if (!Files.exists(file)) {
            change = Change.DELETED;
        } else if (removed.created()) {
            change = Change.CREATED;
        } else {
            change = Change.MODIFIED;
        }
        try {
            listener.onProjectFileChanged(file, role, change, editorManaged.test(file));
        } catch (Exception e) {
            log.warn("Falha ao tratar a mudanca de {}", file, e);
        }
    }

    private void scanDirectory(Path directory, boolean created) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> found;
        try (Stream<Path> walk = Files.walk(directory, MAX_SCAN_DEPTH)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(path))
                    .filter(path -> roleOf(path) != null)
                    .limit(MAX_SCAN_FILES)
                    .toList();
        } catch (Exception e) {
            log.debug("Falha ao varrer {}: {}", directory, e.getMessage());
            return;
        }
        found.forEach(path -> schedule(path, created, false));
    }

    public synchronized void shutdown() {
        closed = true;
        pending.values().forEach(entry -> entry.task().cancel(false));
        pending.clear();
        scheduler.shutdownNow();
    }

    static boolean isIgnored(Path file) {
        for (Path segment : file) {
            if (IGNORED_SEGMENTS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    static FileRole roleOf(Path file) {
        if (Files.isDirectory(file)) {
            return null;
        }
        if (JavaProjectConventions.isJava(file)) {
            return FileRole.JAVA;
        }
        if (SpringConfigSupport.isConfigFile(file)) {
            return FileRole.SPRING_CONFIG;
        }
        if (JavaProjectConventions.isMavenPom(file)
                || JavaProjectConventions.isGradleBuildFile(file)) {
            return FileRole.BUILD;
        }
        return null;
    }
}
