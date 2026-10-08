package dtm.ide.adapter;

import dtm.ide.api.project.IdeProjectFileWatcher;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.ui.JavaProjectTreeIcons;
import dtm.ide.ui.SpringExplorerPanel;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.swing.*;

@Slf4j
public final class FileWatchSupport {

    private static final long PROJECT_CONFIGURATION_REQUEST_DELAY_MS = 1_500;
    private static final long PROJECT_CONFIGURATION_REQUEST_COOLDOWN_MS = 10_000;

    private final AdapterHost host;
    private final Object fileWatcherLock = new Object();
    private final AtomicLong lastConfigurationUpdateRequest = new AtomicLong();
    private volatile Path fileWatcherRoot;
    private volatile JavaFileChangeRouter fileChangeRouter;
    private volatile IdeProjectFileWatcher projectFileWatcher;
    private volatile String fileWatcherListenerId;

    public FileWatchSupport(AdapterHost host) {
        this.host = host;
    }

    public JavaFileChangeRouter fileChangeRouter() {
        return fileChangeRouter;
    }

    public void registerFileWatcher() {
        synchronized (fileWatcherLock) {
            if (fileWatcherListenerId != null && Objects.equals(fileWatcherRoot, host.projectRoot())) {
                return;
            }
            unregisterFileWatcher();
            registerFileWatcherLocked();
        }
    }

    private void registerFileWatcherLocked() {
        IdeProjectFileWatcher watcher;
        try {
            watcher = host.projectFileWatcher();
        } catch (Exception e) {
            log.debug("Observador de arquivos indisponivel: {}", e.getMessage());
            return;
        }
        if (watcher == null) {
            log.debug("Observador de arquivos indisponivel para este projeto");
            return;
        }
        JavaFileChangeRouter router = new JavaFileChangeRouter(this::onWatchedFileChanged,
                path -> host.javaEditors().containsKey(JavaProjectConventions.normalize(path)), host.projectRoot());
        projectFileWatcher = watcher;
        fileChangeRouter = router;
        fileWatcherRoot = host.projectRoot();
        fileWatcherListenerId = watcher.addFileWatcherListener(router::accept);
        log.info("Observador de arquivos do Java registrado: {}", fileWatcherListenerId);
    }

    public void unregisterFileWatcher() {
        String listenerId;
        IdeProjectFileWatcher watcher;
        synchronized (fileWatcherLock) {
            listenerId = fileWatcherListenerId;
            watcher = projectFileWatcher;
            fileWatcherListenerId = null;
            projectFileWatcher = null;
            fileWatcherRoot = null;
        }
        if (listenerId != null && watcher != null) {
            try {
                watcher.removeFileWatcherListener(listenerId);
            } catch (Exception e) {
                log.debug("Falha ao remover o observador de arquivos: {}", e.getMessage());
            }
        }
        JavaFileChangeRouter router = fileChangeRouter;
        fileChangeRouter = null;
        if (router != null) {
            router.shutdown();
        }
    }

    private void onWatchedFileChanged(Path file, JavaFileChangeRouter.FileRole role,
                                      JavaFileChangeRouter.Change change, boolean editorManaged) {
        switch (role) {
            case JAVA -> onWatchedJavaFile(file, change, editorManaged);
            case SPRING_CONFIG -> onWatchedSpringConfigFile(file, change);
            case BUILD -> onWatchedBuildFile(file, change);
        }
    }

    private void onWatchedJavaFile(Path file, JavaFileChangeRouter.Change change,
                                   boolean editorManaged) {
        JavaProjectTreeIcons.invalidate(file);
        host.requestJavaTreeIconRefresh(file);
        if (change == JavaFileChangeRouter.Change.DELETED) {
            forgetJavaFile(file);
            JavaLanguageServer lsp = host.languageServer();
            if (lsp != null) {
                lsp.pathDeleted(file);
            }
            return;
        }
        String content = JavaProjectConventions.readOrEmpty(file);
        if (editorManaged) {
            onExternalChangeToOpenFile(file, content);
            return;
        }
        host.lexicalIndex().refreshFile(file, content);
        refreshSpringIndexFor(file, content);

        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            if (change == JavaFileChangeRouter.Change.CREATED) {
                lsp.pathCreated(file);
                if (!insideKnownSourceRoot(file)) {
                    requestProjectConfigurationUpdate();
                }
            } else {
                lsp.pathChanged(file);
            }
        }
        host.requestRefreshCodeLenses(file);
    }

    private void onExternalChangeToOpenFile(Path file, String rawDiskContent) {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null || rawDiskContent == null) {
            return;
        }
        String diskContent = normalizeDiskText(rawDiskContent);
        String mirrored = lsp.documentContent(file);
        if (mirrored == null) {
            host.lexicalIndex().refreshFile(file, diskContent);
            lsp.pathChanged(file);
            return;
        }
        if (diskContent.equals(mirrored)) {
            return;
        }
        host.lexicalIndex().refreshFile(file, diskContent);
        Path normalized = JavaProjectConventions.normalize(file);
        IdeEditorContext editor = host.javaEditors().get(normalized);
        String baseline = host.diskBaseline().get(normalized);
        if (editor == null || baseline == null || !baseline.equals(mirrored)) {
            lsp.requestExternalResync();
            return;
        }
        host.diskBaseline().put(normalized, diskContent);
        SwingUtilities.invokeLater(() -> editor.setText(diskContent));
    }

    static String normalizeDiskText(String raw) {
        return raw == null ? null : raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String diskBaselineFor(Path file, String editorText) {
        if (file == null || !Files.isRegularFile(file)) {
            return editorText;
        }
        return normalizeDiskText(JavaProjectConventions.readOrEmpty(file));
    }

    private boolean insideKnownSourceRoot(Path file) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return true;
        }
        Path normalized = JavaProjectConventions.normalize(file);
        return current.moduleOf(normalized)
                .map(module -> Stream.concat(module.sourceRoots().stream(),
                                module.testRoots().stream())
                        .anyMatch(normalized::startsWith))
                .orElse(false);
    }

    private void requestProjectConfigurationUpdate() {
        long now = System.currentTimeMillis();
        long previous = lastConfigurationUpdateRequest.get();
        if (now - previous < PROJECT_CONFIGURATION_REQUEST_COOLDOWN_MS
                || !lastConfigurationUpdateRequest.compareAndSet(previous, now)) {
            return;
        }
        host.background().schedule(() -> {
            JavaLanguageServer lsp = host.languageServer();
            ProjectModelSupport model = lsp == null ? null : lsp.extension(ProjectModelSupport.class);
            if (model != null) {
                model.projectConfigurationUpdate();
            }
        }, PROJECT_CONFIGURATION_REQUEST_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void onWatchedSpringConfigFile(Path file, JavaFileChangeRouter.Change change) {
        JavaProjectDescriptor current = host.descriptor();
        Path root = host.projectRoot();
        if (current == null || root == null || !current.spring()) {
            return;
        }
        host.spring().loadConfigIndex(host.lifecycleTicket(), root);
    }

    private void onWatchedBuildFile(Path file, JavaFileChangeRouter.Change change) {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            if (change == JavaFileChangeRouter.Change.DELETED) {
                lsp.pathDeleted(file);
            } else {
                lsp.pathChanged(file);
            }
            ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
            if (model != null) {
                model.projectConfigurationUpdate();
            }
        }
        host.onBuildFileChanged(file);
    }

    public void refreshSpringIndexFor(Path file, String content) {
        JavaProjectDescriptor current = host.descriptor();
        Path root = host.projectRoot();
        long ticket = host.lifecycleTicket();
        if (current == null || root == null || !current.spring()
                || !host.settings().isSpringSupport()) {
            return;
        }
        host.spring().index().refreshFile(file, content).thenAccept(snapshot -> {
            if (!host.isCurrent(ticket, root)) {
                return;
            }
            host.requestRefreshCodeLenses(file);
            SpringExplorerPanel panel = host.spring().panel();
            if (panel != null) {
                panel.reload();
            }
        });
    }

    public void forgetJavaFile(Path file) {
        host.lexicalIndex().refreshFile(file, "");
        host.todoSupport().forget(file);
        JavaProjectDescriptor current = host.descriptor();
        if (current != null && current.spring() && host.settings().isSpringSupport()) {
            refreshSpringIndexFor(file, "");
        }
    }
}
