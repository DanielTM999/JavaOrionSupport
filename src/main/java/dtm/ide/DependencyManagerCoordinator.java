package dtm.ide;

import dtm.ide.build.BuildSystem;
import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencyHealthService;
import dtm.ide.deps.DependencyHealthSnapshot;
import dtm.ide.deps.DependencyInventoryService;
import dtm.ide.deps.DependencyInventorySnapshot;
import dtm.ide.deps.DependencyService;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencySearchMerger;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.GradleRepositorySupport;
import dtm.ide.deps.ManagedDependency;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.deps.MavenLocalRepositoryCatalog;
import dtm.ide.deps.MavenLocalRepositoryResolver;
import dtm.ide.deps.MavenVersionOrder;
import dtm.ide.deps.OsvClient;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.ui.DependencyManagerPanel;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class DependencyManagerCoordinator implements DependencyManagerPanel.Host, AutoCloseable {

    private static final Duration REMOTE_SEARCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final int MIN_REMOTE_QUERY_LENGTH = 3;
    private static final int MAX_PARALLEL_VERSION_LOOKUPS = 6;

    private final PluginTaskExecutor tasks;
    private final MavenCentralClient central;
    private final OsvClient osv;
    private final Supplier<JavaProjectDescriptor> descriptor;
    private final Supplier<BuildSystem> buildSystem;
    private final Supplier<DependencyService> dependencies;
    private final LongSupplier lifecycle;
    private final Supplier<Path> projectRoot;
    private final BiPredicate<Long, Path> isCurrent;
    private final Consumer<Boolean> afterChange;
    private final MavenLocalRepositoryResolver localResolver = new MavenLocalRepositoryResolver();
    private final MavenLocalRepositoryCatalog localCatalog = new MavenLocalRepositoryCatalog();
    private final Map<String, CachedRemoteSearch> remoteSearchCache = new ConcurrentHashMap<>();
    private volatile Path configuredProject;
    private volatile Runnable localChangeListener = () -> { };
    private volatile boolean localOnly;

    DependencyManagerCoordinator(PluginTaskExecutor tasks, MavenCentralClient central,
                                 OsvClient osv,
                                 Supplier<JavaProjectDescriptor> descriptor,
                                 Supplier<BuildSystem> buildSystem,
                                 Supplier<DependencyService> dependencies,
                                 LongSupplier lifecycle, Supplier<Path> projectRoot,
                                 BiPredicate<Long, Path> isCurrent,
                                 Consumer<Boolean> afterChange) {
        this.tasks = tasks;
        this.central = central;
        this.osv = osv;
        this.descriptor = descriptor;
        this.buildSystem = buildSystem;
        this.dependencies = dependencies;
        this.lifecycle = lifecycle;
        this.projectRoot = projectRoot;
        this.isCurrent = isCurrent;
        this.afterChange = afterChange;
    }

    @Override
    public List<JavaModule> modules() {
        JavaProjectDescriptor current = descriptor.get();
        if (current == null) {
            return List.of();
        }
        List<JavaModule> buildable = current.buildableModules();
        if (!buildable.isEmpty()) {
            return buildable;
        }
        JavaModule root = current.rootModule();
        return root == null ? List.of() : List.of(root);
    }

    @Override
    public List<DependencyCoordinate> declaredDependencies(JavaModule module) {
        DependencyService service = dependencies.get();
        return service == null ? List.of() : service.declaredDependencies(module);
    }

    @Override
    public void search(String query, boolean includePreReleases,
                       Consumer<DependencyManagerPanel.SearchOutcome> onResult) {
        tasks.submit(() -> {
            ensureLocalCatalog(false);
            List<MavenLocalRepositoryCatalog.LocalArtifact> local =
                    localCatalog.search(query, includePreReleases);
            DependencyManagerPanel.RemoteStatus status;
            List<MavenCentralClient.SearchResult> remote;
            if (skipRemote(query)) {
                status = DependencyManagerPanel.RemoteStatus.SKIPPED;
                remote = List.of();
            } else {
                Optional<List<MavenCentralClient.SearchResult>> fetched = remoteSearch(query);
                status = fetched.isEmpty()
                        ? DependencyManagerPanel.RemoteStatus.FAILED
                        : DependencyManagerPanel.RemoteStatus.OK;
                remote = fetched.orElseGet(List::of);
            }
            List<DependencySearchResult> merged = DependencySearchMerger.merge(query, local,
                    remote, includePreReleases);
            onResult.accept(new DependencyManagerPanel.SearchOutcome(merged, status,
                    !localCatalog.available()));
        });
    }

    @Override
    public void versions(DependencyCoordinate coordinate, boolean includePreReleases,
                         Consumer<List<DependencyVersionChoice>> onResult) {
        tasks.submit(() -> {
            ensureLocalCatalog(false);
            List<String> local = localCatalog.versions(coordinate.key(), includePreReleases);
            List<String> remote = localOnly ? List.of()
                    : central.versions(coordinate.groupId(), coordinate.artifactId())
                    .stream().filter(version -> includePreReleases
                            || MavenCentralClient.isStable(version)).toList();
            onResult.accept(DependencySearchMerger.mergeVersions(local, remote));
        });
    }

    @Override
    public boolean canInstallLocal(JavaModule module) {
        JavaProjectDescriptor current = descriptor.get();
        return GradleRepositorySupport.hasMavenLocal(current, module);
    }

    @Override
    public void onLocalRepositoryChanged(Runnable listener) {
        localChangeListener = listener == null ? () -> { } : listener;
    }

    @Override
    public void latestVersions(List<DependencyCoordinate> coordinates,
                               Consumer<Map<String, String>> onResult) {
        List<DependencyCoordinate> requested = coordinates == null
                ? List.of() : List.copyOf(coordinates);
        if (localOnly || requested.isEmpty()) {
            onResult.accept(Map.of());
            return;
        }
        tasks.submit(() -> onResult.accept(resolveLatestVersions(requested)));
    }

    private Map<String, String> resolveLatestVersions(List<DependencyCoordinate> requested) {
        Semaphore permits = new Semaphore(MAX_PARALLEL_VERSION_LOOKUPS);
        Map<String, String> latest = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> lookups = new ArrayList<>(requested.size());
        for (DependencyCoordinate coordinate : requested) {
            lookups.add(CompletableFuture.runAsync(() -> {
                if (central.isCoolingDown()) {
                    return;
                }
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (central.isCoolingDown()) {
                        return;
                    }
                    String version = central.latestStableVersion(
                            coordinate.groupId(), coordinate.artifactId());
                    if (!version.isBlank() && (!coordinate.hasVersion()
                            || MavenVersionOrder.compare(version, coordinate.version()) > 0)) {
                        latest.put(coordinate.key(), version);
                    }
                } finally {
                    permits.release();
                }
            }, tasks));
        }
        CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).join();
        Map<String, String> ordered = new LinkedHashMap<>();
        for (DependencyCoordinate coordinate : requested) {
            String version = latest.get(coordinate.key());
            if (version != null) {
                ordered.put(coordinate.key(), version);
            }
        }
        return ordered;
    }

    @Override
    public void inventory(JavaModule module, List<DependencyCoordinate> declared,
                          Consumer<DependencyInventorySnapshot> onResult) {
        JavaProjectDescriptor current = descriptor.get();
        BuildSystem build = buildSystem.get();
        List<DependencyCoordinate> requested = declared == null
                ? List.of() : List.copyOf(declared);
        if (current == null) {
            onResult.accept(new DependencyInventorySnapshot(List.of(), List.of(), true));
            return;
        }
        long ticket = lifecycle.getAsLong();
        Path root = projectRoot.get();
        tasks.submit(() -> {
            DependencyInventorySnapshot snapshot =
                    new DependencyInventoryService(current, build).resolve(module, requested);
            if (isCurrent.test(ticket, root)) {
                onResult.accept(snapshot);
            }
        });
    }

    @Override
    public void health(JavaModule module, DependencyInventorySnapshot inventory,
                       Consumer<DependencyHealthSnapshot> onResult) {
        JavaProjectDescriptor current = descriptor.get();
        BuildSystem build = buildSystem.get();
        if (current == null || build == null) {
            onResult.accept(new DependencyHealthSnapshot(List.of(), Map.of(), true, true));
            return;
        }
        long ticket = lifecycle.getAsLong();
        Path root = projectRoot.get();
        tasks.submit(() -> {
            DependencyHealthSnapshot snapshot = new DependencyHealthService(current, build, osv)
                    .analyze(inventory);
            if (isCurrent.test(ticket, root)) {
                onResult.accept(snapshot);
            }
        });
    }

    @Override
    public void add(JavaModule module, DependencyCoordinate coordinate,
                    Consumer<Boolean> onDone) {
        mutate(service -> service.add(module, coordinate), onDone);
    }

    @Override
    public void remove(JavaModule module, DependencyCoordinate coordinate,
                       Consumer<Boolean> onDone) {
        mutate(service -> service.remove(module, coordinate), onDone);
    }

    @Override
    public void updateVersion(JavaModule module, ManagedDependency dependency, String version,
                              Consumer<Boolean> onDone) {
        mutate(service -> service.updateVersion(module, dependency, version), onDone);
    }

    private void mutate(java.util.function.Predicate<DependencyService> mutation,
                        Consumer<Boolean> onDone) {
        tasks.submit(() -> {
            DependencyService service = dependencies.get();
            boolean changed = service != null && mutation.test(service);
            afterChange.accept(changed);
            onDone.accept(changed);
        });
    }

    void setLocalOnly(boolean value) {
        if (localOnly != value) {
            localOnly = value;
            remoteSearchCache.clear();
        }
    }

    synchronized void resetLocalRepository() {
        configuredProject = null;
        localCatalog.reset();
    }

    @Override
    public synchronized void close() {
        localCatalog.close();
        remoteSearchCache.clear();
        localChangeListener = () -> { };
    }

    private synchronized void ensureLocalCatalog(boolean force) {
        JavaProjectDescriptor current = descriptor.get();
        if (current == null) {
            resetLocalRepository();
            return;
        }
        if (!force && current.root().equals(configuredProject)) {
            return;
        }
        MavenLocalRepositoryResolver.Resolution resolution =
                localResolver.resolve(current, buildSystem.get());
        configuredProject = current.root();
        localCatalog.configure(resolution.repository(), resolution.configurationFiles(), change -> {
            if (change == MavenLocalRepositoryCatalog.Change.CONFIGURATION) {
                tasks.submit(() -> {
                    localCatalog.reset();
                    configuredProject = null;
                    ensureLocalCatalog(true);
                    localChangeListener.run();
                });
            } else {
                localChangeListener.run();
            }
        });
    }

    private boolean skipRemote(String query) {
        if (localOnly || central.isCoolingDown()) {
            return true;
        }
        String trimmed = query == null ? "" : query.trim();
        return !trimmed.contains(":") && trimmed.length() < MIN_REMOTE_QUERY_LENGTH;
    }

    private Optional<List<MavenCentralClient.SearchResult>> remoteSearch(String query) {
        String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        CachedRemoteSearch cached = remoteSearchCache.get(key);
        if (cached != null && cached.created().plus(REMOTE_SEARCH_CACHE_TTL).isAfter(Instant.now())) {
            return Optional.of(cached.results());
        }
        Optional<List<MavenCentralClient.SearchResult>> result = central.trySearch(query);
        result.ifPresent(items -> remoteSearchCache.put(key,
                new CachedRemoteSearch(List.copyOf(items), Instant.now())));
        return result;
    }

    private record CachedRemoteSearch(List<MavenCentralClient.SearchResult> results,
                                      Instant created) {
    }

}
