package dtm.ide.adapter;

import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.tree.PathRenameDecision;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.refactor.MavenModuleRename;
import dtm.ide.ui.JavaModuleRenameDialogPanel;
import lombok.extern.slf4j.Slf4j;

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class PathRenameSupport {

    private final AdapterHost host;
    private final Set<Path> pendingModuleDirectoryRenames = ConcurrentHashMap.newKeySet();

    public PathRenameSupport(AdapterHost host) {
        this.host = host;
    }

    public PathRenameDecision beforePathRename(Path path) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return PathRenameDecision.useDefault();
        }
        Optional<MavenModuleRename.Target> target = MavenModuleRename.of(current, path, host::readCurrentText);
        if (target.isEmpty()) {
            return PathRenameDecision.useDefault();
        }
        JavaModuleRenameDialogPanel.Result result = askModuleRename(target.get());
        if (result == null) {
            return PathRenameDecision.cancel();
        }
        IdeWorkspaceEdit edit = MavenModuleRename.plan(target.get(), result.scope(), result.name(),
                host::readCurrentText);
        if (edit.isEmpty()) {
            return PathRenameDecision.cancel();
        }
        boolean movesDirectory = edit.operations().stream()
                .anyMatch(operation -> operation instanceof IdeWorkspaceEdit.RenameFile);
        if (movesDirectory) {
            pendingModuleDirectoryRenames.add(target.get().directory());
        }
        SwingUtilities.invokeLater(host::syncProject);
        String label = text("moduleRename.label", "Rename module \"{module}\" to \"{name}\"")
                .replace("{module}", target.get().artifactId())
                .replace("{name}", result.name());
        return PathRenameDecision.apply(label, edit);
    }

    private JavaModuleRenameDialogPanel.Result askModuleRename(MavenModuleRename.Target target) {
        CompletableFuture<JavaModuleRenameDialogPanel.Result> answer = new CompletableFuture<>();
        Runnable open = () -> {
            JavaModuleRenameDialogPanel panel = new JavaModuleRenameDialogPanel(target, answer::complete);
            host.showPopup(PlatformPopupBuilder.builder()
                    .component(panel)
                    .title(text("moduleRename.title", "Rename"))
                    .size(500, 320)
                    .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                    .onLoad(component -> panel.focusInput())
                    .onClose(component -> panel.closed())
                    .build());
        };
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(open);
            try {
                return answer.get(30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        answer.whenComplete((result, error) -> loop.exit());
        open.run();
        if (!answer.isDone()) {
            loop.enter();
        }
        return answer.getNow(null);
    }

    public void onPathRenamed(Path oldPath, Path newPath) {
        Path movedFrom = JavaProjectConventions.normalize(oldPath);
        Path movedTo = JavaProjectConventions.normalize(newPath);
        if (movedFrom != null && movedTo != null && !pendingModuleDirectoryRenames.remove(movedFrom)
                && isModuleRoot(movedFrom)) {
            host.onBuildFileChanged(movedTo.resolve(JavaProjectConventions.POM_FILE));
        }
        Set<Path> editorsBeforeMove = movedFrom == null ? Set.of() : host.javaEditors().keySet().stream()
                .filter(path -> path.startsWith(movedFrom))
                .map(path -> movedTo == null ? path : movedTo.resolve(movedFrom.relativize(path)))
                .collect(Collectors.toUnmodifiableSet());
        if (oldPath != null) {
            onPathDeleted(oldPath);
        }
        if (newPath == null) {
            return;
        }
        JavaFileChangeRouter router = host.fileChangeRouter();
        if (router == null) {
            return;
        }
        if (!Files.isDirectory(newPath)) {
            announceMovedFile(router, newPath, editorsBeforeMove);
            return;
        }
        host.background().submit(() -> {
            try (Stream<Path> files = Files.walk(newPath)) {
                files.filter(Files::isRegularFile)
                        .forEach(file -> announceMovedFile(router, file, editorsBeforeMove));
            } catch (IOException | java.io.UncheckedIOException e) {
                log.debug("Falha ao anunciar arquivos da pasta renomeada {}: {}", newPath, e.getMessage());
            }
        });
    }

    private void announceMovedFile(JavaFileChangeRouter router, Path file, Set<Path> editorManagedTargets) {
        Path normalized = JavaProjectConventions.normalize(file);
        if (!editorManagedTargets.contains(normalized) && !host.javaEditors().containsKey(normalized)) {
            router.acceptCreated(file);
            return;
        }
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.pathCreated(normalized);
        }
    }

    public void onPathDeleted(Path path) {
        if (path == null) {
            return;
        }
        Path deleted = path.toAbsolutePath().normalize();
        if (JavaProjectConventions.isJava(deleted)) {
            host.fileWatch().forgetJavaFile(deleted);
        }
        if (JavaProjectConventions.isMavenPom(deleted)
                || JavaProjectConventions.isGradleBuildFile(deleted)) {
            host.onBuildFileChanged(deleted);
        }
        host.problems().removeBelow(deleted);
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.pathDeleted(deleted);
        }
        host.refreshProblemsPanel();
    }

    private boolean isModuleRoot(Path directory) {
        JavaProjectDescriptor current = host.descriptor();
        return current != null && current.modules().stream()
                .anyMatch(module -> module.root().equals(directory));
    }
}
