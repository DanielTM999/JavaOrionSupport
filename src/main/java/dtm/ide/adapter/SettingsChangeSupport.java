package dtm.ide.adapter;

import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.settings.JdtBuildMode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class SettingsChangeSupport {

    private final AdapterHost host;

    public SettingsChangeSupport(AdapterHost host) {
        this.host = host;
    }

    public void applySettings() {
        JavaPluginSettings current = host.currentSettings();
        if (current == null) {
            return;
        }
        host.spring().baseUrl(current.getSpringBaseUrl());
        host.applyDependencySearchSettings(current);
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.setInlayHintsMode(current.getInlayHints());
        }
        Path root = host.projectRoot();
        if (root != null) {
            host.requestRefreshCodeLenses(root);
        }
        SwingUtilities.invokeLater(host.coverage()::refreshGutters);
        if (!applyBuildModeChange(current.getJdtBuildMode(), root)) {
            applyLombokSettingChange(root);
        }
    }

    private boolean applyBuildModeChange(JdtBuildMode mode, Path root) {
        if (mode == host.languageServerManager().appliedBuildMode()) {
            return false;
        }
        host.languageServerManager().appliedBuildMode(mode);
        JavaLanguageServer lsp = host.languageServer();
        if (root == null || lsp == null) {
            return false;
        }
        host.background().submit(() -> {
            lsp.stop();
            host.hideProgress(LanguageServerManager.LSP_PROGRESS_ID);
            host.resolveProjectJdk(host.nextLifecycleTicket(), root);
        });
        return true;
    }

    private void applyLombokSettingChange(Path root) {
        JavaLanguageServer lsp = host.languageServer();
        if (root == null || lsp == null) {
            return;
        }
        host.background().submit(() -> restartWhenLombokAgentChanged(lsp, host.descriptor()));
    }

    public void restartLanguageServer() {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.resetCrashHistory();
        }
        host.setStatusBarText(text("status.restartingLsp", "Java: reiniciando o IntelliSense..."));
        host.clearCaches();
    }

    private void restartWhenLombokAgentChanged(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        if (current == null || lsp == null) {
            return;
        }
        host.languageServerManager().applyLombokAgent(lsp, current);
        if (!LanguageServerManager.needsLombokAgentRestart(lsp)) {
            return;
        }
        log.info("Agente do Lombok mudou; reiniciando o IntelliSense Java");
        host.setStatusBarText(text("status.lombokRestart",
                "Java: Lombok mudou - reiniciando o IntelliSense"));
        host.clearCaches();
    }
}
