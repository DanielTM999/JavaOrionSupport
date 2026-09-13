package dtm.ide.project;

import dtm.ide.spring.config.SpringConfigSupport;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Slf4j
public final class JavaFileChangeRouter {

    private static final long DEBOUNCE_MS = 250;

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
        void onProjectFileChanged(Path file, FileRole role, Change change);
    }

    private final Listener listener;
    private final Predicate<Path> editorManaged;
    private final ScheduledExecutorService scheduler;
    private final Map<Path, Pending> pending = new ConcurrentHashMap<>();

    private boolean closed;
    private long generation;

    private record Pending(ScheduledFuture<?> task, boolean created, long generation) {
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
        if (isIgnored(file) || roleOf(file) == null) {
            return;
        }
        boolean created = kind == StandardWatchEventKinds.ENTRY_CREATE;
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY && editorManaged.test(file)) {
            return;
        }
        schedule(file, created);
    }

    public void acceptCreated(Path createdPath) {
        if (createdPath == null) {
            return;
        }
        Path file = createdPath.toAbsolutePath().normalize();
        if (roleOf(file) == null) {
            return;
        }
        schedule(file, true);
    }

    private synchronized void schedule(Path file, boolean created) {
        if (closed) return;
        pending.compute(file, (path, previous) -> {
            boolean wasCreated = created || (previous != null && previous.created());
            if (previous != null) {
                previous.task().cancel(false);
            }
            long ticket = ++generation;
            ScheduledFuture<?> task = scheduler.schedule(() -> dispatch(path, ticket),
                    DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            return new Pending(task, wasCreated, ticket);
        });
    }

    synchronized void dispatch(Path file, long ticket) {
        Pending removed = pending.get(file);
        if (closed || removed == null || removed.generation() != ticket) return;
        pending.remove(file, removed);
        FileRole role = roleOf(file);
        if (role == null) {
            return;
        }
        Change change;
        if (!Files.exists(file)) {
            change = Change.DELETED;
        } else if (removed != null && removed.created()) {
            change = Change.CREATED;
        } else {
            change = Change.MODIFIED;
        }
        try {
            listener.onProjectFileChanged(file, role, change);
        } catch (Exception e) {
            log.warn("Falha ao tratar a mudanca de {}", file, e);
        }
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
