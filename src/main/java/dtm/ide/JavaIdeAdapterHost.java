package dtm.ide;

import dtm.ide.adapter.AdapterHost;
import dtm.ide.adapter.CodeLensSupport;
import dtm.ide.adapter.CompletionEngine;
import dtm.ide.adapter.ConditionalBreakpointSupport;
import dtm.ide.adapter.CoverageSupport;
import dtm.ide.adapter.DebugSupport;
import dtm.ide.adapter.DiagnosticsEngine;
import dtm.ide.adapter.FileWatchSupport;
import dtm.ide.adapter.LanguageServerManager;
import dtm.ide.adapter.NavigationSupport;
import dtm.ide.adapter.NavigationViews;
import dtm.ide.adapter.ProblemsSupport;
import dtm.ide.adapter.RunLauncher;
import dtm.ide.adapter.SourceActionSupport;
import dtm.ide.adapter.SpringSupport;
import dtm.ide.adapter.SwingDesignerHost;
import dtm.ide.adapter.TodoPanelHost;
import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.editor.EmbeddedCodeEditorSettings;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.project.IdeProjectFileWatcher;
import dtm.ide.api.project.diagnostics.IdeProblem;
import dtm.ide.api.project.diagnostics.ProblemsActionHandle;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.MavenPluginGoals;
import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.debug.JavaDebugSession;
import dtm.ide.deps.DependencyService;
import dtm.ide.deps.PomProperties;
import dtm.ide.editor.AutoCompleteIdleTrigger;
import dtm.ide.editor.BuildFileCompletionProvider;
import dtm.ide.editor.JavaFastCompletionProvider;
import dtm.ide.editor.JavaSnippetCompletionProvider;
import dtm.ide.index.JavaLexicalIndex;
import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.MainClassScanner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.test.JavaTestRunner;
import dtm.ide.ui.DependencyManagerPanel;
import dtm.ide.ui.JavaBuildToolsPanel;
import dtm.ide.ui.JavaProjectStructurePanel;
import dtm.ide.ui.JavaTestExplorerPanel;
import dtm.ide.ui.JavaTodoPanel;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.popup.ModernComponentDialog;
import dtm.stools.component.popup.ModernDialog;
import dtm.stools.component.popup.ModernInputDialog;

import java.awt.Dimension;
import java.awt.Point;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

final class JavaIdeAdapterHost implements AdapterHost {

    private final JavaIdeAdapter adapter;

    JavaIdeAdapterHost(JavaIdeAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public boolean debugActive() {
        return adapter.debugActive.get();
    }

    @Override
    public JavaLanguageServer interactiveServerFor(Path file) {
        return adapter.interactiveServerFor(file);
    }

    @Override
    public JavaProjectDescriptor descriptor() {
        return adapter.descriptor;
    }

    @Override
    public JavaSnippetCompletionProvider snippets() {
        return adapter.snippets;
    }

    @Override
    public Path projectRoot() {
        return adapter.projectRoot;
    }

    @Override
    public List<String> todoMarkers() {
        return settings().getTodoMarkers();
    }

    @Override
    public PluginTaskExecutor background() {
        return adapter.background;
    }

    @Override
    public String registerTodoPanel(JavaTodoPanel panel, Icon icon) {
        return icon == null
                ? adapter.registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"),
                        ToolIconType.INFO, panel)
                : adapter.registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"), icon, panel);
    }

    @Override
    public void requestOpenToolPanel(String panelId) {
        adapter.requestOpenToolPanel(panelId);
    }

    @Override
    public void openAt(Path file, int line, int column) {
        adapter.openAt(file, line, column);
    }

    @Override
    public JdkService jdkService() {
        return adapter.ensureJdkService();
    }

    @Override
    public JdkInstallation projectJdk() {
        return adapter.projectJdk;
    }

    @Override
    public void projectJdk(JdkInstallation jdk) {
        adapter.projectJdk = jdk;
    }

    @Override
    public void descriptor(JavaProjectDescriptor value) {
        adapter.descriptor = value;
    }

    @Override
    public long lifecycleTicket() {
        return adapter.lifecycle.get();
    }

    @Override
    public long nextLifecycleTicket() {
        return adapter.lifecycle.incrementAndGet();
    }

    @Override
    public boolean isCurrent(long ticket, Path root) {
        return adapter.current(ticket, root);
    }

    @Override
    public <T> T timed(String label, Supplier<T> operation) {
        return adapter.timed(label, operation);
    }

    @Override
    public void rebuildLexicalIndex(JavaProjectDescriptor value) {
        adapter.lexicalIndex.rebuild(value);
    }

    @Override
    public void refreshRunButtonsForCurrentFile() {
        adapter.runLauncher.refreshRunButtonsForCurrentFile();
    }

    @Override
    public void reloadBuildToolsPanel() {
        if (adapter.buildToolsPanel != null) {
            adapter.buildToolsPanel.reload();
        }
    }

    @Override
    public void reloadStructurePanel() {
        JavaProjectStructurePanel panel = adapter.structurePanel;
        if (panel != null) {
            panel.reload();
        }
    }

    @Override
    public void setStatusBarText(String value) {
        adapter.setStatusBarText(value);
    }

    @Override
    public DownloadProgressListener progressListener() {
        return adapter.progressListener();
    }

    @Override
    public void resolveProjectJdk(long ticket, Path root) {
        adapter.resolveProjectJdk(ticket, root);
    }

    @Override
    public JavaPluginSettings settings() {
        return adapter.settings();
    }

    @Override
    public JComponent dependencyLibrariesView() {
        DependencyService dependencies = adapter.ensureDependencyService();
        if (dependencies == null || !dependencies.isSupported()) {
            return null;
        }
        DependencyManagerPanel panel = adapter.dependencyPanel;
        if (panel == null) {
            panel = new DependencyManagerPanel(adapter.dependencyManagerHost(),
                    adapter::createModernDialogBuilder);
            adapter.dependencyPanel = panel;
        }
        return panel;
    }

    @Override
    public void syncProject() {
        adapter.syncProject();
    }

    @Override
    public void requestRefreshCodeLenses(Path file) {
        adapter.requestRefreshCodeLenses(file);
    }

    @Override
    public void refreshDiagnosticsOfOpenJavaEditors() {
        adapter.javaEditors.keySet().forEach(adapter::requestRefreshDiagnostics);
    }

    @Override
    public BuildSystem buildSystem() {
        return adapter.ensureBuildSystem();
    }

    @Override
    public java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module) {
        return adapter.runtimeClasspathOf(build, module);
    }

    @Override
    public String registerBottomPanel(String title, Icon icon, JComponent panel) {
        return adapter.registerToolPanel(DockRegion.BOTTOM, title, icon, panel);
    }

    @Override
    public void openWebBrowser(String url) {
        adapter.openWebBrowser(url);
    }

    @Override
    public MavenPluginGoals pluginGoals() {
        return adapter.pluginGoals;
    }

    @Override
    public void clearPluginRepositoryPath() {
        adapter.pluginRepositoryPath = null;
    }

    @Override
    public BuildSystem currentBuildSystem() {
        return adapter.buildSystem;
    }

    @Override
    public List<RunConfigurationData> requestRunConfigurations() {
        return adapter.requestRunConfigurations();
    }

    @Override
    public RunConfigurationData requestSaveRunConfiguration(RunConfigurationData configuration) {
        return adapter.requestSaveRunConfiguration(configuration);
    }

    @Override
    public boolean requestRemoveRunConfiguration(String id) {
        return adapter.requestRemoveRunConfiguration(id);
    }

    @Override
    public Set<Path> migratedBuildRunConfigurations() {
        return adapter.migratedBuildRunConfigurations;
    }

    @Override
    public void showPopup(PlatformPopupBuilder popup) {
        adapter.showPopup(popup);
    }

    @Override
    public BuildToolDebugListener openBuildDebugListener(JavaModule module, Runnable cancelProcess)
            throws IOException {
        return adapter.runLauncher.openBuildDebugListener(module, cancelProcess);
    }

    @Override
    public OutputPanelHandle requestOutputPanel(String title, OutputPanelOptions options) {
        return adapter.requestOutputPanel(title, options);
    }

    @Override
    public void writeOutput(OutputPanelHandle panel, String line) {
        adapter.buildSupport.writeOutput(panel, line);
    }

    @Override
    public void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure) {
        adapter.buildSupport.publishBuildDiagnostics(result, revealOnFailure);
    }

    @Override
    public JavaBuildToolsPanel buildToolsPanel() {
        return adapter.buildToolsPanel;
    }

    @Override
    public JavaLanguageServer languageServer() {
        return adapter.jdtLs;
    }

    @Override
    public void requestShowRunOutput() {
        adapter.requestShowRunOutput();
    }

    @Override
    public JavaTestRunner newTestRunner(JavaProjectDescriptor current, BuildSystem build) {
        return adapter.newTestRunner(current, build);
    }

    @Override
    public void publishTestDiagnostics(BuildResult result) {
        adapter.buildSupport.publishTestDiagnostics(result);
    }

    @Override
    public void clearCoverage() {
        adapter.coverageSupport.clear();
    }

    @Override
    public CoverageProvisioner coverageProvisioner() {
        return adapter.coverageSupport.provisioner();
    }

    @Override
    public void readCoverage(Path execFile, JavaProjectDescriptor current) {
        adapter.coverageSupport.readCoverage(execFile, current);
    }

    @Override
    public AtomicReference<Runnable> pendingTestDebug() {
        return adapter.pendingTestDebug;
    }

    @Override
    public JavaTestRunner activeTestRunner() {
        return adapter.activeTestRunner.get();
    }

    @Override
    public JavaModule mostSpecificModule(Collection<JavaModule> modules, Path file) {
        return JavaIdeAdapter.mostSpecificModule(modules, file);
    }

    @Override
    public BuildToolDebugListener openBuildDebugListener(JavaModule module, Runnable cancelProcess,
                                                         Runnable onAttach) throws IOException {
        return adapter.runLauncher.openBuildDebugListener(module, cancelProcess, onAttach);
    }

    @Override
    public void showProgress(String id, String message, boolean cancellable, Runnable onCancel) {
        adapter.showProgress(id, message, cancellable, onCancel);
    }

    @Override
    public void hideProgress(String id) {
        adapter.hideProgress(id);
    }

    @Override
    public void requestSetRunButtonLoading(boolean loading) {
        adapter.requestSetRunButtonLoading(loading);
    }

    @Override
    public void requestSetRunButtonRunning(boolean running) {
        adapter.requestSetRunButtonRunning(running);
    }

    @Override
    public void warmUpDebugAdapter() {
        adapter.debugSupport.warmUpDebugAdapter();
    }

    @Override
    public boolean hasRunningProcess() {
        return adapter.runLauncher.hasRunningProcess();
    }

    @Override
    public JdkService currentJdkService() {
        return adapter.jdkService;
    }

    @Override
    public JavaTestExplorerPanel testPanel() {
        return adapter.testPanel;
    }

    @Override
    public Map<Path, IdeEditorContext> javaEditors() {
        return adapter.javaEditors;
    }

    @Override
    public void requestOpenFile(Path file) {
        adapter.requestOpenFile(file);
    }

    @Override
    public IdeEditorContext activeJavaEditor() {
        return adapter.activeJavaEditor;
    }

    @Override
    public void requestCodeEditorAutocomplete() {
        adapter.requestCodeEditorAutocomplete();
    }

    @Override
    public SpringSupport spring() {
        return adapter.spring;
    }

    @Override
    public BuildFileCompletionProvider buildFileCompletion() {
        return adapter.buildFileCompletion;
    }

    @Override
    public JavaFastCompletionProvider fastCompletion() {
        return adapter.fastCompletion;
    }

    @Override
    public JavaLexicalIndex lexicalIndex() {
        return adapter.lexicalIndex;
    }

    @Override
    public boolean isSpringNavigationEnabled() {
        return adapter.isSpringNavigationEnabled();
    }

    @Override
    public BuildProblemsCoordinator problems() {
        return adapter.problems;
    }

    @Override
    public Resource resource() {
        return adapter.getResource();
    }

    @Override
    public void requestRefreshDiagnostics(Path file) {
        adapter.requestRefreshDiagnostics(file);
    }

    @Override
    public void requestShowCodeActions(Path file) {
        adapter.requestShowCodeActions(file);
    }

    @Override
    public JavaLanguageServer runningServerFor(Path file) {
        return adapter.runningServerFor(file);
    }

    @Override
    public void showProgress(String id, String message) {
        adapter.showProgress(id, message);
    }

    @Override
    public void updateProgress(String id, String message, int percent, boolean cancellable, Runnable onCancel) {
        adapter.updateProgress(id, message, percent, cancellable, onCancel);
    }

    @Override
    public <T> ModernComponentDialog.ModernComponentDialogBuilder<T> createModernComponentDialogBuilder() {
        return adapter.createModernComponentDialogBuilder();
    }

    @Override
    public ModernDialog.ModernDialogBuilder createModernDialogBuilder() {
        return adapter.createModernDialogBuilder();
    }

    @Override
    public void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                IdeEditorContext context, Point screen, Kind kind) {
        adapter.showUsagesPopup(locations, currentFile, currentText, context, screen, kind);
    }

    @Override
    public IdeEditorContext editorContextFor(Path file) {
        return adapter.editorContextFor(file);
    }

    @Override
    public IdeEditorContext getEditor(Path file) {
        return adapter.getEditor(file);
    }

    @Override
    public CoverageSupport coverage() {
        return adapter.coverageSupport;
    }

    @Override
    public String testPanelId() {
        return adapter.testPanelId;
    }

    @Override
    public void navigateToLocation(Location location, Path path) {
        adapter.navigateToLocation(location, path);
    }

    @Override
    public void openSpringExplorer() {
        adapter.openSpringExplorer();
    }

    @Override
    public Optional<MainClassScanner.MainClass> currentMainClass() {
        return adapter.runLauncher.currentMainClass();
    }

    @Override
    public RunProcessHandle requestRunConfigurationExecution(String id, boolean debug) {
        return adapter.requestRunConfigurationExecution(id, debug);
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
        return adapter.launch(configuration, context);
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData configuration, RunExecutionContext context) {
        return adapter.launchDebug(configuration, context);
    }

    @Override
    public JavaModule moduleContaining(JavaProjectDescriptor current, Path file, boolean testRoots) {
        return JavaIdeAdapter.moduleContaining(current, file, testRoots);
    }

    @Override
    public void ensureTestPanel() {
        adapter.ensureTestPanel();
    }

    @Override
    public List<Location> uniqueLocations(List<Location> locations) {
        return JavaIdeAdapter.uniqueLocations(locations);
    }

    @Override
    public CodeLensSupport codeLens() {
        return adapter.codeLensSupport;
    }

    @Override
    public ClassFileSupport classFileUris() {
        return adapter.classFileUris;
    }

    @Override
    public AtomicLong navigationTicket() {
        return adapter.navigationTicket;
    }

    @Override
    public JavaEditorRegistry editors() {
        return adapter.editors;
    }

    @Override
    public IdeEditorContext getEditor(Path file, boolean focus, Consumer<IdeEditorContext> onReady) {
        return adapter.getEditor(file, focus, onReady);
    }

    @Override
    public boolean closeCenterTab(String id) {
        return adapter.closeCenterTab(id);
    }

    @Override
    public CodeEditor requestEmbeddedCodeEditor(String name, String source,
                                                EmbeddedCodeEditorSettings settings) {
        return adapter.requestEmbeddedCodeEditor(name, source, settings);
    }

    @Override
    public String openCenterTab(String id, String title, JComponent component, boolean closable) {
        return adapter.openCenterTab(id, title, component, closable);
    }

    @Override
    public boolean isDebugPaused() {
        return adapter.debugSupport.isDebugPaused();
    }

    @Override
    public DiagnosticsEngine diagnostics() {
        return adapter.diagnosticsEngine;
    }

    @Override
    public IdeEditorContext getEditor(Path file, boolean focus) {
        return adapter.getEditor(file, focus);
    }

    @Override
    public <T> ModernComponentDialog.ModernComponentDialogBuilder<T> createModernComponentDialogBuilder(
            Class<T> type) {
        return adapter.createModernComponentDialogBuilder(type);
    }

    @Override
    public JavaDebugSession debugSession() {
        return adapter.debugSupport.session();
    }

    @Override
    public Object monitor() {
        return adapter;
    }

    @Override
    public AtomicBoolean debugActiveFlag() {
        return adapter.debugActive;
    }

    @Override
    public NavigationViews navigationViews() {
        return adapter.navigationViews;
    }

    @Override
    public void requestSetHotReloadButtonVisible(boolean visible) {
        adapter.requestSetHotReloadButtonVisible(visible);
    }

    @Override
    public void requestSetHotReloadButtonEnabled(boolean enabled) {
        adapter.requestSetHotReloadButtonEnabled(enabled);
    }

    @Override
    public JavaLanguageServer ensureLanguageServer() {
        return adapter.ensureLanguageServer();
    }

    @Override
    public String registerToolPanel(DockRegion region, String title, ToolIconType icon, JComponent component,
                                    Dimension size) {
        return adapter.registerToolPanel(region, title, icon, component, size);
    }

    @Override
    public void closeDebugRelay() {
        adapter.runLauncher.closeDebugRelay();
    }

    @Override
    public boolean supportsHotReloadForSelection() {
        return adapter.runLauncher.supportsHotReloadForSelection();
    }

    @Override
    public void requestRepaintCodeEditor(Path file) {
        adapter.requestRepaintCodeEditor(file);
    }

    @Override
    public void requestRepaintCodeEditorBreakpointLine(Path file) {
        adapter.requestRepaintCodeEditorBreakpointLine(file);
    }

    @Override
    public DebugSupport debug() {
        return adapter.debugSupport;
    }

    @Override
    public AtomicBoolean buildRunning() {
        return adapter.buildRunning;
    }

    @Override
    public boolean isUnloaded() {
        return adapter.unloaded;
    }

    @Override
    public String buildProgressAction(BuildSystem.BuildAction action) {
        return adapter.buildSupport.buildProgressAction(action);
    }

    @Override
    public void updateProgress(String id, String message, int percent) {
        adapter.updateProgress(id, message, percent);
    }

    @Override
    public List<RunBreakpointData> requestWorkspaceBreakpoints() {
        return adapter.requestWorkspaceBreakpoints();
    }

    @Override
    public void requestSetRunButtonEnabled(boolean enabled) {
        adapter.requestSetRunButtonEnabled(enabled);
    }

    @Override
    public void requestSetDebugButtonEnabled(boolean enabled) {
        adapter.requestSetDebugButtonEnabled(enabled);
    }

    @Override
    public void requestSetCoverageButtonVisible(boolean visible) {
        adapter.requestSetCoverageButtonVisible(visible);
    }

    @Override
    public void requestSetCoverageButtonEnabled(boolean enabled) {
        adapter.requestSetCoverageButtonEnabled(enabled);
    }

    @Override
    public void runBuild(BuildSystem.BuildAction action, String title, JavaModule module) {
        adapter.buildSupport.runBuild(action, title, module);
    }

    @Override
    public void openDependencyManager() {
        adapter.openDependencyManager();
    }

    @Override
    public void clearCaches() {
        adapter.clearCaches();
    }

    @Override
    public void requestProjectTreeViewRefresh() {
        adapter.requestProjectTreeViewRefresh();
    }

    @Override
    public ModernInputDialog.ModernInputDialogBuilder createModernInputDialogBuilder() {
        return adapter.createModernInputDialogBuilder();
    }

    @Override
    public String readCurrentText(Path file) {
        return adapter.readCurrentText(file);
    }

    @Override
    public void onBuildFileChanged(Path file) {
        adapter.onBuildFileChanged(file);
    }

    @Override
    public JavaFileChangeRouter fileChangeRouter() {
        return adapter.fileWatch.fileChangeRouter();
    }

    @Override
    public void requestProjectTreeRevealCreated(Path file) {
        adapter.requestProjectTreeRevealCreated(file);
    }

    @Override
    public SourceActionSupport sourceActions() {
        return adapter.sourceActions;
    }

    @Override
    public AtomicLong navigationRequestTicket() {
        return adapter.navigationRequestTicket;
    }

    @Override
    public PomProperties pomProperties() {
        return adapter.pomProperties;
    }

    @Override
    public boolean isIndexing(Path file) {
        return adapter.isIndexing(file);
    }

    @Override
    public IdeEditorContext liveEditorFor(Path file) {
        return adapter.liveEditorFor(file);
    }

    @Override
    public void classFileUris(ClassFileSupport support) {
        adapter.classFileUris = support;
    }

    @Override
    public void languageServer(JavaLanguageServer server) {
        adapter.jdtLs = server;
    }

    @Override
    public void observeSyncWork(boolean active) {
        adapter.projectSync.observeSyncWork(active);
    }

    @Override
    public DownloadObserver resolveDownloadObserver() {
        return adapter.resolveDownloadObserver();
    }

    @Override
    public ConditionalBreakpointSupport conditionalBreakpoints() {
        return adapter.conditionalBreakpoints;
    }

    @Override
    public CompletionEngine completionEngine() {
        return adapter.completionEngine;
    }

    @Override
    public void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
        adapter.finishDiagnosticReanalysis(ticket, root, successful);
    }

    @Override
    public void refreshProblemsPanel() {
        adapter.refreshProblemsPanel();
    }

    @Override
    public void createNotification(NotificationContext context) {
        adapter.createNotification(context);
    }

    @Override
    public void requestRefreshInlayHints(Path file) {
        adapter.requestRefreshInlayHints(file);
    }

    @Override
    public void requestRefreshSemanticTokens(Path file) {
        adapter.requestRefreshSemanticTokens(file);
    }

    @Override
    public LanguageServerManager languageServerManager() {
        return adapter.languageServers;
    }

    @Override
    public void requestOpenProblemsPanel() {
        adapter.requestOpenProblemsPanel();
    }

    @Override
    public void publishProblems(String owner, Collection<IdeProblem> problems) {
        adapter.publishProblems(owner, problems);
    }

    @Override
    public ProblemsActionHandle registerProblemsAction(String owner, String label, String tooltip, Icon icon,
                                                       Runnable action) {
        return adapter.registerProblemsAction(owner, label, tooltip, icon, action);
    }

    @Override
    public IdeProjectFileWatcher projectFileWatcher() {
        return adapter.getProjectFileWatcher();
    }

    @Override
    public TodoPanelHost todoSupport() {
        return adapter.todoSupport;
    }

    @Override
    public Map<Path, String> diskBaseline() {
        return adapter.diskBaseline;
    }

    @Override
    public void requestJavaTreeIconRefresh(Path file) {
        adapter.requestJavaTreeIconRefresh(file);
    }

    @Override
    public FileWatchSupport fileWatch() {
        return adapter.fileWatch;
    }

    @Override
    public AutoCompleteIdleTrigger autoCompleteIdle() {
        return adapter.autoCompleteIdle;
    }

    @Override
    public void activeJavaEditor(IdeEditorContext editor) {
        adapter.activeJavaEditor = editor;
    }

    @Override
    public Map<Path, String> lastEditorContents() {
        return adapter.lastEditorContents;
    }

    @Override
    public RunLauncher runLauncher() {
        return adapter.runLauncher;
    }

    @Override
    public SwingDesignerHost swingDesignerHost() {
        return adapter.swingDesignerHost;
    }

    @Override
    public NavigationSupport navigationSupport() {
        return adapter.navigationSupport;
    }

    @Override
    public ProblemsSupport problemsSupport() {
        return adapter.problemsSupport;
    }

    @Override
    public void openBuildTools() {
        adapter.openBuildTools();
    }

    @Override
    public void restartLanguageServer() {
        adapter.restartLanguageServer();
    }

    @Override
    public void openProjectStructure() {
        adapter.openProjectStructure();
    }

    @Override
    public void openJdkManager() {
        adapter.openJdkManager();
    }

    @Override
    public void openTestExplorer() {
        adapter.openTestExplorer();
    }
}
