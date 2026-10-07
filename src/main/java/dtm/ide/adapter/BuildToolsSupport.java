package dtm.ide.adapter;

import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildRunConfigurations;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.BuildToolDebug;
import dtm.ide.build.BuildToolModel;
import dtm.ide.build.MavenPluginGoals;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.BuildRunConfigurationBridge;
import dtm.ide.ui.BuildPromptPanel;
import dtm.ide.ui.JavaBuildToolsPanel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dtm.ide.adapter.AdapterFailures.rootMessage;
import static dtm.ide.adapter.AdapterText.text;

public final class BuildToolsSupport implements JavaBuildToolsPanel.Host {

    private final AdapterHost host;

    public BuildToolsSupport(AdapterHost host) {
        this.host = host;
    }

    @Override
    public BuildToolModel load() {
        host.pluginGoals().clearCache();
        return BuildToolModel.load(host.descriptor());
    }

    @Override
    public void sync() {
        host.clearPluginRepositoryPath();
        host.syncProject();
    }

    @Override
    public void profilesChanged(java.util.Set<String> profiles) {
        new BuildRunConfigurations(host.projectRoot()).saveActiveProfiles(profiles);
        BuildSystem build = host.currentBuildSystem();
        if (build != null) {
            build.invalidateClasspathCache();
        }
    }

    @Override
    public java.util.Set<String> activeProfiles() {
        return new BuildRunConfigurations(host.projectRoot()).activeProfiles();
    }

    @Override
    public BuildRunConfigurations.ToolOptions toolOptions() {
        return new BuildRunConfigurations(host.projectRoot()).toolOptions();
    }

    @Override
    public void toolOptionsChanged(BuildRunConfigurations.ToolOptions options) {
        new BuildRunConfigurations(host.projectRoot()).saveToolOptions(options);
    }

    @Override
    public List<BuildRunConfigurations.Entry> runConfigurations() {
        migrateLegacyBuildRunConfigurations();
        return BuildRunConfigurationBridge.entries(host.requestRunConfigurations(), buildToolIsGradle());
    }

    @Override
    public void saveRunConfiguration(BuildRunConfigurations.Entry entry) {
        if (entry == null || !entry.isValid()) {
            return;
        }
        boolean gradle = buildToolIsGradle();
        String existingId = BuildRunConfigurationBridge.idOf(host.requestRunConfigurations(), entry.name(), gradle)
                .orElse(null);
        host.requestSaveRunConfiguration(BuildRunConfigurationBridge.toRunConfiguration(entry, gradle, existingId));
    }

    @Override
    public void removeRunConfiguration(String name) {
        BuildRunConfigurationBridge.idOf(host.requestRunConfigurations(), name, buildToolIsGradle())
                .ifPresent(host::requestRemoveRunConfiguration);
    }

    private boolean buildToolIsGradle() {
        JavaProjectDescriptor current = host.descriptor();
        return current != null && current.isGradle();
    }

    private void migrateLegacyBuildRunConfigurations() {
        Path root = host.projectRoot();
        if (root == null || !host.migratedBuildRunConfigurations().add(root.toAbsolutePath().normalize())) {
            return;
        }
        BuildRunConfigurations legacy = new BuildRunConfigurations(root);
        List<RunConfigurationData> current = host.requestRunConfigurations();
        boolean gradle = buildToolIsGradle();
        for (BuildRunConfigurations.Entry entry : legacy.all()) {
            String existingId = BuildRunConfigurationBridge.idOf(current, entry.name(), gradle).orElse(null);
            RunConfigurationData saved = existingId != null
                    ? RunConfigurationData.builder().id(existingId).build()
                    : host.requestSaveRunConfiguration(BuildRunConfigurationBridge.toRunConfiguration(entry, gradle, null));
            if (BuildRunConfigurationBridge.saved(saved)) {
                legacy.remove(entry.name());
            } else {
                host.migratedBuildRunConfigurations().remove(root.toAbsolutePath().normalize());
            }
        }
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    public boolean showPrompt(BuildPromptPanel prompt, String title) {
        host.showPopup(PlatformPopupBuilder.builder()
                .component(prompt)
                .title(title)
                .size(prompt.popupSize())
                .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                .onLoad(component -> prompt.focusField())
                .onClose(component -> prompt.closed())
                .build());
        return true;
    }

    @Override
    public void executeGoals(BuildToolModel.Node context, List<String> goals) {
        runToolGoals(context == null ? null : context.module(), goals, false);
    }

    @Override
    public void debugGoals(BuildToolModel.Node context, List<String> goals) {
        runToolGoals(context == null ? null : context.module(), goals, true);
    }

    @Override
    public void execute(BuildToolModel.Node command) {
        if (command == null || !command.executable()) {
            reject(text("status.nothingToRun", "Nada para executar"));
            return;
        }
        runToolGoals(command.module(), command.command(), false);
    }

    @Override
    public List<MavenPluginGoals.Goal> goalsOf(BuildToolModel.Coordinate coordinate) {
        return coordinate == null
                ? List.of()
                : host.pluginGoals().goalsOf(coordinate.groupId(), coordinate.artifactId(),
                        coordinate.version());
    }

    @Override
    public void cancel() {
        BuildSystem build = host.currentBuildSystem();
        if (build != null) {
            build.cancel();
        }
    }

    private void reject(String message) {
        JavaBuildToolsPanel panel = host.buildToolsPanel();
        if (panel != null) {
            panel.warning(message);
        }
    }

    private void runToolGoals(JavaModule module, List<String> goals, boolean debug) {
        BuildSystem build = host.buildSystem();
        if (build == null || goals == null || goals.isEmpty()) {
            reject(text("status.buildToolUnavailable", "Nenhum build tool disponivel"));
            return;
        }
        if (build.isRunning()) {
            reject(text("status.buildRunning", "Ja existe um build em andamento"));
            return;
        }
        JavaProjectDescriptor current = host.descriptor();
        boolean gradle = current != null && current.isGradle();
        BuildRunConfigurations.ToolOptions toolOptions =
                new BuildRunConfigurations(host.projectRoot()).toolOptions();
        List<String> arguments = new ArrayList<>();
        if (toolOptions.skipTests()) {
            arguments.addAll(gradle ? List.of("-x", "test") : List.of("-DskipTests"));
        }
        Map<String, String> environment = new LinkedHashMap<>();
        BuildToolDebugListener listener = null;
        if (debug) {
            try {
                listener = host.openBuildDebugListener(module, build::cancel);
                BuildToolDebug.Plan plan = gradle
                        ? BuildToolDebug.gradle(goals, arguments, listener.listenPort())
                        : BuildToolDebug.maven(goals, arguments, listener.listenPort(),
                                System.getenv());
                if (!plan.debuggable()) {
                    listener.close();
                    reject(text("status.noDebuggableJvm",
                            "Nenhuma JVM depuravel nessas tasks (use run, bootRun ou test)"));
                    return;
                }
                arguments.addAll(plan.arguments());
                environment.putAll(plan.environment());
            } catch (Exception error) {
                if (listener != null) {
                    listener.close();
                }
                reject(text("error.buildDebugListen", "Nao foi possivel abrir a porta de debug:")
                        + " " + rootMessage(error));
                return;
            }
        }
        BuildCommand.Options options = new BuildCommand.Options(List.of(), arguments,
                toolOptions.offline(), environment);
        OutputPanelHandle output = host.requestOutputPanel("Build", OutputPanelOptions.interactive(null));
        if (output != null) {
            output.clear();
            output.show();
        }
        BuildToolDebugListener debugListener = listener;
        host.background().submit(() -> {
            BuildResult result;
            try {
                result = build.executeToolCommand(module, goals, options,
                        line -> host.writeOutput(output, line));
            } catch (RuntimeException error) {
                host.writeOutput(output, rootMessage(error));
                result = BuildResult.failed(build.name(), rootMessage(error));
            } finally {
                if (debugListener != null) {
                    debugListener.close();
                }
            }
            host.publishBuildDiagnostics(result, true);
            if (!result.summary().isEmpty()) {
                host.writeOutput(output, result.summary());
            }
            JavaBuildToolsPanel panel = host.buildToolsPanel();
            if (panel != null) {
                panel.finished(result.summary(), result.successful());
            }
        });
    }
}
