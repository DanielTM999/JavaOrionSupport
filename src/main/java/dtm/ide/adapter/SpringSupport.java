package dtm.ide.adapter;

import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectSources;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringBeanIndex;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringPropertyUsage;
import dtm.ide.spring.config.SpringConfigIndex;
import dtm.ide.spring.config.SpringConfigMetadata;
import dtm.ide.spring.config.SpringConfigProperty;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.ide.spring.live.SpringActuatorClient;
import dtm.ide.spring.live.SpringRuntimeBeans;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.SpringExplorerPanel;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class SpringSupport implements SpringExplorerPanel.Host {

    private final AdapterHost host;
    private final SpringBeanIndex index = new SpringBeanIndex();
    private final SpringActuatorClient actuator = new SpringActuatorClient();
    private volatile SpringExplorerPanel explorerPanel;
    private volatile String explorerPanelId;
    private volatile SpringConfigMetadata metadata = SpringConfigMetadata.builtIn();
    private volatile SpringConfigIndex configIndex = SpringConfigIndex.empty();
    private volatile String baseUrl = JavaPluginSettings.DEFAULT_SPRING_BASE_URL;

    public SpringSupport(AdapterHost host) {
        this.host = host;
    }

    public SpringBeanIndex index() {
        return index;
    }

    public SpringConfigMetadata metadata() {
        return metadata;
    }

    public SpringConfigIndex configIndex() {
        return configIndex;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void baseUrl(String value) {
        baseUrl = value;
    }

    public SpringExplorerPanel panel() {
        return explorerPanel;
    }

    public String panelId() {
        return explorerPanelId;
    }

    public void clearPanelId() {
        explorerPanelId = null;
    }

    public void resetConfiguration() {
        configIndex = SpringConfigIndex.empty();
        metadata = SpringConfigMetadata.builtIn();
    }

    public void setup(long ticket, Path root) {
        setup(ticket, root, null);
    }

    public void setup(long ticket, Path root, JavaProjectSources sources) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null || !current.spring() || !host.settings().isSpringSupport()) {
            return;
        }
        index.rebuild(current, sources).thenAccept(snapshot -> {
            if (!host.isCurrent(ticket, root)) {
                return;
            }
            metadata = metadata.withProjectProperties(projectConfigProperties());
            snapshot.beans().stream().map(SpringBean::file).distinct()
                    .forEach(host::requestRefreshCodeLenses);
            host.refreshDiagnosticsOfOpenJavaEditors();
            SwingUtilities.invokeLater(() -> {
                if (!host.isCurrent(ticket, root)) {
                    return;
                }
                SpringExplorerPanel panel = explorerPanel;
                if (panel != null) {
                    panel.reload();
                }
                host.setStatusBarText("Spring: " + snapshot.beans().size() + " bean(s), "
                        + snapshot.endpoints().size() + " endpoint(s)");
            });
        });
        if (!host.settings().getLanguageServerMode().startsServer()) {
            loadMetadata(ticket, root);
        }
        loadConfigIndex(ticket, root);
        SwingUtilities.invokeLater(() -> {
            if (host.isCurrent(ticket, root)) {
                setupPanel();
            }
        });
    }

    public void loadMetadata(long ticket, Path root) {
        host.background().submit(() -> {
            BuildSystem build = host.buildSystem();
            JavaProjectDescriptor current = host.descriptor();
            if (build == null || current == null) {
                return;
            }
            List<Path> classpath = new ArrayList<>();
            for (JavaModule module : springConfigModules(current)) {
                host.runtimeClasspathOf(build, module).ifPresent(entries -> {
                    for (String entry : entries.split(java.io.File.pathSeparator)) {
                        if (!entry.isBlank()) {
                            classpath.add(Path.of(entry));
                        }
                    }
                });
                if (!host.isCurrent(ticket, root)) {
                    return;
                }
            }
            if (classpath.isEmpty()) {
                return;
            }
            SpringConfigMetadata metadata = SpringConfigMetadata.fromClasspath(classpath);
            if (!metadata.fromClasspath()) {
                log.info("Nenhum metadado de configuracao do Spring encontrado no classpath");
                return;
            }
            this.metadata = metadata.withProjectProperties(projectConfigProperties());
            log.info("Catalogo de configuracao do Spring carregado de {} entrada(s): {} chave(s)",
                    classpath.size(), metadata.size());
        });
    }

    private List<SpringConfigProperty> projectConfigProperties() {
        List<SpringConfigProperty> properties = new ArrayList<>();
        for (SpringPropertyUsage usage : index.snapshot().propertyUsages()) {
            if (usage.isPrefix() && !usage.key().isBlank()) {
                properties.add(SpringConfigProperty.of(usage.key(), "",
                        SpringBean.simpleNameOf(usage.ownerType())));
            }
        }
        return properties;
    }

    public void loadConfigIndex(long ticket, Path root) {
        host.background().submit(() -> {
            JavaProjectDescriptor current = host.descriptor();
            if (current == null || !host.isCurrent(ticket, root)) {
                return;
            }
            List<Path> resourceRoots = new ArrayList<>();
            for (JavaModule module : springConfigModules(current)) {
                resourceRoots.add(module.root().resolve("src").resolve("main").resolve("resources"));
                resourceRoots.add(module.root().resolve("src").resolve("test").resolve("resources"));
            }
            SpringConfigIndex index = SpringConfigIndex.scan(resourceRoots);
            if (!host.isCurrent(ticket, root)) {
                return;
            }
            configIndex = index;
            log.info("Indice de configuracao do Spring: {} chave(s) em {} raiz(es)",
                    index.entries().size(), resourceRoots.size());
        });
    }

    private List<JavaModule> springConfigModules(JavaProjectDescriptor current) {
        List<JavaModule> withConfig = current.buildableModules().stream()
                .filter(SpringSupport::hasSpringConfigFile)
                .toList();
        if (!withConfig.isEmpty()) {
            return withConfig;
        }
        List<JavaModule> buildable = current.buildableModules();
        if (!buildable.isEmpty()) {
            return buildable;
        }
        JavaModule rootModule = current.rootModule();
        return rootModule == null ? List.of() : List.of(rootModule);
    }

    private static boolean hasSpringConfigFile(JavaModule module) {
        Path resources = module.root().resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return false;
        }
        try (java.util.stream.Stream<Path> files = Files.list(resources)) {
            return files.anyMatch(file -> Files.isRegularFile(file)
                    && SpringConfigSupport.isConfigFile(file));
        } catch (Exception e) {
            log.debug("Falha ao inspecionar os recursos de {}: {}", module.root(), e.getMessage());
            return false;
        }
    }

    public void setupPanel() {
        if (explorerPanelId != null) {
            return;
        }
        SpringExplorerPanel panel = new SpringExplorerPanel(this);
        explorerPanel = panel;
        explorerPanelId = host.registerBottomPanel("Spring",
                JavaIcons.springExplorer(JavaIcons.SMALL), panel);
    }

    private void adoptRuntimeBeans(List<SpringActuatorClient.LiveBean> liveBeans) {
        if (!host.settings().isSpringRuntimeBeans()) {
            return;
        }
        List<SpringBean> runtime = SpringRuntimeBeans.from(liveBeans, index.snapshot());
        index.applyRuntimeBeans(runtime);
        log.info("Beans de runtime adotados do Actuator: {}", runtime.size());
    }

    @Override
    public SpringIndexSnapshot snapshot() {
        return index.snapshot();
    }

    @Override
    public void openFile(Path file, int line) {
        if (file == null) {
            return;
        }
        host.openAt(file, Math.max(0, line - 1), 0);
    }

    @Override
    public void openInBrowser(String url) {
        host.openWebBrowser(url);
    }

    @Override
    public String applicationBaseUrl() {
        return baseUrl;
    }

    @Override
    public void loadLive(java.util.function.Consumer<SpringExplorerPanel.LiveData> onResult) {
        host.background().submit(() -> {
            SpringExplorerPanel.LiveData data = SpringExplorerPanel.LiveData.unavailable();
            try {
                String baseUrl = this.baseUrl;
                if (actuator.isAvailable(baseUrl)) {
                    data = new SpringExplorerPanel.LiveData(true,
                            actuator.health(baseUrl),
                            actuator.beans(baseUrl),
                            actuator.environment(baseUrl),
                            actuator.mappings(baseUrl));
                    adoptRuntimeBeans(data.beans());
                }
            } catch (Exception e) {
                log.debug("Falha ao consultar o Actuator: {}", e.getMessage());
            } finally {
                onResult.accept(data);
            }
        });
    }

    @Override
    public void refreshIndex(Runnable onDone) {
        JavaProjectDescriptor current = host.descriptor();
        index.rebuild(current).thenRun(() -> SwingUtilities.invokeLater(onDone));
    }
}
