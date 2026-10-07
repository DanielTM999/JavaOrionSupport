package dtm.ide.adapter;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.ui.JdkManagerPanel;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static dtm.ide.adapter.AdapterFailures.rootMessage;
import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class JdkManagerSupport implements JdkManagerPanel.Host {

    private final AdapterHost host;

    public JdkManagerSupport(AdapterHost host) {
        this.host = host;
    }

    @Override
    public List<JdkInstallation> installations() {
        return host.jdkService().available();
    }

    @Override
    public JdkInstallation projectJdk() {
        return host.projectJdk();
    }

    @Override
    public void refreshInstallations() {
        host.jdkService().refresh();
    }

    @Override
    public void download(int major, java.util.function.Consumer<String> onDone) {
        host.background().submit(() -> {
            try {
                JdkInstallation installed = host.jdkService().install(major, host.progressListener());
                adoptIfRequired(installed);
                onDone.accept(null);
            } catch (Exception e) {
                log.warn("Falha ao instalar a JDK {}", major, e);
                onDone.accept(text("status.downloadFailed", "Falha ao baixar a JDK") + ": "
                        + rootMessage(e));
            }
        });
    }

    @Override
    public void addExisting(Path home, java.util.function.Consumer<String> onDone) {
        host.background().submit(() -> {
            try {
                Optional<JdkInstallation> inspected = host.jdkService().inspectExisting(home);
                if (inspected.isEmpty()) {
                    onDone.accept(text("status.notAJdk",
                            "O diretorio selecionado nao contem uma JDK utilizavel:") + " " + home);
                    return;
                }
                JdkInstallation installation = inspected.get();
                host.jdkService().refresh();
                useForProject(installation);
                onDone.accept(null);
            } catch (Exception e) {
                log.warn("Falha ao adicionar a JDK {}", home, e);
                onDone.accept(text("status.addFailed", "Falha ao adicionar a JDK") + ": "
                        + rootMessage(e));
            }
        });
    }

    @Override
    public Integer requiredMajor() {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return host.settings().getDefaultJdkVersion();
        }
        return current.jdkMajor().orElseGet(() -> host.settings().getDefaultJdkVersion());
    }

    private void adoptIfRequired(JdkInstallation installed) {
        Integer required = requiredMajor();
        if (installed == null || host.projectRoot() == null) {
            return;
        }
        if (host.projectJdk() == null || (required != null && required == installed.major())) {
            useForProject(installed);
        }
    }

    @Override
    public void useForProject(JdkInstallation installation) {
        Path root = host.projectRoot();
        if (root == null || installation == null) {
            return;
        }
        long ticket = host.lifecycleTicket();
        host.background().submit(() -> {
            try {
                host.jdkService().selectHomeForProject(root, installation.home());
                JavaProjectDescriptor described = host.timed("describe(useForProject)",
                        () -> JavaProjectConventions.describe(root));
                if (!host.isCurrent(ticket, root)) {
                    return;
                }
                host.projectJdk(installation);
                host.descriptor(described);
                if (described != null) {
                    host.rebuildLexicalIndex(described);
                }
                SwingUtilities.invokeLater(() -> {
                    if (!host.isCurrent(ticket, root)) {
                        return;
                    }
                    host.refreshRunButtonsForCurrentFile();
                    host.reloadBuildToolsPanel();
                    host.setStatusBarText("Java: " + installation.displayName());
                });
            } catch (Exception e) {
                log.warn("Falha ao selecionar JDK para {}", root, e);
                SwingUtilities.invokeLater(() -> {
                    if (host.isCurrent(ticket, root)) {
                        host.setStatusBarText("Java: " + rootMessage(e));
                    }
                });
            }
        });
    }

    @Override
    public boolean remove(JdkInstallation installation) {
        boolean removed = host.jdkService().remove(installation);
        if (removed && installation.equals(host.projectJdk())) {
            host.projectJdk(null);
            Path root = host.projectRoot();
            if (root != null) {
                host.resolveProjectJdk(host.nextLifecycleTicket(), root);
            }
        }
        return removed;
    }
}
