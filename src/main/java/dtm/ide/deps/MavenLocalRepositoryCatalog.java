package dtm.ide.deps;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class MavenLocalRepositoryCatalog implements AutoCloseable {

    public enum Change { CONTENT, CONFIGURATION }

    public record LocalArtifact(DependencyCoordinate coordinate, List<String> versions,
                                long lastUpdated, Path repository) {
        public LocalArtifact {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    private static final int SEARCH_LIMIT = 30;
    private static final long DEBOUNCE_MS = 900;

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "maven-local-repository-refresh");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private final Set<Path> registeredDirectories = new HashSet<>();
    private final Set<Path> pendingArtifacts = new LinkedHashSet<>();

    private volatile Map<String, LocalArtifact> artifacts = Map.of();
    private volatile Path repository;
    private volatile Set<Path> configurationFiles = Set.of();
    private volatile java.util.function.Consumer<Change> listener = ignored -> { };
    private WatchService watcher;
    private Thread watcherThread;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> configurationTask;
    private boolean fullRefresh;
    private long generation;

    public void configure(Path newRepository, List<Path> configs,
                          java.util.function.Consumer<Change> onChange) {
        Path normalized = normalize(newRepository);
        Set<Path> normalizedConfigs = new LinkedHashSet<>();
        if (configs != null) {
            configs.stream().map(MavenLocalRepositoryCatalog::normalize)
                    .filter(java.util.Objects::nonNull).forEach(normalizedConfigs::add);
        }
        synchronized (lock) {
            listener = onChange == null ? ignored -> { } : onChange;
            if (java.util.Objects.equals(repository, normalized)
                    && configurationFiles.equals(normalizedConfigs) && watcher != null) {
                return;
            }
            stopWatcherLocked();
            repository = normalized;
            configurationFiles = Set.copyOf(normalizedConfigs);
            artifacts = scan(normalized);
            try {
                watcher = java.nio.file.FileSystems.getDefault().newWatchService();
                long currentGeneration = ++generation;
                registerRepositoryTreeLocked();
                registerConfigurationParentsLocked();
                watcherThread = Thread.ofPlatform().daemon().name("maven-local-repository-watch")
                        .start(() -> watchLoop(currentGeneration));
            } catch (Exception e) {
                log.debug("Observador do repositorio Maven local indisponivel: {}", e.getMessage());
                stopWatcherLocked();
            }
        }
    }

    public Path repository() {
        return repository;
    }

    public boolean available() {
        Path current = repository;
        return current != null && Files.isDirectory(current);
    }

    public List<LocalArtifact> search(String query, boolean includePreReleases) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return artifacts.values().stream()
                .map(artifact -> filtered(artifact, includePreReleases))
                .filter(artifact -> artifact != null && matches(artifact.coordinate(), needle))
                .sorted(Comparator.comparingInt(
                                (LocalArtifact artifact) -> relevance(artifact.coordinate(), needle))
                        .thenComparing(artifact -> artifact.coordinate().key()))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    public List<String> versions(String key, boolean includePreReleases) {
        LocalArtifact artifact = key == null ? null : artifacts.get(key);
        if (artifact == null) {
            return List.of();
        }
        return artifact.versions().stream()
                .filter(version -> includePreReleases || MavenCentralClient.isStable(version))
                .sorted(MavenVersionOrder.DESCENDING).toList();
    }

    public void reset() {
        synchronized (lock) {
            stopWatcherLocked();
            repository = null;
            configurationFiles = Set.of();
            artifacts = Map.of();
            listener = ignored -> { };
        }
    }

    @Override
    public void close() {
        reset();
        scheduler.shutdownNow();
    }

    private LocalArtifact filtered(LocalArtifact artifact, boolean includePreReleases) {
        List<String> versions = artifact.versions().stream()
                .filter(version -> includePreReleases || MavenCentralClient.isStable(version))
                .sorted(MavenVersionOrder.DESCENDING).toList();
        if (versions.isEmpty()) {
            return null;
        }
        DependencyCoordinate coordinate = artifact.coordinate().withVersion(versions.getFirst());
        return new LocalArtifact(coordinate, versions, artifact.lastUpdated(), artifact.repository());
    }

    private void watchLoop(long expectedGeneration) {
        while (!Thread.currentThread().isInterrupted()) {
            WatchService current;
            synchronized (lock) {
                if (expectedGeneration != generation || watcher == null) {
                    return;
                }
                current = watcher;
            }
            try {
                WatchKey key = current.take();
                processEvents(key, expectedGeneration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (expectedGeneration == generation) {
                    log.debug("Falha no observador do repositorio Maven: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void processEvents(WatchKey key, long expectedGeneration) {
        Path directory;
        synchronized (lock) {
            if (expectedGeneration != generation) {
                return;
            }
            directory = watchedDirectories.get(key);
        }
        if (directory == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                scheduleFullRefresh();
                continue;
            }
            if (!(event.context() instanceof Path relative)) {
                continue;
            }
            Path changed = normalize(directory.resolve(relative));
            if (configurationFiles.contains(changed)
                    || configurationFiles.stream().anyMatch(config -> config.startsWith(changed))) {
                scheduleConfigurationChange();
                continue;
            }
            Path currentRepository = repository;
            if (currentRepository == null) {
                continue;
            }
            if (changed.equals(currentRepository)) {
                scheduleFullRefresh();
            } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(changed)) {
                synchronized (lock) {
                    registerTreeLocked(changed);
                }
                scheduleFullRefresh();
            } else if (isArtifactFile(changed)) {
                Path artifact = artifactDirectory(currentRepository, changed);
                if (artifact == null) {
                    scheduleFullRefresh();
                } else {
                    scheduleArtifactRefresh(artifact);
                }
            }
        }
        if (!key.reset()) {
            synchronized (lock) {
                Path removed = watchedDirectories.remove(key);
                registeredDirectories.remove(removed);
            }
            scheduleFullRefresh();
        }
    }

    private void scheduleArtifactRefresh(Path artifact) {
        synchronized (lock) {
            pendingArtifacts.add(artifact);
            scheduleRefreshLocked();
        }
    }

    private void scheduleFullRefresh() {
        synchronized (lock) {
            fullRefresh = true;
            pendingArtifacts.clear();
            scheduleRefreshLocked();
        }
    }

    private void scheduleRefreshLocked() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        refreshTask = scheduler.schedule(this::refreshPending, DEBOUNCE_MS,
                TimeUnit.MILLISECONDS);
    }

    private void scheduleConfigurationChange() {
        synchronized (lock) {
            if (configurationTask != null) {
                configurationTask.cancel(false);
            }
            configurationTask = scheduler.schedule(
                    () -> listener.accept(Change.CONFIGURATION), DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void refreshPending() {
        boolean rebuild;
        Set<Path> changed;
        synchronized (lock) {
            rebuild = fullRefresh;
            fullRefresh = false;
            changed = Set.copyOf(pendingArtifacts);
            pendingArtifacts.clear();
        }
        if (rebuild) {
            Path current = repository;
            artifacts = scan(current);
            synchronized (lock) {
                registerRepositoryTreeLocked();
            }
        } else if (!changed.isEmpty()) {
            Map<String, LocalArtifact> updated = new LinkedHashMap<>(artifacts);
            for (Path artifactDirectory : changed) {
                String key = keyOf(repository, artifactDirectory);
                if (key == null) {
                    continue;
                }
                LocalArtifact artifact = scanArtifact(repository, artifactDirectory);
                if (artifact == null) {
                    updated.remove(key);
                } else {
                    updated.put(key, artifact);
                }
            }
            artifacts = Map.copyOf(updated);
        }
        listener.accept(Change.CONTENT);
    }

    private void registerRepositoryTreeLocked() {
        Path current = repository;
        if (current != null && Files.isDirectory(current)) {
            registerTreeLocked(current);
        } else if (current != null && current.getParent() != null) {
            registerDirectoryLocked(current.getParent());
        }
    }

    private void registerConfigurationParentsLocked() {
        for (Path configuration : configurationFiles) {
            Path directory = configuration.getParent();
            while (directory != null && !Files.isDirectory(directory)) {
                directory = directory.getParent();
            }
            registerDirectoryLocked(directory);
        }
    }

    private void registerTreeLocked(Path root) {
        if (watcher == null || root == null || !Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    registerDirectoryLocked(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.debug("Falha ao registrar diretorios Maven em {}: {}", root, e.getMessage());
        }
    }

    private void registerDirectoryLocked(Path directory) {
        Path normalized = normalize(directory);
        if (watcher == null || normalized == null || !Files.isDirectory(normalized)
                || !registeredDirectories.add(normalized)) {
            return;
        }
        try {
            WatchKey key = normalized.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            watchedDirectories.put(key, normalized);
        } catch (Exception e) {
            registeredDirectories.remove(normalized);
        }
    }

    private void stopWatcherLocked() {
        generation++;
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        if (configurationTask != null) {
            configurationTask.cancel(false);
            configurationTask = null;
        }
        fullRefresh = false;
        pendingArtifacts.clear();
        Thread thread = watcherThread;
        watcherThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        WatchService current = watcher;
        watcher = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        watchedDirectories.clear();
        registeredDirectories.clear();
    }

    private static Map<String, LocalArtifact> scan(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return Map.of();
        }
        Map<String, ArtifactBuilder> builders = new LinkedHashMap<>();
        try {
            Files.walkFileTree(repository, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isArtifactFile(file)) {
                        add(repository, file, attrs.lastModifiedTime().toMillis(), builders);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.debug("Falha ao indexar repositorio Maven local {}: {}", repository, e.getMessage());
        }
        Map<String, LocalArtifact> result = new LinkedHashMap<>();
        builders.values().forEach(builder -> result.put(builder.key(), builder.build(repository)));
        return Map.copyOf(result);
    }

    private static LocalArtifact scanArtifact(Path repository, Path artifactDirectory) {
        if (repository == null || artifactDirectory == null
                || !Files.isDirectory(artifactDirectory)) {
            return null;
        }
        Map<String, ArtifactBuilder> builders = new LinkedHashMap<>();
        try {
            Files.walkFileTree(artifactDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isArtifactFile(file)) {
                        add(repository, file, attrs.lastModifiedTime().toMillis(), builders);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            return null;
        }
        String key = keyOf(repository, artifactDirectory);
        ArtifactBuilder builder = key == null ? null : builders.get(key);
        return builder == null ? null : builder.build(repository);
    }

    private static void add(Path repository, Path file, long modified,
                            Map<String, ArtifactBuilder> builders) {
        Path relative;
        try {
            relative = repository.relativize(file);
        } catch (Exception e) {
            return;
        }
        if (relative.getNameCount() < 4) {
            return;
        }
        int artifactIndex = relative.getNameCount() - 3;
        String artifact = relative.getName(artifactIndex).toString();
        String version = relative.getName(artifactIndex + 1).toString();
        StringBuilder group = new StringBuilder();
        for (int index = 0; index < artifactIndex; index++) {
            if (!group.isEmpty()) {
                group.append('.');
            }
            group.append(relative.getName(index));
        }
        if (group.isEmpty() || artifact.isBlank() || version.isBlank()) {
            return;
        }
        String key = group + ":" + artifact;
        builders.computeIfAbsent(key, ignored -> new ArtifactBuilder(group.toString(), artifact))
                .add(version, modified);
    }

    private static Path artifactDirectory(Path repository, Path file) {
        if (repository == null || file == null) {
            return null;
        }
        try {
            Path relative = repository.relativize(file);
            return relative.getNameCount() < 4 ? null : file.getParent().getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static String keyOf(Path repository, Path artifactDirectory) {
        if (repository == null || artifactDirectory == null) {
            return null;
        }
        try {
            Path relative = repository.relativize(artifactDirectory);
            if (relative.getNameCount() < 2) {
                return null;
            }
            String artifact = relative.getFileName().toString();
            StringBuilder group = new StringBuilder();
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                if (!group.isEmpty()) {
                    group.append('.');
                }
                group.append(relative.getName(index));
            }
            return group + ":" + artifact;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isArtifactFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pom") || name.endsWith(".jar");
    }

    private static boolean matches(DependencyCoordinate coordinate, String needle) {
        String key = coordinate.key().toLowerCase(Locale.ROOT);
        if (needle.contains(":")) {
            return key.contains(needle);
        }
        for (String token : needle.split("\\s+")) {
            if (!key.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static int relevance(DependencyCoordinate coordinate, String needle) {
        String key = coordinate.key().toLowerCase(Locale.ROOT);
        String artifact = coordinate.artifactId().toLowerCase(Locale.ROOT);
        if (key.equals(needle)) return 0;
        if (artifact.equals(needle)) return 1;
        if (artifact.startsWith(needle)) return 2;
        if (key.startsWith(needle)) return 3;
        if (artifact.contains(needle)) return 4;
        return 5;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static final class ArtifactBuilder {
        private final String group;
        private final String artifact;
        private final Map<String, Long> versions = new HashMap<>();

        private ArtifactBuilder(String group, String artifact) {
            this.group = group;
            this.artifact = artifact;
        }

        private String key() {
            return group + ":" + artifact;
        }

        private void add(String version, long modified) {
            versions.merge(version, modified, Math::max);
        }

        private LocalArtifact build(Path repository) {
            List<String> sorted = new ArrayList<>(versions.keySet());
            sorted.sort(MavenVersionOrder.DESCENDING);
            long updated = versions.values().stream().mapToLong(Long::longValue).max().orElse(0);
            DependencyCoordinate coordinate = DependencyCoordinate.of(group, artifact,
                    sorted.isEmpty() ? "" : sorted.getFirst());
            return new LocalArtifact(coordinate, sorted, updated, repository);
        }
    }
}
