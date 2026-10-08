package dtm.ide.adapter;

import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.refactor.JavaPathTransferPlan;
import dtm.ide.refactor.JavaPathTransferRefactoring;
import dtm.ide.ui.JavaCopyDialogPanel;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaMoveDialogPanel;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

public final class PathTransferHost implements JavaPathTransferRefactoring.Host {

    private static final long DIALOG_TIMEOUT_MINUTES = 30;

    private final AdapterHost host;

    public PathTransferHost(AdapterHost host) {
        this.host = host;
    }

    @Override
    public JavaProjectDescriptor descriptor() {
        return host.descriptor();
    }

    @Override
    public JavaLanguageServer readyServer() {
        JavaLanguageServer lsp = host.languageServer();
        return lsp != null && lsp.isReady() ? lsp : null;
    }

    @Override
    public JavaMoveDialogPanel.Choice askMove(JavaPathTransferPlan plan) {
        CompletableFuture<JavaMoveDialogPanel.Choice> answer = new CompletableFuture<>();
        Runnable open = () -> {
            JavaMoveDialogPanel panel = new JavaMoveDialogPanel(plan, answer::complete);
            host.showPopup(PlatformPopupBuilder.builder()
                    .component(panel)
                    .title(text("move.title", "Move"))
                    .size(520, 240 + Math.min(6, plan.files().size() + plan.folders().size()) * 26)
                    .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                    .onLoad(component -> panel.focusConfirm())
                    .onClose(component -> panel.closed())
                    .build());
        };
        if (SwingUtilities.isEventDispatchThread()) open.run();
        else {
            SwingUtilities.invokeLater(open);
            return await(answer, JavaMoveDialogPanel.Choice.CANCEL);
        }
        return answer.getNow(JavaMoveDialogPanel.Choice.CANCEL);
    }

    @Override
    public JavaCopyDialogPanel.Result askCopy(JavaPathTransferPlan plan, Map<Path, String> defaultNames) {
        if (SwingUtilities.isEventDispatchThread()) {
            return new JavaCopyDialogPanel.Result(false, Map.of());
        }
        CompletableFuture<JavaCopyDialogPanel.Result> answer = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JavaCopyDialogPanel panel = new JavaCopyDialogPanel(plan, defaultNames, answer::complete);
            host.showPopup(PlatformPopupBuilder.builder()
                    .component(panel)
                    .title(text("copy.title", "Copy"))
                    .size(520, 250 + Math.min(6, plan.files().size() + plan.folders().size()) * 22)
                    .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                    .onLoad(component -> panel.focusInput())
                    .onClose(component -> panel.closed())
                    .build());
        });
        return await(answer, null);
    }

    @Override
    public String readText(Path file) {
        return host.readCurrentText(file);
    }

    @Override
    public void warn(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        host.createNotification(NotificationContext.builder()
                .title(text("transfer.title", "Move/copy of Java files"))
                .message(message)
                .icon(JavaIcons.java(JavaIcons.SMALL))
                .build());
    }

    private <T> T await(CompletableFuture<T> answer, T fallback) {
        try {
            return answer.get(DIALOG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

}
