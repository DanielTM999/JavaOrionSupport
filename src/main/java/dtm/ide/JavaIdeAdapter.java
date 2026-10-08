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
import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.project.editor.FileAssociated;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.hierarchy.TypeHierarchyItem;
import dtm.ide.api.project.editor.IdeCallHierarchyContext;
import dtm.ide.api.project.editor.IdeCodeLensContext;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeDiagnosticHoverPolicy;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeDocumentSymbolContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeGhostTextContext;
import dtm.ide.adapter.AdapterFailures;
import dtm.ide.adapter.AdapterHost;
import dtm.ide.adapter.BuildSupport;
import dtm.ide.adapter.BuildToolsSupport;
import dtm.ide.adapter.CenterTabIds;
import dtm.ide.adapter.CodeLensSupport;
import dtm.ide.adapter.CompletionEngine;
import dtm.ide.adapter.DebugSupport;
import dtm.ide.adapter.ConditionalBreakpointSupport;
import dtm.ide.adapter.DiagnosticsEngine;
import dtm.ide.adapter.EditorAssistSupport;
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
import dtm.ide.adapter.MenuContributions;
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
import dtm.ide.api.project.diagnostics.ProblemsActionHandle;
import dtm.ide.build.ClasspathValidation;
import dtm.ide.build.BuildRunConfigurations;
import dtm.ide.build.BuildSystem;
import dtm.ide.swingdesigner.SwingDesignerSupport;
import dtm.ide.build.GradleBuildService;
import dtm.ide.build.MavenPluginGoals;
import dtm.ide.build.MavenBuildService;
import dtm.ide.build.BuildSystems;
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
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaProjectTreeIcons;
import dtm.ide.ui.JavaDebugValuePopup;
import dtm.ide.ui.JdkManagerPanel;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.ide.api.project.editor.IdeSelectionRangeContext;
import dtm.ide.api.project.editor.SelectionRangeContext;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationProvider;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
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

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
@Singleton
@PluginReference(id = "java-ide-adapter")
public class JavaIdeAdapter extends IdeAdapter {

    private static final long SLOW_OPERATION_THRESHOLD_MS = 100;

    private static final String NAVIGATION_PROGRESS_ID = "javaNavigation";
    final JavaEditorRegistry editors = new JavaEditorRegistry();
    private final MavenCentralClient mavenCentral = new MavenCentralClient();
    final BuildProblemsCoordinator problems = new BuildProblemsCoordinator();
    final JavaSnippetCompletionProvider snippets = new JavaSnippetCompletionProvider();
    private final AdapterHost adapterHost = new JavaIdeAdapterHost(this);
    final SpringSupport spring = new SpringSupport(adapterHost);
    final CoverageSupport coverageSupport = new CoverageSupport(adapterHost);
    final CompletionEngine completionEngine = new CompletionEngine(adapterHost);
    final DiagnosticsEngine diagnosticsEngine = new DiagnosticsEngine(adapterHost);
    private final RenameSupport renameSupport = new RenameSupport(adapterHost);
    private final SafeDeleteSupport safeDeleteSupport = new SafeDeleteSupport(adapterHost);
    final CodeLensSupport codeLensSupport = new CodeLensSupport(adapterHost);
    final NavigationViews navigationViews = new NavigationViews(adapterHost);
    final SourceActionSupport sourceActions = new SourceActionSupport(adapterHost);
    final ConditionalBreakpointSupport conditionalBreakpoints = new ConditionalBreakpointSupport(adapterHost);
    final DebugSupport debugSupport = new DebugSupport(adapterHost);
    final RunLauncher runLauncher = new RunLauncher(adapterHost);
    private final ProjectTreeMenuSupport projectTreeMenu = new ProjectTreeMenuSupport(adapterHost);
    final SwingDesignerHost swingDesignerHost = new SwingDesignerHost(adapterHost);
    final NavigationSupport navigationSupport = new NavigationSupport(adapterHost);
    final LanguageServerManager languageServers = new LanguageServerManager(adapterHost);
    final ProblemsSupport problemsSupport = new ProblemsSupport(adapterHost);
    final BuildSupport buildSupport = new BuildSupport(adapterHost);
    final ProjectSyncSupport projectSync = new ProjectSyncSupport(adapterHost);
    final FileWatchSupport fileWatch = new FileWatchSupport(adapterHost);
    private final PathRenameSupport pathRenames = new PathRenameSupport(adapterHost);
    private final EditorEventsSupport editorEvents = new EditorEventsSupport(adapterHost);
    private final MenuContributions menus = new MenuContributions(adapterHost);
    private final EditorAssistSupport editorAssist = new EditorAssistSupport(adapterHost);
    private final GhostTextSupport ghostTextSupport = new GhostTextSupport(adapterHost);
    final JavaLexicalIndex lexicalIndex = new JavaLexicalIndex();
    final JavaFastCompletionProvider fastCompletion =
            new JavaFastCompletionProvider(lexicalIndex);
    final PomProperties pomProperties = new PomProperties(this::pomLocalRepository);
    final BuildFileCompletionProvider buildFileCompletion =
            new BuildFileCompletionProvider(new EditorDependencyCatalog(), pomProperties);
    private final EditorTheme theme = new JavaEditorTheme(() -> requestEditorThemeConfig("java"));
    final AtomicLong lifecycle = new AtomicLong();
    final AtomicLong navigationTicket = new AtomicLong();
    final AtomicLong navigationRequestTicket = new AtomicLong();
    final AtomicBoolean buildRunning = new AtomicBoolean();
    final AtomicBoolean debugActive = new AtomicBoolean();
    final PluginTaskExecutor background =
            new PluginTaskExecutor("java-orion-support");
    final AutoCompleteIdleTrigger autoCompleteIdle = new AutoCompleteIdleTrigger(
            CompletionEngine.AUTO_COMPLETE_IDLE_DELAY_MS,
            (task, delay) -> background.schedule(task, delay, TimeUnit.MILLISECONDS),
            completionEngine::isIdleCompletionEligible,
            completionEngine::isIdleCompletionReady,
            completionEngine::currentIdleCaret,
            completionEngine::fireIdleCompletion
    );

    private final Object lifecycleLock = new Object();
    volatile Path projectRoot;
    volatile JavaProjectDescriptor descriptor;
    private final JavaPathTransferRefactoring pathTransfers = new JavaPathTransferRefactoring(new PathTransferHost(adapterHost));
    private volatile IdeProjectContext projectContext;
    volatile JdkService jdkService;
    volatile JdkInstallation projectJdk;
    private volatile JdkManagerPanel jdkManagerPanel;

    volatile JavaLanguageServer jdtLs;
    volatile ClassFileSupport classFileUris;
    volatile boolean unloaded;
    volatile BuildSystem buildSystem;
    private volatile DependencyService dependencyService;
    private volatile DependencyManagerCoordinator dependencyCoordinator;
    volatile DependencyManagerPanel dependencyPanel;
    final AtomicReference<JavaTestRunner> activeTestRunner = new AtomicReference<>();
    final AtomicReference<Runnable> pendingTestDebug = new AtomicReference<>();
    volatile JavaTestExplorerPanel testPanel;
    volatile String testPanelId;
    final MavenPluginGoals pluginGoals = new MavenPluginGoals(this::pluginRepository);
    private volatile Path pluginRepositoryRoot;
    volatile Path pluginRepositoryPath;
    final TodoPanelHost todoSupport = new TodoPanelHost(adapterHost);
    private final AtomicLong treeIconRefreshTicket = new AtomicLong();
    private final AtomicBoolean treeIconRefreshAll = new AtomicBoolean();
    private final Set<Path> treeIconRefreshPaths = ConcurrentHashMap.newKeySet();
    final Set<Path> migratedBuildRunConfigurations = ConcurrentHashMap.newKeySet();
    volatile JavaProjectStructurePanel structurePanel;
    volatile JavaBuildToolsPanel buildToolsPanel;
    private volatile String buildToolsPanelId;
    volatile JavaPluginSettings settings;
    volatile IdeEditorContext activeJavaEditor;
    final Map<Path, IdeEditorContext> javaEditors = new ConcurrentHashMap<>();
    final Map<Path, String> diskBaseline = new ConcurrentHashMap<>();
    final Map<Path, String> lastEditorContents = new ConcurrentHashMap<>();
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

    void requestJavaTreeIconRefresh(Path file) {
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

    <T> T timed(String label, Supplier<T> operation) {
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

    void resolveProjectJdk(long ticket, Path root) {
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

    JavaLanguageServer ensureLanguageServer() {
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

    boolean current(long ticket, Path root) {
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
        return editorAssist.getSelectionRanges(context);
    }

    @Override
    public CompletableFuture<List<int[]>> getSelectionRanges(SelectionRangeContext context) {
        return editorAssist.getSelectionRanges(context);
    }

    @Override
    public CompletableFuture<HoverInfo> getHoverAsync(IdeHoverContext context) {
        return editorAssist.getHoverAsync(context);
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        return editorAssist.getHover(context);
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

    String readCurrentText(Path file) {
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

    IdeEditorContext editorContextFor(Path file) {
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

    static List<Location> uniqueLocations(List<Location> locations) {
        return JavaNavigation.unique(locations);
    }

    void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                 IdeEditorContext context, Point screen, Kind kind) {
        navigationViews.showUsagesPopup(locations, currentFile, currentText, context, screen, kind);
    }

    void navigateToLocation(Location location, Path path) {
        navigationViews.navigateToLocation(location, path);
    }

    void openAt(Path path, int line, int col) {
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
        return editorAssist.getInlayHintsAsync(context);
    }

    @Override
    public List<InlayHint> getInlayHints(IdeInlayHintContext context) {
        return editorAssist.getInlayHints(context);
    }

    @Override
    public boolean isSemanticTokensEnabled() {
        return true;
    }

    @Override
    public CompletableFuture<List<SemanticToken>> getSemanticTokensAsync(IdeSemanticTokensContext context) {
        return editorAssist.getSemanticTokensAsync(context);
    }

    @Override
    public List<SemanticToken> getSemanticTokens(IdeSemanticTokensContext context) {
        return editorAssist.getSemanticTokens(context);
    }

    @Override
    public String formatCode(FormatCodeContext context) {
        return editorAssist.formatCode(context);
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

    JavaLanguageServer interactiveServerFor(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !lsp.isInteractive() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    JavaLanguageServer runningServerFor(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !lsp.isReady() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    boolean isIndexing(Path filePath) {
        JavaLanguageServer lsp = jdtLs;
        return lsp != null && lsp.getState() == LanguageServerState.INDEXING
                && JavaProjectConventions.isJava(filePath);
    }

    boolean isSpringNavigationEnabled() {
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
        menus.contributeEditorMenu(menu, editorContext);
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

    @Override
    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menus.contributeMenuBar(menu);
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

    static JavaModule moduleContaining(JavaProjectDescriptor descriptor, Path file,
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

    JavaTestRunner newTestRunner(JavaProjectDescriptor current, BuildSystem build) {
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

    void ensureTestPanel() {
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

    void refreshProblemsPanel() {
        problemsSupport.refreshProblemsPanel();
    }

    private void syncWithDisk() {
        problemsSupport.syncWithDisk();
    }

    private void reanalyzeDiagnostics() {
        problemsSupport.reanalyzeDiagnostics();
    }

    void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
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

    void openBuildTools() {
        ensureBuildToolsPanel();
        if (buildToolsPanelId != null) {
            requestOpenToolPanel(buildToolsPanelId);
        }
    }

    java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module) {
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
        openCenterTab(CenterTabIds.DEPENDENCIES_TAB_ID, text("tab.dependencies", "Dependencias"), panel, true);
        switchToCenterTab(CenterTabIds.DEPENDENCIES_TAB_ID);
    }

    synchronized DependencyManagerCoordinator dependencyManagerHost() {
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

    void onBuildFileChanged(Path filePath) {
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
        openCenterTab(CenterTabIds.STRUCTURE_TAB_ID, text("tab.projectStructure", "Estrutura do projeto"),
                panel, true);
        switchToCenterTab(CenterTabIds.STRUCTURE_TAB_ID);
    }

    public void openJdkManager() {
        JdkManagerPanel panel = jdkManagerPanel;
        if (panel == null) {
            panel = new JdkManagerPanel(new JdkManagerSupport(adapterHost), this::createModernDialogBuilder);
            jdkManagerPanel = panel;
        } else {
            panel.reload();
        }
        openCenterTab(CenterTabIds.JDK_TAB_ID, text("tab.jdkManager", "JDKs"), panel, true);
        switchToCenterTab(CenterTabIds.JDK_TAB_ID);
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

    void restartLanguageServer() {
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

    JavaPluginSettings settings() {
        return ensureSettings();
    }

    synchronized BuildSystem ensureBuildSystem() {
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

    synchronized DependencyService ensureDependencyService() {
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

    synchronized JdkService ensureJdkService() {
        JdkService existing = jdkService;
        if (existing != null) {
            return existing;
        }
        JdkService created = new JdkService(getResource(), resolveDownloadObserver());
        jdkService = created;
        return created;
    }

    DownloadObserver resolveDownloadObserver() {
        try {
            return getService(DownloadObserver.class);
        } catch (Exception e) {
            log.debug("DownloadObserver indisponivel: {}", e.getMessage());
            return null;
        }
    }

    DownloadProgressListener progressListener() {
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
