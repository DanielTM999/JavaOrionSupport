package dtm.ide;

import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.search.GlobalSearchQuery;
import dtm.ide.api.search.GlobalSearchResult;
import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.project.editor.FileAssociated;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.api.project.editor.IdeCallHierarchyContext;
import dtm.ide.api.extension.editor.EmbeddedCodeEditorSettings;
import dtm.ide.api.project.editor.IdeCodeLensContext;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeDiagnosticHoverPolicy;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeDocumentSymbolContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeGhostTextContext;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.stools.component.popup.ModernComponentDialog;
import dtm.ide.adapter.AdapterFailures;
import dtm.ide.adapter.AdapterHost;
import dtm.ide.adapter.BuildSupport;
import dtm.ide.adapter.BuildToolsSupport;
import dtm.ide.adapter.CodeLensSupport;
import dtm.ide.adapter.CompletionEngine;
import dtm.ide.adapter.DebugSupport;
import dtm.ide.adapter.ConditionalBreakpointSupport;
import dtm.ide.adapter.DiagnosticsEngine;
import dtm.ide.adapter.EditorEventsSupport;
import dtm.ide.adapter.FileWatchSupport;
import dtm.ide.adapter.RenameSupport;
import dtm.ide.adapter.RunLauncher;
import dtm.ide.adapter.SafeDeleteSupport;
import dtm.ide.adapter.SourceActionSupport;
import dtm.ide.adapter.UiThreads;
import dtm.ide.adapter.CoverageSupport;
import dtm.ide.adapter.GhostTextSupport;
import dtm.ide.adapter.NavigationSupport;
import dtm.ide.adapter.NavigationViews;
import dtm.ide.adapter.PathRenameSupport;
import dtm.ide.adapter.PathTransferHost;
import dtm.ide.adapter.ProblemsSupport;
import dtm.ide.adapter.ProjectSyncSupport;
import dtm.ide.adapter.ProjectTreeMenuSupport;
import dtm.ide.adapter.JdkManagerSupport;
import dtm.ide.adapter.LanguageServerManager;
import dtm.ide.adapter.ProjectStructureSupport;
import dtm.ide.adapter.SpringSupport;
import dtm.ide.adapter.SwingDesignerHost;
import dtm.ide.adapter.TestExplorerSupport;
import dtm.ide.adapter.TodoPanelHost;
import dtm.ide.api.project.editor.IdeInlayHintContext;
import dtm.ide.api.project.editor.IdeRenameContext;
import dtm.ide.api.project.editor.IdeRenamePolicy;
import dtm.ide.api.project.editor.IdeRenamePrepareContext;
import dtm.ide.api.project.editor.IdeRenamePreparation;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.editor.IdeSemanticTokensContext;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeWordCaretContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.ConditionalBreakpointContext;
import dtm.ide.api.project.editor.ConditionalBreakpointDialogView;
import dtm.ide.api.project.editor.NativeEditorType;
import dtm.ide.api.project.editor.view.IdeEditorViewModesBuilder;
import dtm.ide.api.project.tree.PathRenameDecision;
import dtm.ide.api.project.tree.PathTransferDecision;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.api.project.tree.ProjectTreeIgnoreRule;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.project.diagnostics.IdeProblem;
import dtm.ide.api.project.diagnostics.ProblemsActionHandle;
import dtm.ide.build.ClasspathValidation;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildRunConfigurations;
import dtm.ide.build.BuildSystem;
import dtm.ide.swingdesigner.SwingDesignerSupport;
import dtm.ide.build.GradleBuildService;
import dtm.ide.build.MavenPluginGoals;
import dtm.ide.build.MavenBuildService;
import dtm.ide.build.BuildSystems;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.deps.DependencyService;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.deps.OsvClient;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.PomProperties;
import dtm.ide.deps.MavenLocalRepositoryResolver;
import dtm.ide.editor.AutoCompleteIdleTrigger;
import dtm.stools.component.panels.editor.code.ghost.GhostTextSuggestion;
import dtm.ide.editor.JavaSnippetCompletionProvider;
import dtm.ide.editor.BuildFileCompletionProvider;
import dtm.ide.editor.JavaFastCompletionProvider;
import dtm.ide.index.JavaLexicalIndex;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.editor.theme.JavaEditorTheme;
import dtm.ide.lsp.LombokSupport;
import dtm.ide.lsp.LombokSupportStatus;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.debug.BreakpointChanges;
import dtm.ide.debug.ConditionEditorSession;
import dtm.ide.debug.JavaDebugSession;
import dtm.ide.inspection.InspectionSuppressionStore;
import dtm.ide.spring.SpringNavigation;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.ide.project.JavaModule;
import dtm.ide.run.JavaRunSupport;
import dtm.ide.run.form.RunFormChoicesLoader;
import dtm.ide.run.MainClassScanner;
import dtm.ide.api.project.IdeProjectFileWatcher;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectSources;
import dtm.ide.refactor.JavaPathTransferRefactoring;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.settings.JdtBuildMode;
import dtm.ide.settings.JavaSettingsPage;
import dtm.ide.test.JavaTestRunner;
import dtm.ide.test.JUnitPlatformLauncher;
import dtm.ide.ui.DependencyManagerPanel;
import dtm.ide.ui.JavaTestExplorerPanel;
import dtm.ide.ui.JavaBuildToolsPanel;
import dtm.ide.ui.JavaProjectStructurePanel;
import dtm.ide.ui.JavaTodoPanel;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaProjectTreeIcons;
import dtm.ide.ui.JavaDebugValuePopup;
import dtm.ide.ui.JdkManagerPanel;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.stools.component.menu.bar.tree.MenuNode;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.popup.ModernInputDialog;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.ide.api.project.editor.IdeSelectionRangeContext;
import dtm.ide.api.project.editor.SelectionRangeContext;
import dtm.ide.editor.TextOffsets;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationProvider;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.popup.ModernDialog;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.request_actions.http.download.core.DownloadObserver;
import lombok.extern.slf4j.Slf4j;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
@Singleton
@PluginReference(id = "java-ide-adapter")
public class JavaIdeAdapter extends IdeAdapter {

    private static final long SLOW_OPERATION_THRESHOLD_MS = 100;
    private static final long SELECTION_RANGE_TIMEOUT_MS = 1_000;
    private static final String JDK_TAB_ID = "javaJdkManager";

    private static final String NAVIGATION_PROGRESS_ID = "javaNavigation";
    private static final String DEPENDENCIES_TAB_ID = "javaDependencies";
    private final class HostBridge implements AdapterHost {
        @Override
        public boolean debugActive() {
            return debugActive.get();
        }

        @Override
        public JavaLanguageServer interactiveServerFor(Path file) {
            return JavaIdeAdapter.this.interactiveServerFor(file);
        }

        @Override
        public JavaProjectDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public JavaSnippetCompletionProvider snippets() {
            return snippets;
        }

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public List<String> todoMarkers() {
            return settings().getTodoMarkers();
        }

        @Override
        public PluginTaskExecutor background() {
            return background;
        }

        @Override
        public String registerTodoPanel(JavaTodoPanel panel, Icon icon) {
            return icon == null
                    ? JavaIdeAdapter.this.registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"),
                            ToolIconType.INFO, panel)
                    : JavaIdeAdapter.this.registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"), icon, panel);
        }

        @Override
        public void requestOpenToolPanel(String panelId) {
            JavaIdeAdapter.this.requestOpenToolPanel(panelId);
        }

        @Override
        public void openAt(Path file, int line, int column) {
            JavaIdeAdapter.this.openAt(file, line, column);
        }

        @Override
        public JdkService jdkService() {
            return ensureJdkService();
        }

        @Override
        public JdkInstallation projectJdk() {
            return projectJdk;
        }

        @Override
        public void projectJdk(JdkInstallation jdk) {
            projectJdk = jdk;
        }

        @Override
        public void descriptor(JavaProjectDescriptor value) {
            descriptor = value;
        }

        @Override
        public long lifecycleTicket() {
            return lifecycle.get();
        }

        @Override
        public long nextLifecycleTicket() {
            return lifecycle.incrementAndGet();
        }

        @Override
        public boolean isCurrent(long ticket, Path root) {
            return current(ticket, root);
        }

        @Override
        public <T> T timed(String label, Supplier<T> operation) {
            return JavaIdeAdapter.this.timed(label, operation);
        }

        @Override
        public void rebuildLexicalIndex(JavaProjectDescriptor value) {
            lexicalIndex.rebuild(value);
        }

        @Override
        public void refreshRunButtonsForCurrentFile() {
            runLauncher.refreshRunButtonsForCurrentFile();
        }

        @Override
        public void reloadBuildToolsPanel() {
            if (buildToolsPanel != null) {
                buildToolsPanel.reload();
            }
        }

        @Override
        public void reloadStructurePanel() {
            JavaProjectStructurePanel panel = structurePanel;
            if (panel != null) {
                panel.reload();
            }
        }

        @Override
        public void setStatusBarText(String value) {
            JavaIdeAdapter.this.setStatusBarText(value);
        }

        @Override
        public DownloadProgressListener progressListener() {
            return JavaIdeAdapter.this.progressListener();
        }

        @Override
        public void resolveProjectJdk(long ticket, Path root) {
            JavaIdeAdapter.this.resolveProjectJdk(ticket, root);
        }

        @Override
        public JavaPluginSettings settings() {
            return JavaIdeAdapter.this.settings();
        }

        @Override
        public JComponent dependencyLibrariesView() {
            DependencyService dependencies = ensureDependencyService();
            if (dependencies == null || !dependencies.isSupported()) {
                return null;
            }
            DependencyManagerPanel panel = dependencyPanel;
            if (panel == null) {
                panel = new DependencyManagerPanel(dependencyManagerHost(),
                        JavaIdeAdapter.this::createModernDialogBuilder);
                dependencyPanel = panel;
            }
            return panel;
        }

        @Override
        public void syncProject() {
            JavaIdeAdapter.this.syncProject();
        }

        @Override
        public void requestRefreshCodeLenses(Path file) {
            JavaIdeAdapter.this.requestRefreshCodeLenses(file);
        }

        @Override
        public void refreshDiagnosticsOfOpenJavaEditors() {
            javaEditors.keySet().forEach(JavaIdeAdapter.this::requestRefreshDiagnostics);
        }

        @Override
        public BuildSystem buildSystem() {
            return ensureBuildSystem();
        }

        @Override
        public java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module) {
            return JavaIdeAdapter.this.runtimeClasspathOf(build, module);
        }

        @Override
        public String registerBottomPanel(String title, Icon icon, JComponent panel) {
            return JavaIdeAdapter.this.registerToolPanel(DockRegion.BOTTOM, title, icon, panel);
        }

        @Override
        public void openWebBrowser(String url) {
            JavaIdeAdapter.this.openWebBrowser(url);
        }

        @Override
        public MavenPluginGoals pluginGoals() {
            return pluginGoals;
        }

        @Override
        public void clearPluginRepositoryPath() {
            pluginRepositoryPath = null;
        }

        @Override
        public BuildSystem currentBuildSystem() {
            return buildSystem;
        }

        @Override
        public List<RunConfigurationData> requestRunConfigurations() {
            return JavaIdeAdapter.this.requestRunConfigurations();
        }

        @Override
        public RunConfigurationData requestSaveRunConfiguration(RunConfigurationData configuration) {
            return JavaIdeAdapter.this.requestSaveRunConfiguration(configuration);
        }

        @Override
        public boolean requestRemoveRunConfiguration(String id) {
            return JavaIdeAdapter.this.requestRemoveRunConfiguration(id);
        }

        @Override
        public Set<Path> migratedBuildRunConfigurations() {
            return migratedBuildRunConfigurations;
        }

        @Override
        public void showPopup(PlatformPopupBuilder popup) {
            JavaIdeAdapter.this.showPopup(popup);
        }

        @Override
        public BuildToolDebugListener openBuildDebugListener(JavaModule module, Runnable cancelProcess)
                throws IOException {
            return runLauncher.openBuildDebugListener(module, cancelProcess);
        }

        @Override
        public OutputPanelHandle requestOutputPanel(String title, OutputPanelOptions options) {
            return JavaIdeAdapter.this.requestOutputPanel(title, options);
        }

        @Override
        public void writeOutput(OutputPanelHandle panel, String line) {
            buildSupport.writeOutput(panel, line);
        }

        @Override
        public void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure) {
            buildSupport.publishBuildDiagnostics(result, revealOnFailure);
        }

        @Override
        public JavaBuildToolsPanel buildToolsPanel() {
            return buildToolsPanel;
        }

        @Override
        public JavaLanguageServer languageServer() {
            return jdtLs;
        }

        @Override
        public void requestShowRunOutput() {
            JavaIdeAdapter.this.requestShowRunOutput();
        }

        @Override
        public JavaTestRunner newTestRunner(JavaProjectDescriptor current, BuildSystem build) {
            return JavaIdeAdapter.this.newTestRunner(current, build);
        }

        @Override
        public void publishTestDiagnostics(BuildResult result) {
            buildSupport.publishTestDiagnostics(result);
        }

        @Override
        public void clearCoverage() {
            coverageSupport.clear();
        }

        @Override
        public CoverageProvisioner coverageProvisioner() {
            return coverageSupport.provisioner();
        }

        @Override
        public void readCoverage(Path execFile, JavaProjectDescriptor current) {
            coverageSupport.readCoverage(execFile, current);
        }

        @Override
        public AtomicReference<Runnable> pendingTestDebug() {
            return pendingTestDebug;
        }

        @Override
        public JavaTestRunner activeTestRunner() {
            return activeTestRunner.get();
        }

        @Override
        public JavaModule mostSpecificModule(Collection<JavaModule> modules, Path file) {
            return JavaIdeAdapter.mostSpecificModule(modules, file);
        }

        @Override
        public BuildToolDebugListener openBuildDebugListener(JavaModule module, Runnable cancelProcess,
                                                             Runnable onAttach) throws IOException {
            return runLauncher.openBuildDebugListener(module, cancelProcess, onAttach);
        }

        @Override
        public void showProgress(String id, String message, boolean cancellable, Runnable onCancel) {
            JavaIdeAdapter.this.showProgress(id, message, cancellable, onCancel);
        }

        @Override
        public void hideProgress(String id) {
            JavaIdeAdapter.this.hideProgress(id);
        }

        @Override
        public void requestSetRunButtonLoading(boolean loading) {
            JavaIdeAdapter.this.requestSetRunButtonLoading(loading);
        }

        @Override
        public void requestSetRunButtonRunning(boolean running) {
            JavaIdeAdapter.this.requestSetRunButtonRunning(running);
        }

        @Override
        public void warmUpDebugAdapter() {
            debugSupport.warmUpDebugAdapter();
        }

        @Override
        public boolean hasRunningProcess() {
            return runLauncher.hasRunningProcess();
        }

        @Override
        public JdkService currentJdkService() {
            return jdkService;
        }

        @Override
        public JavaTestExplorerPanel testPanel() {
            return testPanel;
        }

        @Override
        public Map<Path, IdeEditorContext> javaEditors() {
            return javaEditors;
        }

        @Override
        public void requestOpenFile(Path file) {
            JavaIdeAdapter.this.requestOpenFile(file);
        }

        @Override
        public IdeEditorContext activeJavaEditor() {
            return activeJavaEditor;
        }

        @Override
        public void requestCodeEditorAutocomplete() {
            JavaIdeAdapter.this.requestCodeEditorAutocomplete();
        }

        @Override
        public SpringSupport spring() {
            return spring;
        }

        @Override
        public BuildFileCompletionProvider buildFileCompletion() {
            return buildFileCompletion;
        }

        @Override
        public JavaFastCompletionProvider fastCompletion() {
            return fastCompletion;
        }

        @Override
        public JavaLexicalIndex lexicalIndex() {
            return lexicalIndex;
        }

        @Override
        public boolean isSpringNavigationEnabled() {
            return JavaIdeAdapter.this.isSpringNavigationEnabled();
        }

        @Override
        public BuildProblemsCoordinator problems() {
            return problems;
        }

        @Override
        public Resource resource() {
            return getResource();
        }

        @Override
        public void requestRefreshDiagnostics(Path file) {
            JavaIdeAdapter.this.requestRefreshDiagnostics(file);
        }

        @Override
        public void requestShowCodeActions(Path file) {
            JavaIdeAdapter.this.requestShowCodeActions(file);
        }

        @Override
        public JavaLanguageServer runningServerFor(Path file) {
            return JavaIdeAdapter.this.runningServerFor(file);
        }

        @Override
        public void showProgress(String id, String message) {
            JavaIdeAdapter.this.showProgress(id, message);
        }

        @Override
        public void updateProgress(String id, String message, int percent, boolean cancellable, Runnable onCancel) {
            JavaIdeAdapter.this.updateProgress(id, message, percent, cancellable, onCancel);
        }

        @Override
        public <T> ModernComponentDialog.ModernComponentDialogBuilder<T> createModernComponentDialogBuilder() {
            return JavaIdeAdapter.this.createModernComponentDialogBuilder();
        }

        @Override
        public ModernDialog.ModernDialogBuilder createModernDialogBuilder() {
            return JavaIdeAdapter.this.createModernDialogBuilder();
        }

        @Override
        public void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                    IdeEditorContext context, Point screen, Kind kind) {
            JavaIdeAdapter.this.showUsagesPopup(locations, currentFile, currentText, context, screen, kind);
        }

        @Override
        public IdeEditorContext editorContextFor(Path file) {
            return JavaIdeAdapter.this.editorContextFor(file);
        }

        @Override
        public IdeEditorContext getEditor(Path file) {
            return JavaIdeAdapter.this.getEditor(file);
        }

        @Override
        public CoverageSupport coverage() {
            return coverageSupport;
        }

        @Override
        public String testPanelId() {
            return testPanelId;
        }

        @Override
        public void navigateToLocation(Location location, Path path) {
            JavaIdeAdapter.this.navigateToLocation(location, path);
        }

        @Override
        public void openSpringExplorer() {
            JavaIdeAdapter.this.openSpringExplorer();
        }

        @Override
        public Optional<MainClassScanner.MainClass> currentMainClass() {
            return runLauncher.currentMainClass();
        }

        @Override
        public RunProcessHandle requestRunConfigurationExecution(String id, boolean debug) {
            return JavaIdeAdapter.this.requestRunConfigurationExecution(id, debug);
        }

        @Override
        public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
            return JavaIdeAdapter.this.launch(configuration, context);
        }

        @Override
        public RunProcessHandle launchDebug(RunConfigurationData configuration, RunExecutionContext context) {
            return JavaIdeAdapter.this.launchDebug(configuration, context);
        }

        @Override
        public JavaModule moduleContaining(JavaProjectDescriptor current, Path file, boolean testRoots) {
            return JavaIdeAdapter.moduleContaining(current, file, testRoots);
        }

        @Override
        public void ensureTestPanel() {
            JavaIdeAdapter.this.ensureTestPanel();
        }

        @Override
        public List<Location> uniqueLocations(List<Location> locations) {
            return JavaIdeAdapter.uniqueLocations(locations);
        }

        @Override
        public CodeLensSupport codeLens() {
            return codeLensSupport;
        }

        @Override
        public ClassFileSupport classFileUris() {
            return classFileUris;
        }

        @Override
        public AtomicLong navigationTicket() {
            return navigationTicket;
        }

        @Override
        public JavaEditorRegistry editors() {
            return editors;
        }

        @Override
        public IdeEditorContext getEditor(Path file, boolean focus, Consumer<IdeEditorContext> onReady) {
            return JavaIdeAdapter.this.getEditor(file, focus, onReady);
        }

        @Override
        public boolean closeCenterTab(String id) {
            return JavaIdeAdapter.this.closeCenterTab(id);
        }

        @Override
        public CodeEditor requestEmbeddedCodeEditor(String name, String source,
                                                    EmbeddedCodeEditorSettings settings) {
            return JavaIdeAdapter.this.requestEmbeddedCodeEditor(name, source, settings);
        }

        @Override
        public String openCenterTab(String id, String title, JComponent component, boolean closable) {
            return JavaIdeAdapter.this.openCenterTab(id, title, component, closable);
        }

        @Override
        public boolean isDebugPaused() {
            return debugSupport.isDebugPaused();
        }

        @Override
        public DiagnosticsEngine diagnostics() {
            return diagnosticsEngine;
        }

        @Override
        public IdeEditorContext getEditor(Path file, boolean focus) {
            return JavaIdeAdapter.this.getEditor(file, focus);
        }

        @Override
        public <T> ModernComponentDialog.ModernComponentDialogBuilder<T> createModernComponentDialogBuilder(
                Class<T> type) {
            return JavaIdeAdapter.this.createModernComponentDialogBuilder(type);
        }

        @Override
        public JavaDebugSession debugSession() {
            return debugSupport.session();
        }

        @Override
        public Object monitor() {
            return JavaIdeAdapter.this;
        }

        @Override
        public AtomicBoolean debugActiveFlag() {
            return debugActive;
        }

        @Override
        public NavigationViews navigationViews() {
            return navigationViews;
        }

        @Override
        public void requestSetHotReloadButtonVisible(boolean visible) {
            JavaIdeAdapter.this.requestSetHotReloadButtonVisible(visible);
        }

        @Override
        public void requestSetHotReloadButtonEnabled(boolean enabled) {
            JavaIdeAdapter.this.requestSetHotReloadButtonEnabled(enabled);
        }

        @Override
        public JavaLanguageServer ensureLanguageServer() {
            return JavaIdeAdapter.this.ensureLanguageServer();
        }

        @Override
        public String registerToolPanel(DockRegion region, String title, ToolIconType icon, JComponent component,
                                        Dimension size) {
            return JavaIdeAdapter.this.registerToolPanel(region, title, icon, component, size);
        }

        @Override
        public void closeDebugRelay() {
            runLauncher.closeDebugRelay();
        }

        @Override
        public boolean supportsHotReloadForSelection() {
            return runLauncher.supportsHotReloadForSelection();
        }

        @Override
        public void requestRepaintCodeEditor(Path file) {
            JavaIdeAdapter.this.requestRepaintCodeEditor(file);
        }

        @Override
        public void requestRepaintCodeEditorBreakpointLine(Path file) {
            JavaIdeAdapter.this.requestRepaintCodeEditorBreakpointLine(file);
        }

        @Override
        public DebugSupport debug() {
            return debugSupport;
        }

        @Override
        public AtomicBoolean buildRunning() {
            return buildRunning;
        }

        @Override
        public boolean isUnloaded() {
            return unloaded;
        }

        @Override
        public String buildProgressAction(BuildSystem.BuildAction action) {
            return buildSupport.buildProgressAction(action);
        }

        @Override
        public void updateProgress(String id, String message, int percent) {
            JavaIdeAdapter.this.updateProgress(id, message, percent);
        }

        @Override
        public List<RunBreakpointData> requestWorkspaceBreakpoints() {
            return JavaIdeAdapter.this.requestWorkspaceBreakpoints();
        }

        @Override
        public void requestSetRunButtonEnabled(boolean enabled) {
            JavaIdeAdapter.this.requestSetRunButtonEnabled(enabled);
        }

        @Override
        public void requestSetDebugButtonEnabled(boolean enabled) {
            JavaIdeAdapter.this.requestSetDebugButtonEnabled(enabled);
        }

        @Override
        public void requestSetCoverageButtonVisible(boolean visible) {
            JavaIdeAdapter.this.requestSetCoverageButtonVisible(visible);
        }

        @Override
        public void requestSetCoverageButtonEnabled(boolean enabled) {
            JavaIdeAdapter.this.requestSetCoverageButtonEnabled(enabled);
        }

        @Override
        public void runBuild(BuildSystem.BuildAction action, String title, JavaModule module) {
            buildSupport.runBuild(action, title, module);
        }

        @Override
        public void openDependencyManager() {
            JavaIdeAdapter.this.openDependencyManager();
        }

        @Override
        public void clearCaches() {
            JavaIdeAdapter.this.clearCaches();
        }

        @Override
        public void requestProjectTreeViewRefresh() {
            JavaIdeAdapter.this.requestProjectTreeViewRefresh();
        }

        @Override
        public ModernInputDialog.ModernInputDialogBuilder createModernInputDialogBuilder() {
            return JavaIdeAdapter.this.createModernInputDialogBuilder();
        }

        @Override
        public String readCurrentText(Path file) {
            return JavaIdeAdapter.this.readCurrentText(file);
        }

        @Override
        public void onBuildFileChanged(Path file) {
            JavaIdeAdapter.this.onBuildFileChanged(file);
        }

        @Override
        public JavaFileChangeRouter fileChangeRouter() {
            return fileWatch.fileChangeRouter();
        }

        @Override
        public void requestProjectTreeRevealCreated(Path file) {
            JavaIdeAdapter.this.requestProjectTreeRevealCreated(file);
        }

        @Override
        public SourceActionSupport sourceActions() {
            return sourceActions;
        }

        @Override
        public AtomicLong navigationRequestTicket() {
            return navigationRequestTicket;
        }

        @Override
        public PomProperties pomProperties() {
            return pomProperties;
        }

        @Override
        public boolean isIndexing(Path file) {
            return JavaIdeAdapter.this.isIndexing(file);
        }

        @Override
        public IdeEditorContext liveEditorFor(Path file) {
            return JavaIdeAdapter.this.liveEditorFor(file);
        }

        @Override
        public void classFileUris(ClassFileSupport support) {
            classFileUris = support;
        }

        @Override
        public void languageServer(JavaLanguageServer server) {
            jdtLs = server;
        }

        @Override
        public void observeSyncWork(boolean active) {
            projectSync.observeSyncWork(active);
        }

        @Override
        public DownloadObserver resolveDownloadObserver() {
            return JavaIdeAdapter.this.resolveDownloadObserver();
        }

        @Override
        public ConditionalBreakpointSupport conditionalBreakpoints() {
            return conditionalBreakpoints;
        }

        @Override
        public CompletionEngine completionEngine() {
            return completionEngine;
        }

        @Override
        public void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
            JavaIdeAdapter.this.finishDiagnosticReanalysis(ticket, root, successful);
        }

        @Override
        public void refreshProblemsPanel() {
            JavaIdeAdapter.this.refreshProblemsPanel();
        }

        @Override
        public void createNotification(NotificationContext context) {
            JavaIdeAdapter.this.createNotification(context);
        }

        @Override
        public void requestRefreshInlayHints(Path file) {
            JavaIdeAdapter.this.requestRefreshInlayHints(file);
        }

        @Override
        public void requestRefreshSemanticTokens(Path file) {
            JavaIdeAdapter.this.requestRefreshSemanticTokens(file);
        }

        @Override
        public LanguageServerManager languageServerManager() {
            return languageServers;
        }

        @Override
        public void requestOpenProblemsPanel() {
            JavaIdeAdapter.this.requestOpenProblemsPanel();
        }

        @Override
        public void publishProblems(String owner, Collection<IdeProblem> problems) {
            JavaIdeAdapter.this.publishProblems(owner, problems);
        }

        @Override
        public ProblemsActionHandle registerProblemsAction(String owner, String label, String tooltip, Icon icon,
                                                           Runnable action) {
            return JavaIdeAdapter.this.registerProblemsAction(owner, label, tooltip, icon, action);
        }

        @Override
        public IdeProjectFileWatcher projectFileWatcher() {
            return getProjectFileWatcher();
        }

        @Override
        public TodoPanelHost todoSupport() {
            return todoSupport;
        }

        @Override
        public Map<Path, String> diskBaseline() {
            return diskBaseline;
        }

        @Override
        public void requestJavaTreeIconRefresh(Path file) {
            JavaIdeAdapter.this.requestJavaTreeIconRefresh(file);
        }

        @Override
        public FileWatchSupport fileWatch() {
            return fileWatch;
        }

        @Override
        public AutoCompleteIdleTrigger autoCompleteIdle() {
            return autoCompleteIdle;
        }

        @Override
        public void activeJavaEditor(IdeEditorContext editor) {
            activeJavaEditor = editor;
        }

        @Override
        public Map<Path, String> lastEditorContents() {
            return lastEditorContents;
        }

        @Override
        public RunLauncher runLauncher() {
            return runLauncher;
        }

        @Override
        public SwingDesignerHost swingDesignerHost() {
            return swingDesignerHost;
        }

        @Override
        public NavigationSupport navigationSupport() {
            return navigationSupport;
        }
    }

    private final JavaEditorRegistry editors = new JavaEditorRegistry();
    private final MavenCentralClient mavenCentral = new MavenCentralClient();
    private final BuildProblemsCoordinator problems = new BuildProblemsCoordinator();
    private final JavaSnippetCompletionProvider snippets = new JavaSnippetCompletionProvider();
    private final AdapterHost adapterHost = new HostBridge();
    private final SpringSupport spring = new SpringSupport(adapterHost);
    private final CoverageSupport coverageSupport = new CoverageSupport(adapterHost);
    private final CompletionEngine completionEngine = new CompletionEngine(adapterHost);
    private final DiagnosticsEngine diagnosticsEngine = new DiagnosticsEngine(adapterHost);
    private final RenameSupport renameSupport = new RenameSupport(adapterHost);
    private final SafeDeleteSupport safeDeleteSupport = new SafeDeleteSupport(adapterHost);
    private final CodeLensSupport codeLensSupport = new CodeLensSupport(adapterHost);
    private final NavigationViews navigationViews = new NavigationViews(adapterHost);
    private final SourceActionSupport sourceActions = new SourceActionSupport(adapterHost);
    private final ConditionalBreakpointSupport conditionalBreakpoints = new ConditionalBreakpointSupport(adapterHost);
    private final DebugSupport debugSupport = new DebugSupport(adapterHost);
    private final RunLauncher runLauncher = new RunLauncher(adapterHost);
    private final ProjectTreeMenuSupport projectTreeMenu = new ProjectTreeMenuSupport(adapterHost);
    private final SwingDesignerHost swingDesignerHost = new SwingDesignerHost(adapterHost);
    private final NavigationSupport navigationSupport = new NavigationSupport(adapterHost);
    private final LanguageServerManager languageServers = new LanguageServerManager(adapterHost);
    private final ProblemsSupport problemsSupport = new ProblemsSupport(adapterHost);
    private final BuildSupport buildSupport = new BuildSupport(adapterHost);
    private final ProjectSyncSupport projectSync = new ProjectSyncSupport(adapterHost);
    private final FileWatchSupport fileWatch = new FileWatchSupport(adapterHost);
    private final PathRenameSupport pathRenames = new PathRenameSupport(adapterHost);
    private final EditorEventsSupport editorEvents = new EditorEventsSupport(adapterHost);
    private final GhostTextSupport ghostTextSupport = new GhostTextSupport(adapterHost);
    private final JavaLexicalIndex lexicalIndex = new JavaLexicalIndex();
    private final JavaFastCompletionProvider fastCompletion =
            new JavaFastCompletionProvider(lexicalIndex);
    private final PomProperties pomProperties = new PomProperties(this::pomLocalRepository);
    private final BuildFileCompletionProvider buildFileCompletion =
            new BuildFileCompletionProvider(new EditorDependencyCatalog(), pomProperties);
    private final EditorTheme theme = new JavaEditorTheme(() -> requestEditorThemeConfig("java"));
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicLong navigationTicket = new AtomicLong();
    private final AtomicLong navigationRequestTicket = new AtomicLong();
    private final AtomicBoolean buildRunning = new AtomicBoolean();
    private final AtomicBoolean debugActive = new AtomicBoolean();
    private final PluginTaskExecutor background =
            new PluginTaskExecutor("java-orion-support");
    private final AutoCompleteIdleTrigger autoCompleteIdle = new AutoCompleteIdleTrigger(
            CompletionEngine.AUTO_COMPLETE_IDLE_DELAY_MS,
            (task, delay) -> background.schedule(task, delay, TimeUnit.MILLISECONDS),
            completionEngine::isIdleCompletionEligible,
            completionEngine::isIdleCompletionReady,
            completionEngine::currentIdleCaret,
            completionEngine::fireIdleCompletion
    );

    private final Object lifecycleLock = new Object();
    private volatile Path projectRoot;
    private volatile JavaProjectDescriptor descriptor;
    private final JavaPathTransferRefactoring pathTransfers = new JavaPathTransferRefactoring(new PathTransferHost(adapterHost));
    private volatile IdeProjectContext projectContext;
    private volatile JdkService jdkService;
    private volatile JdkInstallation projectJdk;
    private volatile JdkManagerPanel jdkManagerPanel;
    private static final String STRUCTURE_TAB_ID = "javaProjectStructure";

    private volatile JavaLanguageServer jdtLs;
    private volatile ClassFileSupport classFileUris;
    private volatile boolean unloaded;
    private volatile BuildSystem buildSystem;
    private volatile DependencyService dependencyService;
    private volatile DependencyManagerCoordinator dependencyCoordinator;
    private volatile DependencyManagerPanel dependencyPanel;
    private final AtomicReference<JavaTestRunner> activeTestRunner = new AtomicReference<>();
    private final AtomicReference<Runnable> pendingTestDebug = new AtomicReference<>();
    private volatile JavaTestExplorerPanel testPanel;
    private volatile String testPanelId;
    private final MavenPluginGoals pluginGoals = new MavenPluginGoals(this::pluginRepository);
    private volatile Path pluginRepositoryRoot;
    private volatile Path pluginRepositoryPath;
    private final TodoPanelHost todoSupport = new TodoPanelHost(adapterHost);
    private final AtomicLong treeIconRefreshTicket = new AtomicLong();
    private final AtomicBoolean treeIconRefreshAll = new AtomicBoolean();
    private final Set<Path> treeIconRefreshPaths = ConcurrentHashMap.newKeySet();
    private final Set<Path> migratedBuildRunConfigurations = ConcurrentHashMap.newKeySet();
    private volatile JavaProjectStructurePanel structurePanel;
    private volatile JavaBuildToolsPanel buildToolsPanel;
    private volatile String buildToolsPanelId;
    private volatile JavaPluginSettings settings;
    private volatile IdeEditorContext activeJavaEditor;
    private final Map<Path, IdeEditorContext> javaEditors = new ConcurrentHashMap<>();
    private final Map<Path, String> diskBaseline = new ConcurrentHashMap<>();
    private final Map<Path, String> lastEditorContents = new ConcurrentHashMap<>();
    private volatile List<RunConfigurationData> staticRunConfigurations = List.of();

    @Override
    public boolean supports(Path projectPath) {
        return timed("supports", () -> JavaProjectConventions.supports(projectPath));
    }

    @Override
    public String getProjectType() {
        return JavaProjectConventions.PROJECT_TYPE;
    }

    @Override
    public boolean handlesPath(Path path) {
        return JavaProjectConventions.handlesPath(path);
    }

    @Override
    public void onAdapterSelected(IdeProjectContext context) {
        log.info("onAdapterSelected chamado na thread {}", Thread.currentThread().getName());
        bind(context);
    }

    @Override
    public void onProjectOpened(IdeProjectContext context) {
        log.info("onProjectOpened chamado na thread {}", Thread.currentThread().getName());
        background.submit(JavaLocalScope::warmUp);
        bind(context);
    }

    @Override
    public void onProjectClosed(IdeProjectContext context) {
        teardownProject(false);
        projectContext = null;
    }

    @Override
    public void onProjectSwitched(IdeProjectContext oldContext, IdeProjectContext newContext) {
        teardownProject(true);
        projectContext = null;
        if (newContext != null) {
            onProjectOpened(newContext);
        }
    }

    private void teardownProject(boolean switching) {
        Path closingRoot;
        synchronized (lifecycleLock) {
            lifecycle.incrementAndGet();
            closingRoot = projectRoot;
            descriptor = null;
            projectRoot = null;
            staticRunConfigurations = List.of();
        }
        background.cancelPending();
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null) {
            lsp.resetProjectState();
            if (switching && closingRoot != null) {
                lsp.stopAsyncIfBoundTo(closingRoot);
            } else {
                lsp.stopAsync();
            }
        }
        dtm.ide.run.OwnedRunProcesses.shutdownAll();
        projectJdk = null;
        buildSystem = null;
        dependencyService = null;
        runLauncher.clearRunSupport();
        closeSwingDesigner();
        runLauncher.clearRunBuildProgress();
        buildSupport.cancelStartupBuild();
        hideProgress(BuildSupport.STARTUP_BUILD_PROGRESS_ID);
        debugSupport.closeDebugSession();
        activeJavaEditor = null;
        runLauncher.clearSelectedRunConfig();
        runLauncher.clearMainClassMemo();
        fileWatch.unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        if (coordinator != null) {
            coordinator.resetLocalRepository();
        }
        lexicalIndex.clear();
        spring.index().clear();
        todoSupport.reset();
        RunFormChoicesLoader choicesLoader = runLauncher.currentRunFormChoicesLoader();
        if (choicesLoader != null) {
            choicesLoader.invalidate();
        }
        projectSync.clearPending();
        spring.resetConfiguration();
        problems.clearAll();
        problemsSupport.reanalysisRunning().set(false);
        refreshProblemsPanel();
        coverageSupport.clear();
        coverageSupport.detachAllGutters();
        javaEditors.clear();
        diskBaseline.clear();
        lastEditorContents.clear();
        languageServers.progress().set(0);
        hideProgress(LanguageServerManager.LSP_PROGRESS_ID);
        projectSync.reset();
        JavaBuildToolsPanel toolsPanel = buildToolsPanel;
        if (toolsPanel != null) {
            toolsPanel.setSyncing(false);
        }
        SwingUtilities.invokeLater(diagnosticsEngine::hideCodeActionLamp);
        clearStatusBarText();
    }

    @Override
    public void onUnload() {
        unloaded = true;
        lifecycle.incrementAndGet();
        buildSupport.cancelStartupBuild();
        background.close();
        closeSwingDesigner();
        dtm.ide.run.OwnedRunProcesses.shutdownAll();
        debugSupport.startGeneration().incrementAndGet();
        runLauncher.closeDebugRelay();
        JavaDebugSession session = debugSupport.session();
        debugSupport.clearSession();
        debugActive.set(false);
        debugSupport.edtWatchdog().stop();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException error) {
                log.warn("Falha ao fechar a sessao de debug no unload", error);
            }
        }
        debugSupport.runDebuggeeTerminator(debugSupport.takeDebuggeeTerminator());
        ProblemsActionHandle action = problemsSupport.takeClearBuildAction();
        if (action != null) {
            action.unregister();
        }
        JavaLanguageServer lsp;
        synchronized (this) {
            lsp = jdtLs;
            jdtLs = null;
        }
        if (lsp != null) {
            try {
                lsp.shutdown();
            } catch (RuntimeException error) {
                log.warn("Falha ao encerrar o JDT LS no unload", error);
            }
        }
        fileWatch.unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        dependencyCoordinator = null;
        if (coordinator != null) {
            coordinator.close();
        }
        autoCompleteIdle.cancel();
        for (ConditionEditorSession conditionSession : List.copyOf(conditionalBreakpoints.sessions().values())) {
            try {
                conditionSession.close();
            } catch (RuntimeException error) {
                log.debug("Falha ao fechar editor de condicao no unload: {}", error.getMessage());
            }
        }
        conditionalBreakpoints.sessions().clear();
        JavaDebugValuePopup popup = debugSupport.currentValuePopup();
        if (popup != null) {
            SwingUtilities.invokeLater(popup::hide);
        }
        unregisterToolPanels();
        spring.index().shutdown();
        lexicalIndex.shutdown();
        dtm.ide.build.JavacDaemons.shutdownAll();
    }

    private void unregisterToolPanels() {
        for (String panelId : java.util.Arrays.asList(
                debugSupport.panelId(), testPanelId, todoSupport.panelId(), buildToolsPanelId, spring.panelId())) {
            if (panelId == null) {
                continue;
            }
            try {
                unregisterToolPanel(panelId);
            } catch (RuntimeException error) {
                log.debug("Falha ao remover painel {} no unload: {}", panelId, error.getMessage());
            }
        }
        debugSupport.clearPanelId();
        testPanelId = null;
        todoSupport.clearPanelId();
        buildToolsPanelId = null;
        spring.clearPanelId();
    }

    @Override
    public void clearCaches() {
        log.info("clearCaches chamado na thread {}", Thread.currentThread().getName());
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        long ticket;
        synchronized (lifecycleLock) {
            ticket = lifecycle.incrementAndGet();
        }
        buildSystem = null;
        dependencyService = null;
        problems.clearAll();
        refreshProblemsPanel();
        coverageSupport.clear();
        JavaLanguageServer lsp = jdtLs;

        background.submit(() -> {
            JavaProjectDescriptor described = timed("describe(clearCaches)",
                    () -> JavaProjectConventions.describe(root));
            if (!publishDescriptor(ticket, root, described)) {
                return;
            }
            JdkService jdks = jdkService;
            if (jdks != null) {
                jdks.refresh();
            }
            if (lsp != null) {
                lsp.stop();
            }
            if (!current(ticket, root) || described == null) {
                return;
            }
            JavaProjectSources sources = JavaProjectSources.collect(described);
            lexicalIndex.rebuild(described, sources);
            if (settings().getLanguageServerMode().startsServer()) {
                ensureLanguageServer();
            }
            resolveProjectJdk(ticket, root);
            spring.setup(ticket, root, sources);
            SwingUtilities.invokeLater(() -> refreshUiAfterDescribe(ticket, root));
        });
    }

    private void bind(IdeProjectContext context) {
        long started = System.nanoTime();
        String callerThread = Thread.currentThread().getName();
        log.info("bind iniciado na thread {}", callerThread);
        Path nextRoot = context == null ? null
                : context.getProjectPath().map(JavaProjectConventions::normalize).orElse(null);
        if (nextRoot != null && nextRoot.equals(projectRoot) && descriptor != null) {
            this.projectContext = context;
            runLauncher.refreshRunButtonsForCurrentFile();
            logSlowBind(started, callerThread);
            return;
        }
        Path previousRoot = projectRoot;
        if (previousRoot != null && !previousRoot.equals(nextRoot)) {
            log.info("Projeto trocado sem fechamento: {} -> {}", previousRoot, nextRoot);
            teardownProject(true);
        }
        this.projectContext = context;
        long ticket;
        synchronized (lifecycleLock) {
            this.projectRoot = nextRoot;
            this.descriptor = null;
            this.staticRunConfigurations = List.of();
            ticket = lifecycle.incrementAndGet();
        }
        problems.clearAll();
        refreshProblemsPanel();
        Path root = nextRoot;
        if (root == null) {
            runLauncher.refreshRunButtonsForCurrentFile();
            logSlowBind(started, callerThread);
            return;
        }
        fileWatch.registerFileWatcher();
        requestJavaTreeIconRefresh(null);

        if (settings().getLanguageServerMode().startsServer()) {
            String loading = text("status.startingLsp",
                    "Java: IntelliSense local pronto; iniciando analise semantica...");
            languageServers.progress().set(1);
            showProgress(LanguageServerManager.LSP_PROGRESS_ID, loading);
            updateProgress(LanguageServerManager.LSP_PROGRESS_ID, loading, 1);
        }
        background.submit(() -> {
            try {
                JavaProjectDescriptor described = timed("describe(bind)",
                        () -> JavaProjectConventions.describe(root));
                if (!publishDescriptor(ticket, root, described)) {
                    return;
                }
                if (described == null) {
                    SwingUtilities.invokeLater(() -> {
                        if (current(ticket, root)) {
                            hideProgress(LanguageServerManager.LSP_PROGRESS_ID);
                            runLauncher.refreshRunButtonsForCurrentFile();
                        }
                    });
                    return;
                }

                log.info("Projeto Java vinculado: {} ({}, {} modulo(s), spring={})",
                        root, described.kind().key(), described.modules().size(), described.spring());
                JavaProjectSources sources = JavaProjectSources.collect(described);
                lexicalIndex.rebuild(described, sources);
                if (settings().getLanguageServerMode().startsServer()) {
                    ensureLanguageServer();
                }
                resolveProjectJdk(ticket, root);
                spring.setup(ticket, root, sources);
                SwingUtilities.invokeLater(() -> refreshUiAfterDescribe(ticket, root));
            } finally {
                logSlowBind(started, callerThread);
            }
        });
    }

    private void refreshUiAfterDescribe(long ticket, Path root) {
        if (!current(ticket, root) || descriptor == null) {
            return;
        }
        if (descriptor.isMaven() || descriptor.isGradle()) {
            ensureBuildToolsPanel();
        }
        ensureTestPanel();
        runLauncher.refreshRunButtonsForCurrentFile();
        requestJavaTreeIconRefresh(null);
    }

    private void requestJavaTreeIconRefresh(Path file) {
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        if (file == null) {
            treeIconRefreshAll.set(true);
        } else {
            treeIconRefreshPaths.add(file.toAbsolutePath().normalize());
        }
        long generation = lifecycle.get();
        long ticket = treeIconRefreshTicket.incrementAndGet();
        background.schedule(() -> {
            if (ticket != treeIconRefreshTicket.get() || !current(generation, root)) {
                return;
            }
            boolean all = treeIconRefreshAll.getAndSet(false);
            List<Path> paths = new ArrayList<>();
            for (Path path : List.copyOf(treeIconRefreshPaths)) {
                if (treeIconRefreshPaths.remove(path)) {
                    paths.add(path);
                }
            }
            if (all || !paths.isEmpty()) {
                requestProjectTreeNodeIconRefresh(all ? List.of() : paths);
            }
        }, 350, TimeUnit.MILLISECONDS);
    }

    private <T> T timed(String label, Supplier<T> operation) {
        long started = System.nanoTime();
        try {
            return operation.get();
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (elapsedMs > SLOW_OPERATION_THRESHOLD_MS) {
                log.info("{} levou {} ms na thread {}", label, elapsedMs,
                        Thread.currentThread().getName());
            }
        }
    }

    private void logSlowBind(long started, String callerThread) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (elapsedMs > SLOW_OPERATION_THRESHOLD_MS) {
            log.info("bind levou {} ms (chamador={}, conclusao={})", elapsedMs,
                    callerThread, Thread.currentThread().getName());
        }
    }

    private void resolveProjectJdk(long ticket, Path root) {
        JavaProjectDescriptor current = descriptor;
        if (current == null || !current(ticket, root)) {
            return;
        }
        background.submit(() -> {
            JdkService.JdkResolution resolution = ensureJdkService()
                    .provisionForProject(current, settings().getDefaultJdkVersion(), progressListener());
            if (!current(ticket, root)) {
                return;
            }
            projectJdk = resolution.installation();
            if (!resolution.resolved()) {
                hideProgress(LanguageServerManager.LSP_PROGRESS_ID);
                setStatusBarText(text("status.noJdk",
                        "Java: nenhuma JDK encontrada - instale uma pelo JDK Manager"));
                finishDiagnosticReanalysis(ticket, root, false);
                promptForProjectJdk(ticket, root, resolution);
                return;
            }
            if (resolution.outcome() == JdkService.JdkOutcome.FALLBACK) {
                setStatusBarText("Java: " + resolution.installation().displayName() + " - "
                        + text("status.jdkMismatch", "o projeto pede a JDK")
                        + " " + resolution.requestedMajor());
            }
            startLanguageServer(ticket, root, resolution.installation());
            background.submit(() -> buildSupport.startupBuild(ticket, root));
        });
    }

    private void promptForProjectJdk(long ticket, Path root, JdkService.JdkResolution resolution) {
        SwingUtilities.invokeLater(() -> {
            if (!current(ticket, root)) {
                return;
            }
            openJdkManager();
            JdkManagerPanel panel = jdkManagerPanel;
            if (panel != null) {
                panel.promptForMissingJdk(resolution.requestedMajor(), resolution.failure());
            }
        });
    }

    private void startLanguageServer(long ticket, Path root, JdkInstallation jdk) {
        languageServers.startLanguageServer(ticket, root, jdk);
    }

    private JavaLanguageServer ensureLanguageServer() {
        return languageServers.ensureLanguageServer();
    }

    private boolean applyLombokAgent(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        return languageServers.applyLombokAgent(lsp, current);
    }

    public LombokSupportStatus getLombokSupportStatus() {
        return languageServers.getLombokSupportStatus();
    }

    public void setLombokSupportListener(LombokSupport.Listener listener) {
        languageServers.setLombokSupportListener(listener);
    }

    private void requestJdtLsProjectConfigurationRefresh() {
        languageServers.requestJdtLsProjectConfigurationRefresh();
    }

    private void requestJdtLsProjectConfigurationRefresh(JavaLanguageServer lsp) {
        languageServers.requestJdtLsProjectConfigurationRefresh(lsp);
    }

    private boolean current(long ticket, Path root) {
        return lifecycle.get() == ticket && root != null && root.equals(projectRoot);
    }

    private boolean publishDescriptor(long ticket, Path root, JavaProjectDescriptor described) {
        List<RunConfigurationData> configurations = buildStaticRunConfigurations(described);
        synchronized (lifecycleLock) {
            if (!current(ticket, root)) {
                return false;
            }
            descriptor = described;
            staticRunConfigurations = configurations;
            runLauncher.clearMainClassMemo();
            return true;
        }
    }

    @Override
    public Collection<FileAssociated> getFileAssociations() {
        if (!settings().isBuildFileCompletion()) {
            return List.of(
                    new FileAssociated("xml", NativeEditorType.XML),
                    new FileAssociated("json", NativeEditorType.JSON));
        }
        return List.of(new FileAssociated("json", NativeEditorType.JSON));
    }

    @Override
    public TokenizerCodeEditorProvider resolveSyntaxHighlightTokenizer(Path filePath) {
        return editors.tokenizerFor(filePath);
    }

    @Override
    public CompletableFuture<List<FoldRange>> resolveFoldRanges(Path filePath, String text, long documentVersion) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !JavaProjectConventions.isJava(filePath) || !lsp.supportsFoldingRanges()) {
            return CompletableFuture.completedFuture(null);
        }
        return lsp.foldingRangesAsync(filePath, text);
    }

    @Override
    public Collection<FoldRule> resolveFoldRules(Path filePath) {
        return editors.foldRulesFor(filePath);
    }

    @Override
    public EditorTheme getEditorTheme() {
        return theme;
    }

    @Override
    public void configureEditor(IdeEditorContext editorContext) {
        if (editorContext == null || !editors.handles(editorContext.filePath())) {
            return;
        }
        editorContext.setFoldingEnabled(true);
        editorContext.setAutoCompleteOnTyping(!debugActive.get());
        editorEvents.configureGhostText(editorContext);
        coverageSupport.installGutter(editorContext);
        editorEvents.installTestGutter(editorContext);
        editorEvents.installCodeActionCommandHandler(editorContext);
        editorEvents.installJavaShortcuts(editorContext);
        if (JavaProjectConventions.isMavenPom(editorContext.filePath())
                && settings().isBuildFileCompletion()) {
            dependencyManagerHost().warmLocalCatalog();
        }
    }

    @Override
    public Set<Character> getCompletionTriggerCharacters() {
        return completionEngine.getCompletionTriggerCharacters();
    }

    @Override
    public Set<Character> getCompletionTriggerCharacters(Path filePath) {
        return completionEngine.getCompletionTriggerCharacters(filePath);
    }

    @Override
    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        return completionEngine.shouldAutoTriggerCompletion(context);
    }

    @Override
    public boolean isAutoCompletionOnTypingEnabled() {
        return completionEngine.isAutoCompletionOnTypingEnabled();
    }

    @Override
    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        return completionEngine.getCompletionSuggestions(context);
    }

    @Override
    public CompletableFuture<List<AutoCompleteItem>> getCompletionSuggestionsAsync(
            IdeCompletionContext context) {
        return completionEngine.getCompletionSuggestionsAsync(context);
    }

    @Override
    public CompletableFuture<AutoCompleteItem> resolveCompletionItem(AutoCompleteItem item) {
        return completionEngine.resolveCompletionItem(item);
    }

    @Override
    public String getGhostText(IdeGhostTextContext context) {
        GhostTextSuggestion suggestion = getGhostSuggestion(context);
        return suggestion == null ? null : suggestion.text();
    }

    @Override
    public GhostTextSuggestion getGhostSuggestion(IdeGhostTextContext context) {
        return ghostTextSupport.getGhostSuggestion(context);
    }

    @Override
    public boolean supportsIncrementalDiagnostics() {
        return true;
    }

    @Override
    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context, boolean incremental,
                                                 Collection<Diagnostic> diagnostics) {
        return diagnosticsEngine.getDiagnostics(context, incremental, diagnostics);
    }

    @Override
    public List<Range> getSelectionRanges(IdeSelectionRangeContext context) {
        if (context == null) {
            return null;
        }
        try {
            List<Range> chain = selectionChain(context.filePath(), context.text(), context.offset())
                    .get(SELECTION_RANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return chain.isEmpty() ? null : chain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            log.debug("Extend selection indisponivel: {}", failure.getMessage());
            return null;
        }
    }

    @Override
    public CompletableFuture<List<int[]>> getSelectionRanges(SelectionRangeContext context) {
        if (context == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return selectionChain(context.filePath(), context.text(), context.offset())
                .thenApply(chain -> TextOffsets.offsets(context.text(), chain));
    }

    private CompletableFuture<List<Range>> selectionChain(Path filePath, String text, int offset) {
        JavaLanguageServer lsp = interactiveServerFor(filePath);
        if (lsp == null || !JavaProjectConventions.isJava(filePath)) {
            return CompletableFuture.completedFuture(List.of());
        }
        Position position = TextOffsets.position(text, offset);
        return lsp.selectionRangesAsync(filePath, text, position.line(), position.col());
    }

    @Override
    public CompletableFuture<HoverInfo> getHoverAsync(IdeHoverContext context) {
        if (context == null || debugSupport.isDebugPaused() || SpringConfigSupport.isConfigFile(context.filePath())) {
            return CompletableFuture.completedFuture(getHover(context));
        }
        JavaLanguageServer lsp = interactiveServerFor(context.filePath());
        if (lsp == null) {
            return CompletableFuture.completedFuture(null);
        }
        HoverInfo diagnostic = lsp.diagnosticHover(context.filePath(), context.line(), context.col());
        return diagnostic != null ? CompletableFuture.completedFuture(diagnostic)
                : lsp.hoverAsync(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        if (context == null) {
            return null;
        }
        if (debugSupport.isDebugPaused()) {
            return null;
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            return SpringConfigSupport.hover(spring.metadata(), context.filePath(),
                    context.text(), context.line());
        }
        JavaLanguageServer lsp = interactiveServerFor(context.filePath());
        if (lsp == null) {
            return null;
        }
        HoverInfo diagnostic = lsp.diagnosticHover(
                context.filePath(), context.line(), context.col());
        return diagnostic != null ? diagnostic
                : lsp.hover(context.filePath(), context.text(), context.line(), context.col());
    }

    static String diskBaselineFor(Path file, String editorText) {
        return FileWatchSupport.diskBaselineFor(file, editorText);
    }

    @Override
    public PathRenameDecision beforePathRename(Path path) {
        return pathRenames.beforePathRename(path);
    }

    @Override
    public void onPathRenamed(Path oldPath, Path newPath) {
        pathRenames.onPathRenamed(oldPath, newPath);
    }

    @Override
    public void onPathDeleted(Path path) {
        pathRenames.onPathDeleted(path);
    }

    private String readCurrentText(Path file) {
        IdeEditorContext editor = editorContextFor(file);
        if (editor != null) {
            String text = onUi(editor::getText);
            if (text != null) {
                return text;
            }
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Falha ao ler {}: {}", file, e.getMessage());
            return null;
        }
    }

    @Override
    public PathTransferDecision beforePathTransfer(PathTransferRequest request) {
        return pathTransfers.before(request);
    }

    @Override
    public IdeWorkspaceEdit afterPathTransfer(PathTransferRequest request) {
        return pathTransfers.after(request);
    }

    @Override
    public boolean canDeletePaths(List<Path> paths) {
        return safeDeleteSupport.canDeletePaths(paths);
    }

    static List<Range> typeDeclarationRanges(List<DocumentSymbol> symbols) {
        return SafeDeleteSupport.typeDeclarationRanges(symbols);
    }

    private static <T> T onUi(Supplier<T> action) {
        return UiThreads.onUi(action);
    }

    @Override
    public IdeDiagnosticHoverPolicy getDiagnosticHoverPolicy(Path filePath) {
        if (JavaProjectConventions.isMavenPom(filePath)
                || SpringConfigSupport.isConfigFile(filePath) || JavaProjectConventions.isJava(filePath)) {
            return IdeDiagnosticHoverPolicy.diagnosticFirst();
        }
        return IdeDiagnosticHoverPolicy.disabled();
    }

    @Override
    public void onHover(IdeHoverContext context) {
        debugSupport.onHover(context);
    }

    @Override
    public List<CodeLens> getCodeLenses(IdeCodeLensContext context) {
        return codeLensSupport.getCodeLenses(context);
    }

    private static List<Location> springLocations(SpringNavigation.Target target) {
        return CodeLensSupport.springLocations(target);
    }

    private IdeEditorContext editorContextFor(Path file) {
        Path normalized = JavaProjectConventions.normalize(file);
        return normalized == null ? null : javaEditors.get(normalized);
    }

    IdeEditorContext liveEditorFor(Path file) {
        IdeEditorContext registered = editorContextFor(file);
        if (registered != null) {
            return registered;
        }
        Path normalized = JavaProjectConventions.normalize(file);
        IdeEditorContext open = normalized == null || !JavaProjectConventions.isJava(normalized)
                ? null : getEditor(normalized);
        if (open == null) {
            return null;
        }
        IdeEditorContext previous = javaEditors.putIfAbsent(normalized, open);
        return previous == null ? open : previous;
    }

    static String locationKey(Location location) {
        return UiThreads.locationKey(location);
    }

    private static List<Location> uniqueLocations(List<Location> locations) {
        return JavaNavigation.unique(locations);
    }

    private void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                 IdeEditorContext context, Point screen, Kind kind) {
        navigationViews.showUsagesPopup(locations, currentFile, currentText, context, screen, kind);
    }

    private void navigateToLocation(Location location, Path path) {
        navigationViews.navigateToLocation(location, path);
    }

    private void openAt(Path path, int line, int col) {
        navigationViews.openAt(path, line, col);
    }

    static int[] clampPosition(String text, int line, int col) {
        return NavigationViews.clampPosition(text, line, col);
    }

    static void applyClassFileEditorProviders(CodeEditor editor, Path virtual,
                                              JavaEditorRegistry editors,
                                              HoverDocumentationProvider hover) {
        NavigationViews.applyClassFileEditorProviders(editor, virtual, editors, hover);
    }

    @Override
    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        return renameSupport.computeRenameEdits(context);
    }

    @Override
    public IdeWorkspaceEdit computeRenameWorkspaceEdit(IdeRenameContext context) {
        return renameSupport.computeRenameWorkspaceEdit(context);
    }

    @Override
    public boolean isRenameEnabled(Path filePath) {
        return renameSupport.isRenameEnabled(filePath);
    }

    @Override
    public IdeRenamePolicy getRenamePolicy(Path filePath) {
        return renameSupport.getRenamePolicy(filePath);
    }

    @Override
    public IdeRenamePreparation prepareRename(IdeRenamePrepareContext context) {
        return renameSupport.prepareRename(context);
    }

    @Override
    public String validateRenameName(IdeRenamePrepareContext context, String newName) {
        return renameSupport.validateRenameName(context, newName);
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        return diagnosticsEngine.getCodeActions(context);
    }

    InspectionSuppressionStore suppressions() {
        return diagnosticsEngine.suppressions();
    }

    @Override
    public CompletableFuture<List<InlayHint>> getInlayHintsAsync(IdeInlayHintContext context) {
        JavaLanguageServer lsp = runningServerFor(context == null ? null : context.filePath());
        return lsp == null ? CompletableFuture.completedFuture(null)
                : lsp.inlayHintsAsync(context.filePath(), context.text(), context.firstLine(), context.lastLine());
    }

    @Override
    public List<InlayHint> getInlayHints(IdeInlayHintContext context) {
        JavaLanguageServer lsp = runningServerFor(context == null ? null : context.filePath());
        return lsp == null ? null : lsp.inlayHints(context.filePath(), context.text(),
                context.firstLine(), context.lastLine());
    }

    @Override
    public boolean isSemanticTokensEnabled() {
        return true;
    }

    @Override
    public CompletableFuture<List<SemanticToken>> getSemanticTokensAsync(IdeSemanticTokensContext context) {
        JavaLanguageServer lsp = jdtLs;
        if (context == null || lsp == null || !lsp.isReady()
                || !JavaProjectConventions.isJava(context.filePath())
                || !settings().getLanguageServerMode().startsServer()) {
            return CompletableFuture.completedFuture(getSemanticTokens(context));
        }
        return lsp.semanticTokensAsync(context.filePath(), context.text());
    }

    @Override
    public List<SemanticToken> getSemanticTokens(IdeSemanticTokensContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)
                || !settings().getLanguageServerMode().startsServer()) {
            return null;
        }
        JavaLanguageServer lsp = jdtLs;
        LanguageServerState state = lsp == null
                ? LanguageServerState.NOT_STARTED : lsp.getState();
        if (state == LanguageServerState.ERROR || state == LanguageServerState.STOPPED) {
            return null;
        }
        if (lsp == null || !lsp.isReady()) {
            return List.of();
        }
        return lsp.semanticTokens(filePath, context.text());
    }

    @Override
    public String formatCode(FormatCodeContext context) {
        Path file = context == null ? null : context.file();
        JavaLanguageServer lsp = runningServerFor(file);
        if (lsp == null) {
            if (isIndexing(file)) {
                setStatusBarText(text("status.formatDuringIndexing",
                        "Java: formatacao disponivel apos a indexacao"));
            }
            return null;
        }
        return lsp.format(context.file(), context.fullText(),
                context.tabSize(), context.useSpacesForTab());
    }

    @Override
    public CompletableFuture<SignatureHelp> provideSignatureHelpAsync(IdeSignatureHelpContext context) {
        return navigationSupport.provideSignatureHelpAsync(context);
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        return navigationSupport.provideSignatureHelp(context);
    }

    @Override
    public Set<Character> getSignatureTriggerCharacters() {
        return navigationSupport.getSignatureTriggerCharacters();
    }

    @Override
    public Set<Character> getSignatureRetriggerCharacters() {
        return navigationSupport.getSignatureRetriggerCharacters();
    }

    @Override
    public GlobalSearchResult search(GlobalSearchQuery query, GlobalSearchResult defaultResult) {
        return navigationSupport.search(query, defaultResult);
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        return navigationSupport.findDefinitions(context);
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        return navigationSupport.findReferences(context);
    }

    @Override
    public List<DocumentSymbol> getDocumentSymbols(IdeDocumentSymbolContext context) {
        return navigationSupport.getDocumentSymbols(context);
    }

    @Override
    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        return navigationSupport.getDocumentHighlights(context);
    }

    @Override
    public boolean isGoToDeclarationEnabled() {
        return navigationSupport.isGoToDeclarationEnabled();
    }

    @Override
    public boolean isGoToImplementationEnabled() {
        return navigationSupport.isGoToImplementationEnabled();
    }

    @Override
    public boolean isFindUsagesEnabled() {
        return navigationSupport.isFindUsagesEnabled();
    }

    @Override
    public void onWordClick(IdeWordClickContext context) {
        navigationSupport.onWordClick(context);
    }

    @Override
    public void onGoToDeclaration(IdeEditorContext context) {
        navigationSupport.onGoToDeclaration(context);
    }

    @Override
    public void onGoToImplementation(IdeEditorContext context) {
        navigationSupport.onGoToImplementation(context);
    }

    @Override
    public void onFindUsages(IdeEditorContext context) {
        navigationSupport.onFindUsages(context);
    }

    @Override
    public boolean isCallHierarchyEnabled() {
        return navigationSupport.isCallHierarchyEnabled();
    }

    @Override
    public boolean isTypeHierarchyEnabled() {
        return navigationSupport.isTypeHierarchyEnabled();
    }

    @Override
    public List<TypeHierarchyItem> prepareTypeHierarchy(IdeCallHierarchyContext context) {
        return navigationSupport.prepareTypeHierarchy(context);
    }

    @Override
    public List<TypeHierarchyItem> getSupertypes(TypeHierarchyItem item) {
        return navigationSupport.getSupertypes(item);
    }

    @Override
    public List<TypeHierarchyItem> getSubtypes(TypeHierarchyItem item) {
        return navigationSupport.getSubtypes(item);
    }

    @Override
    public List<CallHierarchyItem> prepareCallHierarchy(IdeCallHierarchyContext context) {
        return navigationSupport.prepareCallHierarchy(context);
    }

    @Override
    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        return navigationSupport.getIncomingCalls(item);
    }

    @Override
    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        return navigationSupport.getOutgoingCalls(item);
    }

    NavigationSupport.ResolvedNavigation resolveCurrent(Supplier<NavigationSupport.NavigationRequest> live, NavigationSupport.NavigationRequest request,
                                      Path file, Kind kind, boolean followEdits) {
        return navigationSupport.resolveCurrent(live, request, file, kind, followEdits);
    }

    Result resolveNavigation(Path filePath, String source, int line, int col, Kind kind) {
        return navigationSupport.resolveNavigation(filePath, source, line, col, kind);
    }

    static boolean isOwnDeclaration(List<Location> definitions, IdeWordClickContext context) {
        return NavigationSupport.isOwnDeclaration(definitions, context);
    }

    static boolean isCtrlDefinitionClick(IdeWordClickContext context) {
        return NavigationSupport.isCtrlDefinitionClick(context);
    }

    static String identifierAt(String text, int line, int col) {
        return NavigationSupport.identifierAt(text, line, col);
    }

    private void navigateFromEditor(IdeEditorContext context, String action) {
        navigationSupport.navigateFromEditor(context, action);
    }

    private boolean isNavigationAvailable(Path filePath) {
        return navigationSupport.isNavigationAvailable(filePath);
    }

    private Path pomLocalRepository() {
        try {
            return dependencyManagerHost().localRepository();
        } catch (Exception e) {
            log.debug("Repositorio Maven local indisponivel: {}", e.getMessage());
            return null;
        }
    }

    private final class EditorDependencyCatalog implements BuildFileCompletionProvider.Catalog {

        @Override
        public List<DependencySearchResult> search(String query) {
            return dependencyManagerHost().editorCatalog().search(query);
        }

        @Override
        public List<DependencyVersionChoice> versions(String groupId, String artifactId) {
            return dependencyManagerHost().editorCatalog().versions(groupId, artifactId);
        }
    }

    @Override
    public void onWordCaretChange(IdeWordCaretContext context) {
        diagnosticsEngine.onWordCaretChange(context);
    }

    @Override
    public void onEditorOpen(IdeEditorContext editorContext) {
        editorEvents.onEditorOpen(editorContext);
    }

    @Override
    public void onEditorSelected(IdeEditorContext editorContext) {
        editorEvents.onEditorSelected(editorContext);
    }

    @Override
    public void onCodeEditorInsertText(IdeEditorContext editorContext, int offset, String inserted) {
        editorEvents.onCodeEditorInsertText(editorContext, offset, inserted);
    }

    @Override
    public void onCodeEditorDeleteText(IdeEditorContext editorContext, int offset, String removed) {
        editorEvents.onCodeEditorDeleteText(editorContext, offset, removed);
    }

    @Override
    public void onCodeEditorTextChanged(IdeEditorContext editorContext) {
        editorEvents.onCodeEditorTextChanged(editorContext);
    }

    @Override
    public void onEditorClose(Path filePath) {
        editorEvents.onEditorClose(filePath);
    }

    @Override
    public String onBeforeFileSave(Path filePath, String content) {
        return editorEvents.onBeforeFileSave(filePath, content);
    }

    @Override
    public void onAfterFileSave(Path filePath, String content) {
        editorEvents.onAfterFileSave(filePath, content);
    }

    private JavaLanguageServer interactiveServerFor(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !lsp.isInteractive() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    private JavaLanguageServer runningServerFor(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !lsp.isReady() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    private boolean isIndexing(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        return lsp != null && lsp.getState() == LanguageServerState.INDEXING
                && JavaProjectConventions.isJava(filePath);
    }

    private boolean isSpringNavigationEnabled() {
        JavaProjectDescriptor current = descriptor;
        return current != null && current.spring() && settings().isSpringSupport()
                && settings().isSpringNavigation();
    }

    @Override
    public String getLineCommentPrefix(Path filePath) {
        if (filePath == null) {
            return null;
        }
        return switch (JavaProjectConventions.extensionOf(filePath)) {
            case "java", "gradle", "kts" -> "//";
            case "properties" -> "#";
            case "yml", "yaml" -> "#";
            default -> null;
        };
    }

    @Override
    public void contributeProjectTreeMenu(IdeMenuBuilder menu, List<Path> selectedPaths) {
        projectTreeMenu.contributeProjectTreeMenu(menu, selectedPaths);
    }

    @Override
    public List<ProjectTreeIgnoreRule> resolveProjectTreeIgnoredFolders(Path projectPath) {
        return List.of(
                ProjectTreeIgnoreRule.any("build"),
                ProjectTreeIgnoreRule.any("out"),
                ProjectTreeIgnoreRule.any("bin"),
                ProjectTreeIgnoreRule.any(".gradle"),
                ProjectTreeIgnoreRule.any(".settings"),
                ProjectTreeIgnoreRule.specific(".orion"));
    }

    @Override
    public Icon resolveProjectTreeNodeIcon(Path path, boolean directory, int iconSize) {
        if (directory || !JavaProjectTreeIcons.isJava(path)) {
            return null;
        }
        return JavaProjectTreeIcons.iconOf(path, iconSize > 0 ? iconSize : JavaIcons.SMALL);
    }

    @Override
    public void contributeEditorMenu(IdeMenuBuilder menu, IdeEditorContext editorContext) {
        if (menu == null || editorContext == null) return;
        if (JavaProjectConventions.isBuildFile(editorContext.filePath())) {
            contributeBuildFileEditorMenu(menu);
            return;
        }
        if (!JavaProjectConventions.isJava(editorContext.filePath())) return;
        boolean enabled = isNavigationAvailable(editorContext.filePath());
        boolean debugPaused = debugSupport.isDebugPaused();
        String debugExpression = DebugSupport.selectedDebugExpression(editorContext);
        menu.separator()
                .submenu(text("menu.navigate", "Navigate"), JavaIcons.search(JavaIcons.SMALL),
                        navigate -> navigate
                        .item(text("menu.definition", "Go to Definition"), enabled,
                                event -> onGoToDeclaration(editorContext))
                        .item(text("menu.implementation", "Go to Implementation"), enabled,
                                event -> onGoToImplementation(editorContext))
                        .item(text("menu.usages", "Find Usages"), enabled,
                                event -> onFindUsages(editorContext)))
                .separator()
                .item(text("generate.title", "Generate..."), enabled,
                        event -> sourceActions.showGenerateActions(editorContext))
                .item(text("generate.override", "Override Methods..."), enabled,
                        event -> sourceActions.showOverrideMethods(editorContext, false))
                .item(text("generate.implement", "Implement Methods..."), enabled,
                        event -> sourceActions.showOverrideMethods(editorContext, true))
                .separator()
                .item(text("debug.evaluate", "Evaluate Expression..."), debugPaused,
                        event -> debugSupport.showEvaluateDialog(editorContext, 0))
                .item(text("debug.addWatch", "Add Watch"), debugPaused
                                && debugExpression != null,
                        event -> debugSupport.addDebugWatch(debugExpression));
    }

    @Override
    public void contributeEditorViewModes(IdeEditorViewModesBuilder modes, IdeEditorContext editorContext) {
        if (modes == null || editorContext == null || !SwingDesignerSupport.isJavaSource(editorContext.filePath())) {
            return;
        }
        SwingDesignerSupport support = ensureSwingDesigner();
        if (support != null) {
            support.contributeViewModes(modes, editorContext);
        }
    }

    private SwingDesignerSupport ensureSwingDesigner() {
        return swingDesignerHost.ensureSwingDesigner();
    }

    private void closeSwingDesigner() {
        swingDesignerHost.closeSwingDesigner();
    }

    private void contributeBuildFileEditorMenu(IdeMenuBuilder menu) {
        JavaProjectDescriptor current = descriptor;
        if (current == null || !current.kind().hasBuildTool()) return;
        menu.separator()
                .item(text("tree.sync", "Sincronizar projeto"),
                        JavaIcons.sync(JavaIcons.SMALL), event -> syncProject())
                .item(text("tree.reload", "Recarregar projeto"),
                        JavaIcons.refresh(JavaIcons.SMALL), event -> clearCaches())
                .item(text("menu.buildTools", "Build Tools"),
                        JavaIcons.buildTool(current, JavaIcons.SMALL), event -> openBuildTools());
    }

    @Override
    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menu.submenu("javaBuildMenu", text("menu.build", "Build"), build -> build
                .item("javaCompile", text("menu.compile", "Compilar"),
                        event -> runBuild(BuildSystem.BuildAction.COMPILE,
                                text("menu.compile", "Compilar")))
                .item("javaRebuild", text("menu.rebuild", "Recompilar tudo"),
                        event -> runBuild(BuildSystem.BuildAction.REBUILD,
                                text("menu.rebuild", "Recompilar tudo")))
                .item("javaClean", text("menu.clean", "Limpar"),
                        event -> runBuild(BuildSystem.BuildAction.CLEAN,
                                text("menu.clean", "Limpar")))
                .separator()
                .item("javaTest", text("menu.test", "Testar"),
                        event -> runBuild(BuildSystem.BuildAction.TEST,
                                text("menu.test", "Testar")))
                .item("javaPackage", text("menu.package", "Empacotar"),
                        event -> runBuild(BuildSystem.BuildAction.PACKAGE,
                                text("menu.package", "Empacotar")))
                .item("javaInstall", text("menu.install", "Instalar no repositorio local"),
                        event -> runBuild(BuildSystem.BuildAction.INSTALL,
                                text("menu.install", "Instalar no repositorio local")))
                .separator()
                .add(MenuNode.item("javaSyncProject", text("menu.sync", "Sincronizar projeto"))
                        .icon(JavaIcons.sync(JavaIcons.SMALL))
                        .tooltip(text("menu.sync.tip",
                                "Reler o pom ou o build.gradle e atualizar o classpath"))
                        .onClick(event -> syncProject()))
                .add(MenuNode.item("javaSyncWithDisk",
                                text("menu.syncDisk", "Ressincronizar com o disco"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.syncDisk.tip",
                                "Reler as mudancas feitas fora do editor e reanalisar os arquivos abertos"))
                        .onClick(event -> syncWithDisk()))
                .add(MenuNode.item("javaReanalyzeDiagnostics",
                                text("menu.reanalyzeDiagnostics", "Limpar e rediagnosticar"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.reanalyzeDiagnostics.tip",
                                "Descartar os diagnosticos atuais e reiniciar a analise Java"))
                        .onClick(event -> reanalyzeDiagnostics()))
                .add(MenuNode.item("javaRestartLanguageServer",
                                text("menu.restartLsp", "Reiniciar Java Language Server"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.restartLsp.tip",
                                "Encerrar o JDT LS e inicia-lo novamente, mesmo apos falhas repetidas"))
                        .onClick(event -> restartLanguageServer()))
                .add(MenuNode.item("javaProjectStructure",
                                text("menu.projectStructure", "Estrutura do projeto..."))
                        .icon(JavaIcons.module(JavaIcons.SMALL))
                        .shortcut("control alt shift S")
                        .tooltip(text("menu.projectStructure.tip",
                                "SDK, nivel de linguagem, pastas de codigo, modulos e bibliotecas"))
                        .onClick(event -> openProjectStructure()))
                .add(MenuNode.item("javaBuildJdkManager", text("menu.jdkManager", "Gerenciar JDKs"))
                        .icon(JavaIcons.java(JavaIcons.SMALL))
                        .tooltip(text("menu.jdkManager.tip",
                                "Ver as JDKs instaladas, baixar novas e escolher a do projeto"))
                        .onClick(event -> openJdkManager())));

        menu.into("code")
                .add(MenuNode.item("javaGenerate", text("generate.title", "Generate..."))
                        .shortcut("alt INSERT")
                        .onClick(event -> sourceActions.showGenerateActions(activeJavaEditor)))
                .add(MenuNode.item("javaOverrideMethods",
                                text("generate.override", "Override Methods..."))
                        .shortcut("control INSERT")
                        .onClick(event -> sourceActions.showOverrideMethods(activeJavaEditor, false)))
                .add(MenuNode.item("javaImplementMethods",
                                text("generate.implement", "Implement Methods..."))
                        .shortcut("control I")
                        .onClick(event -> sourceActions.showOverrideMethods(activeJavaEditor, true)))
                .add(MenuNode.item("javaEvaluateExpression",
                                text("debug.evaluate", "Evaluate Expression..."))
                        .shortcut("alt F8")
                        .onClick(event -> debugSupport.showEvaluateDialog(activeJavaEditor, 0)))
                .add(MenuNode.separator());

        menu.into("window")
                .add(MenuNode.item(JDK_TAB_ID, text("menu.jdkManager", "Gerenciar JDKs"))
                        .icon(JavaIcons.java(JavaIcons.SMALL))
                        .tooltip(text("menu.jdkManager.tip",
                                "Ver as JDKs instaladas, baixar novas e escolher a do projeto"))
                        .onClick(event -> openJdkManager()))
                .add(MenuNode.item(STRUCTURE_TAB_ID,
                                text("menu.projectStructure", "Estrutura do projeto..."))
                        .icon(JavaIcons.module(JavaIcons.SMALL))
                        .tooltip(text("menu.projectStructure.tip",
                                "SDK, nivel de linguagem, pastas de codigo, modulos e bibliotecas"))
                        .onClick(event -> openProjectStructure()))
                .add(MenuNode.item(DEPENDENCIES_TAB_ID,
                                text("menu.dependencies", "Gerenciar dependencias"))
                        .icon(JavaIcons.dependency(JavaIcons.SMALL))
                        .tooltip(text("menu.dependencies.tip",
                                "Buscar no Maven Central, adicionar, atualizar e remover dependencias"))
                        .onClick(event -> openDependencyManager()))
                .add(MenuNode.item("javaProblems", text("menu.problems", "Problemas"))
                        .icon(JavaIcons.error(JavaIcons.SMALL))
                        .tooltip(text("menu.problems.tip",
                                "Erros e avisos do Java, Maven e Gradle"))
                        .onClick(event -> openProblemsPanel()))
                .add(MenuNode.item("javaTestExplorer", text("menu.tests", "Testes"))
                        .icon(JavaIcons.test(JavaIcons.SMALL))
                        .tooltip(text("menu.tests.tip",
                                "Ver e executar os testes JUnit do projeto"))
                        .onClick(event -> openTestExplorer()))
                .add(MenuNode.item("javaTodo", text("menu.todo", "TODO"))
                        .icon(JavaIcons.todo(JavaIcons.SMALL))
                        .tooltip(text("menu.todo.tip",
                                "TODO, FIXME e demais marcadores do projeto"))
                        .onClick(event -> openTodoPanel()))
                .add(MenuNode.item("javaBuildTools", text("menu.buildTools", "Build Tools"))
                        .icon(JavaIcons.buildTool(descriptor, JavaIcons.SMALL))
                        .tooltip(text("menu.buildTools.tip", "Projetos, tasks e dependencias Maven/Gradle"))
                        .onClick(event -> openBuildTools()))
                .add(MenuNode.item("javaSpringExplorer", text("menu.spring", "Spring"))
                        .icon(JavaIcons.spring(JavaIcons.SMALL))
                        .tooltip(text("menu.spring.tip",
                                "Beans, endpoints e o estado da aplicacao em execucao"))
                        .onClick(event -> openSpringExplorer()));
    }

    @Override
    public List<RunConfigurationContribution> getRunConfigurationContributions() {
        return runLauncher.getRunConfigurationContributions();
    }

    @Override
    public Collection<RunConfigurationData> getStaticRunConfigurations() {
        return staticRunConfigurations;
    }

    private static List<RunConfigurationData> buildStaticRunConfigurations(
            JavaProjectDescriptor current) {
        if (current == null) return List.of();
        List<MainClassScanner.MainClass> mainClasses = MainClassScanner.scan(current);
        if (mainClasses.isEmpty()) {
            return List.of();
        }
        MainClassScanner.MainClass entryPoint = mainClasses.getFirst();
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put(JavaRunSupport.PROPERTY_MAIN_CLASS, entryPoint.qualifiedName());
        properties.put(JavaRunSupport.PROPERTY_MODULE, entryPoint.module().name());

        return List.of(RunConfigurationData.builder()
                .type(entryPoint.springBoot() ? JavaRunSupport.TYPE_SPRING_BOOT
                        : JavaRunSupport.TYPE_RUN)
                .title(entryPoint.simpleName())
                .properties(properties)
                .build());
    }

    @Override
    public void onRunConfigurationChanged(RunConfigurationData configuration) {
        runLauncher.onRunConfigurationChanged(configuration);
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
        return runLauncher.launch(configuration, context);
    }

    @Override
    public RunProcessHandle launchCoverage(RunConfigurationData configuration,
                                           RunExecutionContext context) {
        return runLauncher.launchCoverage(configuration, context);
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData configuration,
                                        RunExecutionContext context) {
        return runLauncher.launchDebug(configuration, context);
    }

    private Path pluginRepository() {
        Path root = projectRoot;
        Path cached = pluginRepositoryPath;
        if (cached != null && Objects.equals(root, pluginRepositoryRoot)) {
            return cached;
        }
        Path resolved = new MavenLocalRepositoryResolver().resolve(descriptor, buildSystem)
                .repository();
        pluginRepositoryRoot = root;
        pluginRepositoryPath = resolved;
        return resolved;
    }

    static JavaModule mostSpecificModule(Collection<JavaModule> modules, Path file) {
        if (modules == null || file == null) {
            return null;
        }
        return modules.stream()
                .filter(module -> !module.isAggregator() && module.contains(file))
                .max(Comparator.comparingInt(module -> module.root().getNameCount()))
                .orElse(null);
    }

    static String debugProjectName(JavaModule module) {
        return DebugSupport.debugProjectName(module);
    }

    static String safeDebugExpression(String source, int offset) {
        return DebugSupport.safeDebugExpression(source, offset);
    }

    private static JavaModule moduleContaining(JavaProjectDescriptor descriptor, Path file,
                                               boolean testRoots) {
        return descriptor.modules().stream()
                .filter(candidate -> (testRoots
                        ? candidate.existingTestRoots() : candidate.existingSourceRoots()).stream()
                        .map(JavaProjectConventions::normalize)
                        .anyMatch(file::startsWith))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void stop(RunConfigurationData configuration) {
        runLauncher.stop(configuration);
    }

    @Override
    public void onHotReload(RunConfigurationData configuration) {
        runHotReload();
    }

    private void runHotReload() {
        debugSupport.runHotReload();
    }

    @Override
    public void onBreakpointChanged(BreakpointChangedEvent event) {
        super.onBreakpointChanged(event);
        if (event == null || event.getBreakpointIde() == null) {
            return;
        }
        JavaDebugSession session = debugSupport.session();
        if (session == null) {
            return;
        }
        BreakpointChanges.Update update = BreakpointChanges.resolve(event);
        session.updateBreakpoint(event.getFile(), event.getBreakpointIde().line(), update.enabled(),
                update.spec());
    }

    @Override
    public boolean isConditionalBreakpointEnabled(Path fileOpen) {
        return conditionalBreakpoints.isConditionalBreakpointEnabled(fileOpen);
    }

    @Override
    public CodeEditor createConditionalBreakpointEditor(ConditionalBreakpointContext context) {
        return conditionalBreakpoints.createConditionalBreakpointEditor(context);
    }

    @Override
    public void configureConditionalBreakpointEditor(IdeEditorContext editorContext,
                                                     ConditionalBreakpointContext context) {
        conditionalBreakpoints.configureConditionalBreakpointEditor(editorContext, context);
    }

    @Override
    public void configureConditionalBreakpointDialog(ConditionalBreakpointDialogView dialogView) {
        conditionalBreakpoints.configureConditionalBreakpointDialog(dialogView);
    }

    private JavaTestRunner newTestRunner(JavaProjectDescriptor current, BuildSystem build) {
        JdkService jdks = jdkService;
        JavaTestRunner runner = new JavaTestRunner(current, build, this::getProjectJdk,
                settings().isIncrementalBuild()
                        ? new JUnitPlatformLauncher(jdks == null ? null : jdks.sdkRoot(),
                                this::pomLocalRepository)
                        : null);
        activeTestRunner.set(runner);
        return runner;
    }

    public void openTestExplorer() {
        ensureTestPanel();
        if (testPanelId != null) {
            requestOpenToolPanel(testPanelId);
        }
        if (testPanel != null) {
            testPanel.reload();
        }
    }

    private void ensureTestPanel() {
        if (testPanel == null) {
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(new TestExplorerSupport(adapterHost), background);
            testPanel = panel;
            Icon icon = JavaIcons.test(JavaIcons.SMALL);
            testPanelId = icon == null
                    ? registerToolPanel(DockRegion.BOTTOM, text("panel.tests", "Testes"),
                            ToolIconType.PLAY, panel)
                    : registerToolPanel(DockRegion.BOTTOM, text("panel.tests", "Testes"),
                            icon, panel);
        }
    }

    private void openTodoPanel() {
        todoSupport.openPanel();
    }

    private void refreshTodosFor(Path filePath, String content) {
        todoSupport.refresh(filePath, content);
    }

    private void openProblemsPanel() {
        problemsSupport.openProblemsPanel();
    }

    private void refreshProblemsPanel() {
        problemsSupport.refreshProblemsPanel();
    }

    private void syncWithDisk() {
        problemsSupport.syncWithDisk();
    }

    private void reanalyzeDiagnostics() {
        problemsSupport.reanalyzeDiagnostics();
    }

    private void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
        problemsSupport.finishDiagnosticReanalysis(ticket, root, successful);
    }

    private void ensureBuildToolsPanel() {
        if (buildToolsPanel != null) {
            buildToolsPanel.reload();
            return;
        }
        JavaBuildToolsPanel panel = new JavaBuildToolsPanel(new BuildToolsSupport(adapterHost), background);
        buildToolsPanel = panel;
        Icon icon = JavaIcons.buildTool(descriptor, JavaIcons.SMALL);
        buildToolsPanelId = icon == null
                ? registerToolPanel(DockRegion.RIGHT, text("panel.buildTools", "Build Tools"),
                        ToolIconType.SETTINGS, panel, new Dimension(420, 600))
                : registerToolPanel(DockRegion.RIGHT, text("panel.buildTools", "Build Tools"),
                        icon, panel, new Dimension(420, 600));
    }

    private void openBuildTools() {
        ensureBuildToolsPanel();
        if (buildToolsPanelId != null) {
            requestOpenToolPanel(buildToolsPanelId);
        }
    }

    private java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module) {
        JavaLanguageServer lsp = jdtLs;
        ProjectModelSupport model = lsp == null ? null : lsp.extension(ProjectModelSupport.class);
        if (model != null && lsp.isReady()) {
            java.util.Optional<String> fromServer = model.runtimeClasspath(module.root());
            if (fromServer.isPresent() && !fromServer.get().isBlank()) {
                if (!ClasspathValidation.hasMissingJar(fromServer.get())) {
                    return fromServer;
                }
                requestJdtLsProjectConfigurationRefresh(lsp);
            }
        }
        return build.resolveRuntimeClasspath(module);
    }

    public void openSpringExplorer() {
        JavaProjectDescriptor current = descriptor;
        if (current == null || !current.spring()) {
            setStatusBarText(text("status.noSpring", "Java: este projeto nao usa Spring"));
            return;
        }
        spring.setupPanel();
        if (spring.panelId() != null) {
            requestOpenToolPanel(spring.panelId());
        }
    }

    public void runBuild(BuildSystem.BuildAction action, String title) {
        buildSupport.runBuild(action, title, null);
    }

    public void openDependencyManager() {
        DependencyService dependencies = ensureDependencyService();
        if (dependencies == null || !dependencies.isSupported()) {
            setStatusBarText(text("status.noBuildTool",
                    "Java: o projeto nao usa Maven nem Gradle - nao ha onde declarar dependencias"));
            return;
        }
        DependencyManagerPanel panel = dependencyPanel;
        if (panel == null) {
            panel = new DependencyManagerPanel(dependencyManagerHost(), this::createModernDialogBuilder);
            dependencyPanel = panel;
        } else {
            panel.reloadModules();
        }
        openCenterTab(DEPENDENCIES_TAB_ID, text("tab.dependencies", "Dependencias"), panel, true);
        switchToCenterTab(DEPENDENCIES_TAB_ID);
    }

    private synchronized DependencyManagerCoordinator dependencyManagerHost() {
        DependencyManagerCoordinator current = dependencyCoordinator;
        if (current == null) {
            current = new DependencyManagerCoordinator(background, mavenCentral, new OsvClient(),
                    () -> descriptor, () -> buildSystem, this::ensureDependencyService,
                    lifecycle::get, () -> projectRoot, this::current,
                    this::afterDependencyChange);
            dependencyCoordinator = current;
            current.onRepositoryInvalidated(() -> {
                BuildSystem build = buildSystem;
                if (build != null) build.invalidateClasspathCache();
                projectSync.restartAutomaticSync();
            });
            current.warmLocalCatalog();
            JavaPluginSettings active = settings;
            if (active != null) {
                current.setLocalOnly(active.isDependencySearchLocalOnly());
            }
        }
        return current;
    }

    private void afterDependencyChange(boolean changed) {
        if (!changed) {
            return;
        }
        BuildSystem build = buildSystem;
        if (build != null) {
            build.invalidateClasspathCache();
        }
        requestProjectTreeViewRefresh();
        clearCaches();
    }

    public void syncProject() {
        projectSync.syncProject();
    }

    static boolean jdkRequirementChanged(JavaProjectDescriptor previous, JavaProjectDescriptor reloaded) {
        return ProjectSyncSupport.jdkRequirementChanged(previous, reloaded);
    }

    private void onBuildFileChanged(Path filePath) {
        projectSync.onBuildFileChanged(filePath);
    }

    static boolean validMavenReactor(Path file, Set<Path> visited) {
        return ProjectSyncSupport.validMavenReactor(file, visited);
    }

    public void openProjectStructure() {
        JavaProjectStructurePanel panel = structurePanel;
        if (panel == null) {
            panel = new JavaProjectStructurePanel(new ProjectStructureSupport(adapterHost));
            structurePanel = panel;
        } else {
            panel.reload();
        }
        openCenterTab(STRUCTURE_TAB_ID, text("tab.projectStructure", "Estrutura do projeto"),
                panel, true);
        switchToCenterTab(STRUCTURE_TAB_ID);
    }

    public void openJdkManager() {
        JdkManagerPanel panel = jdkManagerPanel;
        if (panel == null) {
            panel = new JdkManagerPanel(new JdkManagerSupport(adapterHost), this::createModernDialogBuilder);
            jdkManagerPanel = panel;
        } else {
            panel.reload();
        }
        openCenterTab(JDK_TAB_ID, text("tab.jdkManager", "JDKs"), panel, true);
        switchToCenterTab(JDK_TAB_ID);
    }

    @Override
    public List<PluginSettingsPage> getSettingsPages() {
        return List.of(new JavaSettingsPage(ensureSettings(), this::applySettings,
                suppressions(), projectRoot));
    }

    private synchronized JavaPluginSettings ensureSettings() {
        JavaPluginSettings existing = settings;
        if (existing != null) {
            return existing;
        }
        Path directory = null;
        try {
            directory = getResource().getResourcePath();
        } catch (Exception e) {
            log.debug("Diretorio de recursos indisponivel: {}", e.getMessage());
        }
        JavaPluginSettings created = new JavaPluginSettings(directory);
        settings = created;
        applySettings();
        return created;
    }

    private void applySettings() {
        JavaPluginSettings current = settings;
        if (current == null) {
            return;
        }
        spring.baseUrl(current.getSpringBaseUrl());
        applyDependencySearchSettings(current);
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null) {
            lsp.setInlayHintsMode(current.getInlayHints());
        }
        Path root = projectRoot;
        if (root != null) {
            requestRefreshCodeLenses(root);
        }
        SwingUtilities.invokeLater(coverageSupport::refreshGutters);
        if (!applyBuildModeChange(current.getJdtBuildMode(), root)) {
            applyLombokSettingChange(root);
        }
    }

    private void applyDependencySearchSettings(JavaPluginSettings current) {
        mavenCentral.setRequestTimeout(
                Duration.ofSeconds(current.getDependencySearchTimeoutSeconds()));
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        if (coordinator != null) {
            coordinator.setLocalOnly(current.isDependencySearchLocalOnly());
        }
    }

    private boolean applyBuildModeChange(JdtBuildMode mode, Path root) {
        if (mode == languageServers.appliedBuildMode()) {
            return false;
        }
        languageServers.appliedBuildMode(mode);
        JavaLanguageServer lsp = jdtLs;
        if (root == null || lsp == null) {
            return false;
        }
        background.submit(() -> {
            lsp.stop();
            hideProgress(LanguageServerManager.LSP_PROGRESS_ID);
            resolveProjectJdk(lifecycle.incrementAndGet(), root);
        });
        return true;
    }

    private void applyLombokSettingChange(Path root) {
        JavaLanguageServer lsp = jdtLs;
        if (root == null || lsp == null) {
            return;
        }
        background.submit(() -> restartWhenLombokAgentChanged(lsp, descriptor));
    }

    private void restartLanguageServer() {
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null) {
            lsp.resetCrashHistory();
        }
        setStatusBarText(text("status.restartingLsp", "Java: reiniciando o IntelliSense..."));
        clearCaches();
    }

    private void restartWhenLombokAgentChanged(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        if (current == null || lsp == null) {
            return;
        }
        applyLombokAgent(lsp, current);
        if (!LanguageServerManager.needsLombokAgentRestart(lsp)) {
            return;
        }
        log.info("Agente do Lombok mudou; reiniciando o IntelliSense Java");
        setStatusBarText(text("status.lombokRestart",
                "Java: Lombok mudou - reiniciando o IntelliSense"));
        clearCaches();
    }

    private JavaPluginSettings settings() {
        return ensureSettings();
    }

    private synchronized BuildSystem ensureBuildSystem() {
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return null;
        }
        BuildSystem existing = buildSystem;
        if (existing != null) {
            return existing;
        }
        JdkService jdks = ensureJdkService();
        BuildToolProvisioner provisioner = new BuildToolProvisioner(jdks,
                new SdkDownloader(resolveDownloadObserver()));
        BuildSystem created = BuildSystems.forProject(current, provisioner,
                this::getProjectJdk, progressListener());
        if (created instanceof MavenBuildService maven) {
            maven.setActiveProfiles(() ->
                    new BuildRunConfigurations(projectRoot).activeProfiles());
        } else if (created instanceof GradleBuildService gradle) {
            gradle.setActiveProfiles(() ->
                    new BuildRunConfigurations(projectRoot).activeProfiles());
            gradle.setStaleClasspathListener(this::requestJdtLsProjectConfigurationRefresh);
        }
        buildSystem = created;
        return created;
    }

    private synchronized DependencyService ensureDependencyService() {
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return null;
        }
        DependencyService existing = dependencyService;
        if (existing != null) {
            return existing;
        }
        DependencyService created = new DependencyService(current);
        dependencyService = created;
        return created;
    }

    private synchronized JdkService ensureJdkService() {
        JdkService existing = jdkService;
        if (existing != null) {
            return existing;
        }
        JdkService created = new JdkService(getResource(), resolveDownloadObserver());
        jdkService = created;
        return created;
    }

    private DownloadObserver resolveDownloadObserver() {
        try {
            return getService(DownloadObserver.class);
        } catch (Exception e) {
            log.debug("DownloadObserver indisponivel: {}", e.getMessage());
            return null;
        }
    }

    private DownloadProgressListener progressListener() {
        return new DownloadProgressListener() {
            @Override
            public void onStart(String id, String label) {
                showProgress(id, label);
            }

            @Override
            public void onProgress(String id, String label, int percent) {
                updateProgress(id, label, percent);
            }

            @Override
            public void onFinish(String id) {
                hideProgress(id);
            }
        };
    }

    public JavaProjectDescriptor getDescriptor() {
        return descriptor;
    }

    public JdkInstallation getProjectJdk() {
        return projectJdk;
    }

    static String rootMessage(Throwable error) {
        return AdapterFailures.rootMessage(error);
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public IdeProjectContext getProjectContext() {
        IdeProjectContext delegated = super.getProjectContext();
        return delegated != null ? delegated : projectContext;
    }
}
