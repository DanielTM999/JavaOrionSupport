package dtm.ide.adapter;

import dtm.ide.build.BuildSystem;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.ui.JavaBuildToolsPanel;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class ProjectSyncSupport {

    private static final String SYNC_PROGRESS_ID = "javaProjectSync";
    private static final long SYNC_WORK_START_GRACE_MS = 3_000;
    private static final long SYNC_WORK_MAX_MS = 120_000;

    private final AdapterHost host;
    private final AtomicBoolean buildToolsSyncPending = new AtomicBoolean();
    private final AtomicLong syncGeneration = new AtomicLong();
    private final AtomicLong automaticSyncTicket = new AtomicLong();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private final AtomicInteger automaticSyncAttempts = new AtomicInteger();
    private final AtomicReference<SyncWork> syncWork = new AtomicReference<>();

    public ProjectSyncSupport(AdapterHost host) {
        this.host = host;
    }

    public void clearPending() {
        buildToolsSyncPending.set(false);
    }

    public void reset() {
        syncGeneration.incrementAndGet();
        syncRunning.set(false);
        automaticSyncTicket.incrementAndGet();
        syncWork.set(null);
        host.hideProgress(SYNC_PROGRESS_ID);
    }

    public void restartAutomaticSync() {
        automaticSyncAttempts.set(0);
        scheduleAutomaticSync();
    }

    public void observeSyncWork(boolean active) {
        SyncWork sync = syncWork.get();
        if (sync != null) {
            sync.observe(active);
        }
    }

    public void syncProject() {
        Path root = host.projectRoot();
        if (root == null) {
            return;
        }
        if (!syncRunning.compareAndSet(false, true)) {
            buildToolsSyncPending.set(true);
            return;
        }
        long ticket = host.lifecycleTicket();
        long generation = syncGeneration.incrementAndGet();
        buildToolsSyncPending.set(false);
        String label = text("status.syncing", "Java: sincronizando o projeto...");
        host.setStatusBarText(label);
        host.showProgress(SYNC_PROGRESS_ID, label);
        JavaBuildToolsPanel panel = host.buildToolsPanel();
        if (panel != null) {
            panel.setSyncing(true);
        }
        host.background().submit(() -> {
            boolean waiting = false;
            try {
                BuildSystem build = host.currentBuildSystem();
                if (build != null) {
                    build.invalidateClasspathCache();
                }
                JavaProjectDescriptor previous = host.descriptor();
                JavaProjectDescriptor reloaded = host.timed("describe(syncProject)",
                        () -> JavaProjectConventions.describe(root));
                if (!host.isCurrent(ticket, root)) {
                    return;
                }
                if (reloaded != null) {
                    host.descriptor(reloaded);
                }
                if (jdkRequirementChanged(previous, reloaded)) {
                    log.info("JDK pedida pelo projeto mudou de {} para {}; reavaliando a JDK do projeto",
                            previous.jdkMajor().orElse(null), reloaded.jdkMajor().orElse(null));
                    host.setStatusBarText(text("status.jdkRequirementChanged",
                            "Java: o projeto pede outra JDK - recarregando"));
                    host.clearCaches();
                    return;
                }
                JavaLanguageServer lsp = host.languageServer();
                host.languageServerManager().applyLombokAgent(lsp, host.descriptor());
                boolean agentChanged = lsp != null && LanguageServerManager.needsLombokAgentRestart(lsp);
                if (agentChanged || lsp == null) {
                    host.clearCaches();
                    return;
                }
                ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
                SyncWork work = new SyncWork();
                syncWork.set(work);
                if (model == null || !model.updateProjectConfiguration(root)) {
                    syncWork.compareAndSet(work, null);
                    host.clearCaches();
                    return;
                }
                waiting = true;
                work.completion().whenComplete((ignored, error) -> {
                    syncWork.compareAndSet(work, null);
                    boolean recovered = error == null;
                    try {
                        if (recovered && host.isCurrent(ticket, root) && syncGeneration.get() == generation) model.resynchronizeAfterProjectUpdate();
                    } catch (Exception failure) {
                        recovered = false;
                        log.warn("Falha ao sincronizar documentos apos atualizar o projeto", failure);
                    } finally {
                        finishSync(generation, ticket, root, recovered);
                    }
                });
            } finally {
                if (!waiting) {
                    finishSync(generation, ticket, root, false);
                }
            }
        });
    }

    public static boolean jdkRequirementChanged(JavaProjectDescriptor previous, JavaProjectDescriptor reloaded) {
        return previous != null && reloaded != null
                && !previous.jdkMajor().equals(reloaded.jdkMajor());
    }

    private void finishSync(long generation, long ticket, Path root, boolean synced) {
        if (syncGeneration.get() != generation) {
            return;
        }
        syncRunning.set(false);
        if (buildToolsSyncPending.getAndSet(false)) scheduleAutomaticSync();
        else if (!synced && host.isCurrent(ticket, root) && automaticSyncAttempts.incrementAndGet() <= 3) scheduleAutomaticSync();
        host.hideProgress(SYNC_PROGRESS_ID);
        JavaBuildToolsPanel panel = host.buildToolsPanel();
        if (panel != null) {
            panel.setSyncing(false);
        }
        if (!synced || !host.isCurrent(ticket, root)) {
            return;
        }
        host.requestProjectTreeViewRefresh();
        SwingUtilities.invokeLater(() -> {
            if (!host.isCurrent(ticket, root)) {
                return;
            }
            refreshBuildToolsPanel();
            host.setStatusBarText(text("status.synced", "Java: projeto sincronizado"));
        });
    }

    private static final class SyncWork {
        private final CompletableFuture<Boolean> started = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        void observe(boolean active) {
            if (active) {
                started.complete(true);
            } else if (started.isDone()) {
                finished.complete(null);
            }
        }

        CompletableFuture<Void> completion() {
            return started.completeOnTimeout(false, SYNC_WORK_START_GRACE_MS, TimeUnit.MILLISECONDS)
                    .thenCompose(active -> active
                            ? finished.completeOnTimeout(null, SYNC_WORK_MAX_MS, TimeUnit.MILLISECONDS)
                            : CompletableFuture.completedFuture(null));
        }
    }

    private void refreshBuildToolsPanel() {
        JavaBuildToolsPanel panel = host.buildToolsPanel();
        if (panel != null) {
            panel.reload();
        }
    }

    public void onBuildFileChanged(Path filePath) {
        buildToolsSyncPending.set(true);
        automaticSyncAttempts.set(0);
        JavaBuildToolsPanel panel = host.buildToolsPanel();
        if (panel != null) panel.setSyncPending(true);
        scheduleAutomaticSync();
    }

    private void scheduleAutomaticSync() {
        long ticket = automaticSyncTicket.incrementAndGet();
        long session = host.lifecycleTicket();
        host.background().schedule(() -> {
            Path root = host.projectRoot();
            if (root == null || session != host.lifecycleTicket() || ticket != automaticSyncTicket.get()) return;
            if (host.descriptor() != null && host.descriptor().isMaven()) {
                if (!validMavenReactor(root.resolve("pom.xml"), new java.util.HashSet<>())) {
                    host.setStatusBarText("Java: corrija o POM; a sincronizacao sera retomada automaticamente");
                    return;
                }
            }
            SwingUtilities.invokeLater(this::syncProject);
        }, 1200, TimeUnit.MILLISECONDS);
    }

    public static boolean validMavenReactor(Path file, Set<Path> visited) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!visited.add(normalized)) return true;
        dtm.ide.project.MavenPom pom = dtm.ide.project.MavenPom.parse(normalized);
        if (!pom.isValid()) return false;
        for (String module : pom.values("modules", "module")) {
            if (module.contains("${")) continue;
            Path child = normalized.getParent().resolve(module).normalize();
            if (!child.getFileName().toString().endsWith(".xml")) child = child.resolve("pom.xml");
            if (!validMavenReactor(child, visited)) return false;
        }
        return true;
    }
}
