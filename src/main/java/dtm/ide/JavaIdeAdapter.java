package dtm.ide;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.search.GlobalSearchMatch;
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
import dtm.ide.api.project.editor.IdeInlayHintContext;
import dtm.ide.api.project.editor.IdeRenameContext;
import dtm.ide.api.project.editor.IdeRenamePolicy;
import dtm.ide.api.project.editor.IdeRenamePrepareContext;
import dtm.ide.api.project.editor.IdeRenamePreparation;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.editor.IdeSemanticTokensContext;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeWordCaretContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.api.project.editor.EditorShortcutScope;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.ConditionalBreakpointContext;
import dtm.ide.api.project.editor.ConditionalBreakpointDialogView;
import dtm.ide.api.project.editor.NativeEditorType;
import dtm.ide.api.project.tree.PathRenameDecision;
import dtm.ide.api.project.tree.PathTransferDecision;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.api.project.tree.ProjectTreeIgnoreRule;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.project.diagnostics.IdeProblem;
import dtm.ide.api.project.diagnostics.ProblemsActionHandle;
import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildToolDebug;
import dtm.ide.build.BuildProgressTracker;
import dtm.ide.build.ClasspathValidation;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.incremental.IncrementalJavaBuilder;
import dtm.ide.build.incremental.ModuleBuildState;
import dtm.ide.build.BuildRunConfigurations;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.GradleBuildService;
import dtm.ide.build.MavenPluginGoals;
import dtm.ide.build.MavenBuildService;
import dtm.ide.build.StaticAnalysisReportParser;
import dtm.ide.build.BuildSystems;
import dtm.ide.build.BuildToolModel;
import dtm.ide.coverage.CoverageAgent;
import dtm.ide.coverage.CoverageDisplay;
import dtm.ide.coverage.CoverageGutter;
import dtm.ide.coverage.CoverageGutterLayer;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.CoverageReadResult;
import dtm.ide.coverage.CoverageStore;
import dtm.ide.coverage.FileCoverage;
import dtm.ide.coverage.JacocoExecReader;
import dtm.ide.concurrent.EdtStallWatchdog;
import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencyService;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.deps.OsvClient;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.PomProperties;
import dtm.ide.deps.MavenLocalRepositoryResolver;
import dtm.ide.editor.AutoCompleteIdleTrigger;
import dtm.stools.configs.UiTokens;
import dtm.stools.component.panels.editor.code.ghost.GhostTextActivationMode;
import dtm.stools.component.panels.editor.code.ghost.GhostTextSuggestion;
import dtm.ide.editor.CallParentheses;
import dtm.ide.editor.CompletionRanking;
import dtm.ide.editor.JavaTypingContext;
import dtm.ide.editor.JavaSnippetCompletionProvider;
import dtm.ide.editor.BuildFileCompletionProvider;
import dtm.ide.editor.JavaFastCompletionProvider;
import dtm.ide.editor.JavaImportInserter;
import dtm.ide.index.JavaLexicalIndex;
import dtm.ide.index.JavaLexicalSource;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Result;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.navigation.JavaNavigation.Extent;
import dtm.ide.editor.theme.JavaEditorTheme;
import dtm.ide.lsp.ImportCandidates;
import dtm.ide.lsp.JdtLsExtensionBundles;
import dtm.ide.lsp.JdtLsProvisioner;
import dtm.ide.lsp.JdtLsService;
import dtm.ide.lsp.JavaClassFileNavigation;
import dtm.ide.lsp.LombokAccessorRename;
import dtm.ide.lsp.LombokAccessors;
import dtm.ide.lsp.LombokAgentResolver;
import dtm.ide.lsp.LombokSupport;
import dtm.ide.lsp.LombokSupportStatus;
import dtm.ide.lsp.UsagesPopup;
import dtm.ide.debug.BuildToolDebugListener;
import dtm.ide.debug.BreakpointChanges;
import dtm.ide.debug.ConditionCompletionProvider;
import dtm.ide.debug.ConditionEditorSession;
import dtm.ide.debug.ConditionLanguageService;
import dtm.ide.debug.JavaAttachTarget;
import dtm.ide.debug.JavaDebugSession;
import dtm.ide.debug.JdwpRelay;
import dtm.ide.debug.JavaDebugSnapshot;
import dtm.ide.debug.JavaHotReloadService;
import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringBeanIndex;
import dtm.ide.spring.SpringDiagnostics;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringAnnotationCompletionProvider;
import dtm.ide.spring.SpringInjection;
import dtm.ide.spring.SpringEndpoint;
import dtm.ide.inspection.DiagnosticRanges;
import dtm.ide.inspection.InspectionSuppressionStore;
import dtm.ide.inspection.InspectionSuppressions;
import dtm.ide.inspection.DiagnosticTags;
import dtm.ide.inspection.JavaDiagnosticEdits;
import dtm.ide.inspection.JavaInspection;
import dtm.ide.spring.SpringNavigation;
import dtm.ide.spring.live.SpringActuatorClient;
import dtm.ide.spring.live.SpringRuntimeBeans;
import dtm.ide.spring.SpringSearchContributor;
import dtm.ide.spring.SpringPropertyUsage;
import dtm.ide.spring.SpringValueDiagnostics;
import dtm.ide.spring.config.SpringConfigDocument;
import dtm.ide.spring.config.SpringConfigIndex;
import dtm.ide.spring.config.SpringConfigProperty;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import dtm.ide.spring.JavaType;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaQueryCompletionProvider;
import dtm.ide.spring.infra.SpringInfraDiagnostics;
import dtm.ide.spring.jpa.JpaDiagnostics;
import dtm.ide.spring.jpa.JpqlDiagnostics;
import dtm.ide.spring.config.SpringConfigMetadata;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.ide.spring.live.SpringActuatorClient;
import dtm.ide.project.JavaModule;
import dtm.ide.run.JavaRunConfigurationContribution;
import dtm.ide.run.JavaRunSupport;
import dtm.ide.run.chain.RunChainHost;
import dtm.ide.run.ProcessLauncher;
import dtm.ide.run.RemoteDebugSettings;
import dtm.ide.run.BuildRunConfigurationBridge;
import dtm.ide.run.JavaRunTypes;
import dtm.ide.run.JavaRunValidation;
import dtm.ide.run.form.RunFormChoicesLoader;
import dtm.ide.run.form.RunFormContext;
import dtm.ide.run.DebugPorts;
import dtm.ide.run.MainClassScanner;
import dtm.ide.api.project.IdeProjectFileWatcher;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.LanguageLevelEditor;
import dtm.ide.project.ProjectLayout;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectSources;
import dtm.ide.project.JdtOutputIsolation;
import dtm.ide.refactor.JavaPathTransferPlan;
import dtm.ide.refactor.MavenModuleRename;
import dtm.ide.refactor.JavaPathTransferRefactoring;
import dtm.ide.refactor.JavaSafeDeleteScanner;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.settings.JdtBuildMode;
import dtm.ide.settings.JavaSettingsPage;
import dtm.ide.settings.HotReloadMode;
import dtm.ide.test.JUnitTestDiscovery;
import dtm.ide.test.JavaTest;
import dtm.ide.test.JavaTestRunner;
import dtm.ide.test.JUnitPlatformLauncher;
import dtm.ide.test.JavaTestProblems;
import dtm.ide.test.JavaSemanticTestDiscovery;
import dtm.ide.ui.DependencyManagerPanel;
import dtm.ide.ui.JavaSourceActionDialogs;
import dtm.ide.ui.ImportChoicePanel;
import dtm.ide.ui.JavaCoveragePanel;
import dtm.ide.ui.JavaTestExplorerPanel;
import dtm.ide.ui.JavaTestGutterLayer;
import dtm.ide.ui.JavaDebugPanel;
import dtm.ide.ui.JavaCopyDialogPanel;
import dtm.ide.ui.JavaModuleRenameDialogPanel;
import dtm.ide.ui.JavaDeleteDialogPanel;
import dtm.ide.ui.JavaMoveDialogPanel;
import dtm.ide.todo.TodoItem;
import dtm.ide.todo.TodoScanner;
import dtm.ide.ui.JavaBuildToolsPanel;
import dtm.ide.ui.BuildPromptPanel;
import dtm.ide.ui.ConditionalBreakpointHints;
import dtm.ide.ui.JavaProjectStructurePanel;
import dtm.ide.ui.JavaTodoPanel;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaProjectTreeIcons;
import dtm.ide.ui.JavaTypeCreationPanel;
import dtm.ide.ui.RepositoryCreationPanel;
import dtm.ide.ui.JavaDebugValuePopup;
import dtm.ide.ui.JavaEvaluateDialog;
import dtm.ide.ui.SpringExplorerPanel;
import dtm.ide.ui.JdkManagerPanel;
import dtm.ide.wizard.JavaFileTemplates;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.stools.component.menu.bar.tree.MenuNode;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.popup.ModernInputDialog;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.CommandHandler;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.menu.popup.ActionMenu;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.codelens.CodeLensClickEvent;
import dtm.stools.component.panels.editor.code.codelens.CodeLensItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationContext;
import dtm.stools.component.panels.editor.code.hover.HoverDocumentationProvider;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.popup.ModernDialog;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenClassifierProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenColorProvider;
import dtm.stools.component.panels.editor.code.provider.def.DefaultTokenRenderProvider;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Singleton
@PluginReference(id = "java-ide-adapter")
public class JavaIdeAdapter extends IdeAdapter {

    private static final String DISABLE_INSPECTION_COMMAND = "java.orion.disableInspection";

    private static final String HIDE_OCCURRENCE_COMMAND = "java.orion.hideInspectionOccurrence";

    private static final long SLOW_OPERATION_THRESHOLD_MS = 100;
    private static final long AUTO_COMPLETE_IDLE_DELAY_MS = 150;
    private static final long DELETE_REFERENCES_TIMEOUT_MS = 30_000;
    private static final int DELETE_SEARCH_PARALLELISM = 4;
    private static final Set<Character> COMPLETION_TRIGGER_CHARACTERS = Set.of('.', '@', '(', ':', '$');
    private static final long COVERAGE_POLL_INTERVAL_MS = 400L;
    private static final long COVERAGE_SETTLE_TIMEOUT_MS = 5000L;
    private static final int GHOST_TEXT_IDLE_DELAY_MS = 1_000;
    private static final int CODE_LENS_TOOLTIP_TARGETS = 8;
    private static final int NAVIGATION_RETRIES = 2;
    private static final long NAVIGATION_RETRY_DELAY_MS = 80;
    private static final long PROBLEMS_REFRESH_DELAY_MS = 200;
    private static final String BUILD_PROBLEMS_OWNER = "java.build";
    private static final String LSP_PROBLEMS_OWNER = "java.lsp";
    private volatile ProblemsActionHandle clearBuildAction;
    private static final long RENAME_WAIT_BUDGET_MS = 60_000;
    private static final long PROJECT_CONFIGURATION_REQUEST_DELAY_MS = 1_500;
    private static final long PROJECT_CONFIGURATION_REQUEST_COOLDOWN_MS = 10_000;
    private static final String RENAME_PROGRESS_ID = "javaRenameWait";
    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");
    private static final String BUILD_PROGRESS_ID = "javaBuild";
    private static final String RUN_BUILD_PROGRESS_ID = "javaRunBuild";
    private static final String TEST_DEBUG_PROGRESS_ID = "javaTestDebugBuild";
    private static final long LSP_DEBUG_POLL_MS = 250;
    private static final String STARTUP_BUILD_PROGRESS_ID = "javaStartupBuild";
    private static final long STARTUP_BUILD_LSP_WAIT_MS = 180_000;
    private static final long STARTUP_BUILD_LSP_POLL_MS = 500;
    private static final long BUILD_SLOT_WAIT_MS = 600_000;
    private static final long BUILD_SLOT_POLL_MS = 100;
    private static final long PASTE_IMPORT_WINDOW_MS = 20_000;
    private static final long PASTE_IMPORT_RETRY_MS = 1_000;
    private static final long PASTE_IMPORT_FOLLOW_UP_MS = 5_000;
    private static final int PASTE_IMPORT_MAX_ROUNDS = 3;
    private static final Pattern TYPE_LIKE_NAME = Pattern.compile("\\b\\p{Lu}");

    private record PendingPasteImport(Path file, int offset, String pasted, Range range, long deadline,
                                      int round, Set<String> handled) {
    }

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(JavaIdeAdapter.class, key, fallback);
    }

    private static final String JDK_TAB_ID = "javaJdkManager";

    private static final String LSP_PROGRESS_ID = "javaLanguageServer";
    private static final String RENAME_COMPUTE_PROGRESS_ID = "javaRename";
    private static final String LSP_WORK_PROGRESS_ID = "javaLanguageServerWork";
    private static final String NAVIGATION_PROGRESS_ID = "javaNavigation";
    private static final String SYNC_PROGRESS_ID = "javaProjectSync";
    private static final long SYNC_WORK_START_GRACE_MS = 3_000;
    private static final long SYNC_WORK_MAX_MS = 120_000;
    private static final String DEPENDENCIES_TAB_ID = "javaDependencies";
    private static final Color DEBUG_LINE_COLOR = new Color(227, 100, 100, 80);
    private static final Pattern SNIPPET_DEFAULT = Pattern.compile("\\$\\{\\d+:([^}]*)}");
    private static final Pattern SNIPPET_PLACEHOLDER = Pattern.compile("\\$\\{?\\d+}?");
    private static final List<String> JAVA_GHOST_KEYWORDS = List.of(
            "this", "throw", "throws", "true", "try", "return", "public", "private",
            "protected", "static", "final", "class", "interface", "record", "extends",
            "implements", "new", "null", "super", "switch", "synchronized", "instanceof",
            "import", "package", "void", "boolean");

    private final JavaEditorRegistry editors = new JavaEditorRegistry();
    private final MavenCentralClient mavenCentral = new MavenCentralClient();
    private final SpringBeanIndex springIndex = new SpringBeanIndex();
    private final SpringActuatorClient actuator = new SpringActuatorClient();
    private final BuildProblemsCoordinator problems = new BuildProblemsCoordinator();
    private final JavaSnippetCompletionProvider snippets = new JavaSnippetCompletionProvider();
    private final JavaLexicalIndex lexicalIndex = new JavaLexicalIndex();
    private final JavaFastCompletionProvider fastCompletion =
            new JavaFastCompletionProvider(lexicalIndex);
    private final AtomicLong problemsRefreshTicket = new AtomicLong();
    private final AtomicBoolean renameWaitCanceled = new AtomicBoolean();
    private final AtomicBoolean diagnosticReanalysisRunning = new AtomicBoolean();
    private final PomProperties pomProperties = new PomProperties(this::pomLocalRepository);
    private final BuildFileCompletionProvider buildFileCompletion =
            new BuildFileCompletionProvider(new EditorDependencyCatalog(), pomProperties);
    private final EditorTheme theme = new JavaEditorTheme(() -> requestEditorThemeConfig("java"));
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicLong wordCaretTicket = new AtomicLong();
    private final AtomicReference<CodeActionPrefetch> codeActionPrefetch = new AtomicReference<>();
    private final AtomicLong navigationTicket = new AtomicLong();
    private final AtomicLong navigationRequestTicket = new AtomicLong();
    private final AtomicLong hotReloadTicket = new AtomicLong();
    private final AtomicLong debugHoverTicket = new AtomicLong();
    private final AtomicLong debugLineTicket = new AtomicLong();
    private final AtomicInteger lspProgress = new AtomicInteger();
    private final AtomicBoolean buildRunning = new AtomicBoolean();
    private final AtomicBoolean debugActive = new AtomicBoolean();
    private final Map<RunConfigurationKey, RunProcessHandle> runningProcesses =
            new ConcurrentHashMap<>();
    private final Set<Path> debugSteppedFiles = ConcurrentHashMap.newKeySet();
    private final PluginTaskExecutor background =
            new PluginTaskExecutor("java-orion-support");
    private final AutoCompleteIdleTrigger autoCompleteIdle = new AutoCompleteIdleTrigger(
            AUTO_COMPLETE_IDLE_DELAY_MS,
            (task, delay) -> background.schedule(task, delay, TimeUnit.MILLISECONDS),
            this::isIdleCompletionEligible,
            this::isIdleCompletionReady,
            this::currentIdleCaret,
            this::fireIdleCompletion
    );

    private static final Color DELETE_ACCENT = new Color(220, 53, 69);
    
    private volatile Path projectRoot;
    private volatile JavaProjectDescriptor descriptor;
    private final JavaPathTransferRefactoring pathTransfers = new JavaPathTransferRefactoring(new PathTransferHost());
    private volatile IdeProjectContext projectContext;
    private volatile JdkService jdkService;
    private volatile JdkInstallation projectJdk;
    private volatile JdtBuildMode appliedBuildMode;
    private volatile JdkManagerPanel jdkManagerPanel;
    private static final String STRUCTURE_TAB_ID = "javaProjectStructure";

    private static final long TODO_DEBOUNCE_MS = 400;

    private static final String IDE_MENU_ID_NEW = "tree.new";

    private static final int IDE_NEW_MENU_INDEX = 4;

    private static final String MENU_ID_NEW_JAVA = "java.tree.new";
    private static final String MENU_ID_BUILD_MODULE = "java.tree.buildModule";
    private static final String MENU_ID_SYNC = "java.tree.sync";
    private static final String MENU_ID_ADD_DEPENDENCY = "java.tree.addDependency";
    private static final String MENU_ID_RELOAD = "java.tree.reload";
    private static final String MENU_ID_MARK_DIRECTORY = "java.tree.markDirectory";

    private volatile JdtLsService jdtLs;
    private volatile boolean unloaded;
    private volatile LombokAgentResolver lombokResolver;
    private final LombokSupport lombokSupport = new LombokSupport(this::onLombokStatusChanged);
    private volatile BuildSystem buildSystem;
    private volatile DependencyService dependencyService;
    private volatile DependencyManagerCoordinator dependencyCoordinator;
    private volatile DependencyManagerPanel dependencyPanel;
    private volatile JavaRunSupport runSupport;
    private final AtomicReference<BuildProgressTracker> runBuildProgress = new AtomicReference<>();
    private final AtomicReference<IncrementalJavaBuilder> startupBuilder = new AtomicReference<>();
    private final AtomicReference<JavaTestRunner> activeTestRunner = new AtomicReference<>();
    private final AtomicReference<Runnable> pendingTestDebug = new AtomicReference<>();
    private volatile JavaDebugSession debugSession;
    private volatile JavaDebugPanel debugPanel;
    private volatile String debugPanelId;
    private volatile JavaHotReloadService hotReloadService;
    private volatile IdeEditorContext debugLineContext;
    private volatile int debugLine = -1;
    private final Map<String, String> debugLibrarySources = new ConcurrentHashMap<>();
    private volatile CodeEditor debugLibraryEditor;
    private volatile String debugLibraryUri;
    private volatile int debugLibraryLine = -1;
    private final EdtStallWatchdog debugEdtWatchdog = new EdtStallWatchdog("depuracao Java");
    private final AtomicLong debugStartGeneration = new AtomicLong();
    private volatile JavaModule debugModule;
    private volatile Runnable debuggeeTerminator;
    private volatile JdwpRelay debugRelay;
    private volatile RunFormChoicesLoader runFormChoicesLoader;
    private volatile RunProcessHandle debugProcessHandle;
    private volatile JavaTestExplorerPanel testPanel;
    private final CoverageStore coverageStore = new CoverageStore();
    private volatile CoverageProvisioner coverageProvisioner;
    private volatile String testPanelId;
    private final AtomicBoolean buildToolsSyncPending = new AtomicBoolean();
    private final Set<Path> pendingModuleDirectoryRenames = ConcurrentHashMap.newKeySet();
    private final AtomicLong syncGeneration = new AtomicLong();
    private final AtomicReference<SyncWork> syncWork = new AtomicReference<>();
    private final MavenPluginGoals pluginGoals = new MavenPluginGoals(this::pluginRepository);
    private volatile Path pluginRepositoryRoot;
    private volatile Path pluginRepositoryPath;
    private final AtomicBoolean naturalDebugEnd = new AtomicBoolean();
    private final Map<Path, ConditionEditorSession> conditionSessions = new ConcurrentHashMap<>();
    private final AtomicReference<ConditionEditorSession> activeConditionSession = new AtomicReference<>();
    private final TodoScanner todoScanner = new TodoScanner();
    private final AtomicLong todoRefreshTicket = new AtomicLong();
    private final AtomicLong treeIconRefreshTicket = new AtomicLong();
    private final AtomicBoolean treeIconRefreshAll = new AtomicBoolean();
    private final Set<Path> treeIconRefreshPaths = ConcurrentHashMap.newKeySet();
    private final Set<Path> migratedBuildRunConfigurations = ConcurrentHashMap.newKeySet();
    private volatile JavaTodoPanel todoPanel;
    private volatile String todoPanelId;
    private volatile JavaProjectStructurePanel structurePanel;
    private volatile JavaBuildToolsPanel buildToolsPanel;
    private volatile String buildToolsPanelId;
    private volatile SpringExplorerPanel springPanel;
    private volatile String springPanelId;
    private volatile SpringConfigMetadata springMetadata = SpringConfigMetadata.builtIn();
    private volatile SpringConfigIndex springConfigIndex = SpringConfigIndex.empty();
    private volatile InspectionSuppressionStore suppressionStore;
    private volatile JavaFileChangeRouter fileChangeRouter;
    private volatile IdeProjectFileWatcher projectFileWatcher;
    private volatile String fileWatcherListenerId;
    private volatile String springBaseUrl = JavaPluginSettings.DEFAULT_SPRING_BASE_URL;
    private volatile JavaPluginSettings settings;
    private volatile Object codeActionLampHandle;
    private volatile IdeEditorContext codeActionLampContext;
    private volatile IdeEditorContext activeJavaEditor;
    private final Map<Path, IdeEditorContext> javaEditors = new ConcurrentHashMap<>();
    private final Map<Path, PendingPasteImport> pendingPasteImports = new ConcurrentHashMap<>();
    private final Set<Path> pasteImportsResolving = ConcurrentHashMap.newKeySet();
    private final Map<Path, String> diskBaseline = new ConcurrentHashMap<>();
    private final Map<Path, String> lastEditorContents = new ConcurrentHashMap<>();
    private final AtomicLong lastConfigurationUpdateRequest = new AtomicLong();
    private final AtomicBoolean languageServerReadyHandled = new AtomicBoolean();
    private final AtomicBoolean languageServerWorkVisible = new AtomicBoolean();
    private volatile RunConfigurationData selectedRunConfig;
    private volatile List<RunConfigurationData> staticRunConfigurations = List.of();
    private volatile JavaDebugValuePopup debugValuePopup;

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
        lifecycle.incrementAndGet();
        background.cancelPending();
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            lsp.resetProjectState();
            lsp.stopAsync();
        }

        descriptor = null;
        projectRoot = null;
        projectContext = null;
        projectJdk = null;
        buildSystem = null;
        dependencyService = null;
        runSupport = null;
        runBuildProgress.set(null);
        cancelStartupBuild();
        hideProgress(STARTUP_BUILD_PROGRESS_ID);
        closeDebugSession();
        activeJavaEditor = null;
        selectedRunConfig = null;
        staticRunConfigurations = List.of();
        unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        if (coordinator != null) {
            coordinator.resetLocalRepository();
        }
        lexicalIndex.clear();
        springIndex.clear();
        todoScanner.clear();
        if (todoPanel != null) {
            todoPanel.setItems(List.of(), null);
        }
        RunFormChoicesLoader choicesLoader = runFormChoicesLoader;
        if (choicesLoader != null) {
            choicesLoader.invalidate();
        }
        buildToolsSyncPending.set(false);
        springConfigIndex = SpringConfigIndex.empty();
        springMetadata = SpringConfigMetadata.builtIn();
        problems.clearAll();
        diagnosticReanalysisRunning.set(false);
        refreshProblemsPanel();
        clearCoverage();
        detachAllCoverageGutters();
        javaEditors.clear();
        diskBaseline.clear();
        lastEditorContents.clear();
        lspProgress.set(0);
        hideProgress(LSP_PROGRESS_ID);
        syncGeneration.incrementAndGet();
        syncWork.set(null);
        hideProgress(SYNC_PROGRESS_ID);
        JavaBuildToolsPanel toolsPanel = buildToolsPanel;
        if (toolsPanel != null) {
            toolsPanel.setSyncing(false);
        }
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        clearStatusBarText();
    }

    @Override
    public void onUnload() {
        unloaded = true;
        lifecycle.incrementAndGet();
        cancelStartupBuild();
        background.close();
        dtm.ide.run.OwnedRunProcesses.shutdownAll();
        debugStartGeneration.incrementAndGet();
        closeDebugRelay();
        JavaDebugSession session = debugSession;
        debugSession = null;
        debugActive.set(false);
        debugEdtWatchdog.stop();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException error) {
                log.warn("Falha ao fechar a sessao de debug no unload", error);
            }
        }
        runDebuggeeTerminator(takeDebuggeeTerminator());
        ProblemsActionHandle action = clearBuildAction;
        clearBuildAction = null;
        if (action != null) {
            action.unregister();
        }
        JdtLsService lsp;
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
        unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        dependencyCoordinator = null;
        if (coordinator != null) {
            coordinator.close();
        }
        autoCompleteIdle.cancel();
        springIndex.shutdown();
        lexicalIndex.shutdown();
        dtm.ide.build.JavacDaemons.shutdownAll();
    }

    @Override
    public void clearCaches() {
        log.info("clearCaches chamado na thread {}", Thread.currentThread().getName());
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        long ticket = lifecycle.incrementAndGet();
        buildSystem = null;
        dependencyService = null;
        problems.clearAll();
        refreshProblemsPanel();
        clearCoverage();
        JdtLsService lsp = jdtLs;

        background.submit(() -> {
            JavaProjectDescriptor described = timed("describe(clearCaches)",
                    () -> JavaProjectConventions.describe(root));
            if (!current(ticket, root)) {
                return;
            }
            descriptor = described;
            staticRunConfigurations = buildStaticRunConfigurations(described);
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
            setupSpring(ticket, root, sources);
            SwingUtilities.invokeLater(() -> refreshUiAfterDescribe(ticket, root));
        });
    }

    private void bind(IdeProjectContext context) {
        long started = System.nanoTime();
        String callerThread = Thread.currentThread().getName();
        log.info("bind iniciado na thread {}", callerThread);
        Path nextRoot = context == null ? null
                : context.getProjectPath().map(JavaProjectConventions::normalize).orElse(null);
        this.projectContext = context;
        if (nextRoot != null && nextRoot.equals(projectRoot) && descriptor != null) {
            refreshRunButtonsForCurrentFile();
            logSlowBind(started, callerThread);
            return;
        }
        this.projectRoot = nextRoot;
        this.descriptor = null;
        this.staticRunConfigurations = List.of();
        problems.clearAll();
        refreshProblemsPanel();
        long ticket = lifecycle.incrementAndGet();
        Path root = nextRoot;
        if (root == null) {
            refreshRunButtonsForCurrentFile();
            logSlowBind(started, callerThread);
            return;
        }
        registerFileWatcher();
        requestJavaTreeIconRefresh(null);

        if (settings().getLanguageServerMode().startsServer()) {
            String loading = text("status.startingLsp",
                    "Java: IntelliSense local pronto; iniciando analise semantica...");
            lspProgress.set(1);
            showProgress(LSP_PROGRESS_ID, loading);
            updateProgress(LSP_PROGRESS_ID, loading, 1);
        }
        background.submit(() -> {
            try {
                JavaProjectDescriptor described = timed("describe(bind)",
                        () -> JavaProjectConventions.describe(root));
                if (!current(ticket, root)) {
                    return;
                }
                descriptor = described;
                staticRunConfigurations = buildStaticRunConfigurations(described);
                if (described == null) {
                    SwingUtilities.invokeLater(() -> {
                        if (current(ticket, root)) {
                            hideProgress(LSP_PROGRESS_ID);
                            refreshRunButtonsForCurrentFile();
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
                setupSpring(ticket, root, sources);
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
        refreshRunButtonsForCurrentFile();
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
                hideProgress(LSP_PROGRESS_ID);
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
            background.submit(() -> startupBuild(ticket, root));
        });
    }

    private void startupBuild(long ticket, Path root) {
        if (!settings().isBuildOnProjectOpen() || !current(ticket, root)) {
            return;
        }
        JavaProjectDescriptor current = descriptor;
        BuildSystem build = ensureBuildSystem();
        if (current == null || build == null || current.rootModule() == null) {
            return;
        }
        if (isStartupBuildCached(current, build)) {
            setStatusBarText(text("status.buildCached", "Java: build em cache (sem mudancas)"));
            return;
        }
        awaitLanguageServerBeforeBuild(ticket, root);
        if (!current(ticket, root) || build.isRunning() || !buildRunning.compareAndSet(false, true)) {
            return;
        }
        if (isStartupBuildCached(current, build)) {
            buildRunning.set(false);
            setStatusBarText(text("status.buildCached", "Java: build em cache (sem mudancas)"));
            return;
        }

        BuildProgressTracker progress = new BuildProgressTracker(
                text("progress.buildingModule", "Buildando modulo"), current, null,
                update -> updateProgress(STARTUP_BUILD_PROGRESS_ID,
                        update.label(), update.percent()));
        BuildProgressTracker.Update initial = progress.initial();
        showProgress(STARTUP_BUILD_PROGRESS_ID, initial.label(), true, this::cancelStartupBuild);
        if (initial.percent() >= 0) {
            updateProgress(STARTUP_BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        try {
            BuildResult result = runStartupBuild(current, build, progress);
            if (!current(ticket, root)) {
                return;
            }
            publishBuildDiagnostics(result, true);
            setStatusBarText(result.summary().isEmpty() ? "" : "Java: " + result.summary());
        } finally {
            startupBuilder.set(null);
            BuildProgressTracker.Update completed = progress.completed();
            updateProgress(STARTUP_BUILD_PROGRESS_ID, completed.label(), completed.percent());
            hideProgress(STARTUP_BUILD_PROGRESS_ID);
            buildRunning.set(false);
        }
    }

    private boolean isStartupBuildCached(JavaProjectDescriptor current, BuildSystem build) {
        if (!settings().isIncrementalBuild()) {
            return false;
        }
        try {
            return new IncrementalJavaBuilder(current, () -> build, this::getProjectJdk)
                    .isUpToDate(current.rootModule());
        } catch (Exception e) {
            log.debug("Falha ao verificar o cache do build incremental: {}", e.getMessage());
            return false;
        }
    }

    private void awaitLanguageServerBeforeBuild(long ticket, Path root) {
        JdtLsService lsp = jdtLs;
        if (lsp == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_BUILD_LSP_WAIT_MS);
        while (current(ticket, root) && System.nanoTime() < deadline) {
            JdtLsService.State state = lsp.getState();
            if (state != JdtLsService.State.STARTING && state != JdtLsService.State.INDEXING) {
                return;
            }
            lsp.awaitReady(STARTUP_BUILD_LSP_POLL_MS);
        }
    }

    private BuildResult runStartupBuild(JavaProjectDescriptor current, BuildSystem build,
                                        BuildProgressTracker progress) {
        JavaModule rootModule = current.rootModule();
        if (settings().isIncrementalBuild()) {
            IncrementalJavaBuilder builder =
                    new IncrementalJavaBuilder(current, this::ensureBuildSystem, this::getProjectJdk)
                            .withModuleListener(progress::moduleStarted);
            if (builder.isApplicable(rootModule)) {
                startupBuilder.set(builder);
                return builder.build(rootModule, false, progress);
            }
        }
        return executeBuild(BuildSystem.BuildAction.COMPILE, build, null, progress);
    }

    private void cancelStartupBuild() {
        IncrementalJavaBuilder builder = startupBuilder.getAndSet(null);
        if (builder != null) {
            builder.cancel();
        }
        BuildSystem build = buildSystem;
        if (build != null && build.isRunning()) {
            build.cancel();
        }
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
        if (!settings().getLanguageServerMode().startsServer()) {
            setStatusBarText(text("status.lspDisabled",
                    "Java: IntelliSense desligado nas preferencias"));
            return;
        }
        JdtLsService lsp = ensureLanguageServer();
        if (lsp.isInteractive() && root.equals(lsp.getProjectRoot())) {
            return;
        }
        JavaProjectDescriptor current = descriptor;
        JdtBuildMode buildMode = settings().getJdtBuildMode();
        lsp.setBuildMode(buildMode);
        appliedBuildMode = buildMode;
        if (buildMode.isAutobuild() && JdtOutputIsolation.ensure(current)) {
            setStatusBarText(text("status.jdtOutputIsolated",
                    "Java: saida do autobuild isolada em") + " "
                    + JdtOutputIsolation.BUILD_DIRECTORY);
        }
        lsp.setSpringSupport(current != null && current.spring() && settings().isSpringSupport());
        applyLombokAgent(lsp, current);
        String loading = text("status.startingLsp", "Java: carregando IntelliSense...");
        lspProgress.set(5);
        showProgress(LSP_PROGRESS_ID, loading);
        updateProgress(LSP_PROGRESS_ID, loading, 5);
        lsp.start(root, jdk, progressListener()).whenComplete((unused, error) -> {
            hideProgress(LSP_PROGRESS_ID);
            if (!current(ticket, root)) {
                if (!root.equals(projectRoot)) {
                    lsp.stopAsyncIfBoundTo(root);
                }
                finishDiagnosticReanalysis(ticket, root, false);
                return;
            }
            if (error != null) {
                log.warn("IntelliSense Java indisponivel", error);
                setStatusBarText(text("status.lspUnavailable", "Java: IntelliSense indisponivel")
                        + " - " + rootMessage(error));
                JavaProjectDescriptor activeDescriptor = descriptor;
                if (activeDescriptor != null && activeDescriptor.spring()
                        && settings().isSpringSupport()) {
                    loadSpringMetadata(ticket, root);
                }
            }
            finishDiagnosticReanalysis(ticket, root, error == null && lsp.isReady());
        });
    }

    private synchronized JdtLsService ensureLanguageServer() {
        if (unloaded) {
            throw new IllegalStateException("plugin Java descarregado");
        }
        JdtLsService existing = jdtLs;
        if (existing != null) {
            return existing;
        }
        JdkService jdks = ensureJdkService();
        SdkDownloader downloader = new SdkDownloader(resolveDownloadObserver());
        JdtLsProvisioner provisioner = new JdtLsProvisioner(downloader, jdks.sdkRoot());
        JdtLsExtensionBundles extensionBundles = new JdtLsExtensionBundles(downloader, jdks.sdkRoot());

        JdtLsService created = new JdtLsService(jdks, provisioner, extensionBundles,
                this::onLspDiagnosticsPublished);
        created.setMaxHeap(settings().getLanguageServerMemory());
        created.setStatusListener(this::publishLanguageServerStatus);
        created.setWorkListener(this::publishLanguageServerWork);
        created.setCodeLensRefreshListener(path -> {
            if (path == null || !conditionSessions.containsKey(path.toAbsolutePath().normalize())) {
                requestRefreshCodeLenses(path);
            }
        });
        created.setWarmUpCompleteListener(this::refreshJavaEditorsAfterIndexing);
        created.setLateCompletionListener(this::onLateCompletion);
        created.setDocumentUpgradeListener(this::onLanguageServerDocumentUpgrade);
        languageServerReadyHandled.set(false);
        jdtLs = created;
        return created;
    }

    private void onLspDiagnosticsPublished(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        ConditionEditorSession conditionSession = conditionSessions.get(normalized);
        if (conditionSession != null) {
            conditionSession.diagnosticsPublished();
            return;
        }
        JdtLsService lsp = jdtLs;
        if (lsp != null && lsp.isReady()) {
            problems.supersedeCompilerProblems(normalized);
        }
        requestRefreshDiagnostics(path);
        resolvePastedImports(path);
        List<BuildDiagnostic> problems = lsp == null ? List.of() : lsp.diagnostics(normalized)
                .stream()
                .map(diagnostic -> new BuildDiagnostic(normalized,
                        diagnostic.startLine() + 1, diagnostic.startCol() + 1,
                        diagnostic.severity(), diagnostic.message(), diagnostic.source()))
                .toList();
        this.problems.publishLive(normalized, problems);
        refreshProblemsPanel();
    }

    private boolean applyLombokAgent(JdtLsService lsp, JavaProjectDescriptor current) {
        if (lsp == null) {
            return false;
        }
        if (!settings().isLombokSupport()) {
            lombokSupport.update(LombokSupportStatus.DISABLED, "");
            return lsp.setLombokAgentJar(null);
        }
        try {
            LombokAgentResolver.Agent agent = ensureLombokResolver()
                    .resolveAgent(current, resolvedClasspath(lsp, current));
            if (!agent.declared()) {
                lombokSupport.update(LombokSupportStatus.NOT_USED, "");
                return lsp.setLombokAgentJar(null);
            }
            if (!agent.isUsable()) {
                lombokSupport.update(LombokSupportStatus.ERROR, agent.failure());
                return lsp.setLombokAgentJar(null);
            }
            lombokSupport.update(LombokSupportStatus.STARTING,
                    agent.version() == null ? LombokAgentResolver.TESTED_VERSION : agent.version());
            return lsp.setLombokAgentJar(agent.jar());
        } catch (Exception e) {
            lombokSupport.update(LombokSupportStatus.ERROR, rootMessage(e));
            log.warn("Falha ao resolver o agente do Lombok: {}", rootMessage(e));
            return false;
        }
    }

    public LombokSupportStatus getLombokSupportStatus() {
        return lombokSupport.status();
    }

    public void setLombokSupportListener(LombokSupport.Listener listener) {
        lombokSupport.setListener(listener == null ? this::onLombokStatusChanged : listener);
    }

    private List<Path> resolvedClasspath(JdtLsService lsp, JavaProjectDescriptor current) {
        if (lsp == null || current == null || !lsp.isInteractive()) {
            return List.of();
        }
        return lsp.runtimeClasspath(current.root())
                .map(classpath -> {
                    if (ClasspathValidation.hasMissingJar(classpath)) {
                        requestJdtLsProjectConfigurationRefresh(lsp);
                        return List.<Path>of();
                    }
                    return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                            .filter(entry -> !entry.isBlank())
                            .map(Path::of)
                            .toList();
                })
                .orElse(List.of());
    }

    private void requestJdtLsProjectConfigurationRefresh() {
        requestJdtLsProjectConfigurationRefresh(jdtLs);
    }

    private void requestJdtLsProjectConfigurationRefresh(JdtLsService lsp) {
        if (lsp != null && lsp.isInteractive()) {
            lsp.projectConfigurationUpdate();
        }
    }

    private void onLombokStatusChanged(LombokSupportStatus status, String detail) {
        log.debug("Lombok: estado {} ({})", status, detail);
        switch (status) {
            case ACTIVE -> setStatusBarText(text("status.lombokActive", "Java: Lombok ativo")
                    + (detail == null || detail.isBlank() ? "" : " - " + detail));
            case ERROR -> notifyLombokFailure(detail);
            default -> {
            }
        }
    }

    private void notifyLombokFailure(String detail) {
        String message = text("notification.lombokMessage",
                "Lombok foi detectado no projeto, mas o agente nao pode ser carregado. "
                        + "Getters, setters e builders podem aparecer como erro.");
        setStatusBarText(text("status.lombokError", "Java: Lombok detectado sem agente ativo"));
        createNotification(NotificationContext.builder()
                .title(text("notification.lombokTitle", "Lombok indisponivel"))
                .message(detail == null || detail.isBlank() ? message : message + " (" + detail + ")")
                .icon(JavaIcons.java(JavaIcons.SMALL))
                .build());
    }

    private void refreshLombokStatusAfterServerState(JdtLsService.State state) {
        LombokSupportStatus current = lombokSupport.status();
        if (current != LombokSupportStatus.STARTING && current != LombokSupportStatus.ACTIVE) {
            return;
        }
        if (state == JdtLsService.State.READY) {
            lombokSupport.update(LombokSupportStatus.ACTIVE, lombokSupport.detail());
            return;
        }
        if (state == JdtLsService.State.ERROR) {
            lombokSupport.update(LombokSupportStatus.ERROR, lombokSupport.detail());
        }
    }

    private synchronized LombokAgentResolver ensureLombokResolver() {
        LombokAgentResolver existing = lombokResolver;
        if (existing != null) {
            return existing;
        }
        LombokAgentResolver created = new LombokAgentResolver(ensureJdkService().sdkRoot());
        lombokResolver = created;
        return created;
    }

    private void onLanguageServerDocumentUpgrade(Path path) {
        if (path == null) {
            return;
        }
        requestRefreshInlayHints(path);
        requestRefreshSemanticTokens(path);
        requestRefreshCodeLenses(path);
    }

    private void publishLanguageServerStatus(String message, int percent) {
        JdtLsService lsp = jdtLs;
        JdtLsService.State state = lsp == null ? JdtLsService.State.NOT_STARTED : lsp.getState();
        refreshLombokStatusAfterServerState(state);
        if (state == JdtLsService.State.STARTING || state == JdtLsService.State.INDEXING) {
            int effectivePercent = percent >= 0 ? Math.max(1, Math.min(99, percent)) : -1;
            lspProgress.set(Math.max(0, effectivePercent));
            String label = message == null || message.isBlank() || message.strip().equals("Java:")
                    ? text("status.indexing",
                            "Java: indexando - navegacao e autocomplete aproximados disponiveis")
                    : message;
            updateProgress(LSP_PROGRESS_ID, label, effectivePercent, true,
                    this::cancelLanguageServerIndexing);
            return;
        }
        if (state == JdtLsService.State.READY) {
            lspProgress.set(100);
            updateProgress(LSP_PROGRESS_ID, message, 100);
            hideProgress(LSP_PROGRESS_ID);
            if (languageServerReadyHandled.compareAndSet(false, true)) {
                Path root = projectRoot;
                JavaProjectDescriptor current = descriptor;
                if (root != null && current != null && current.spring()
                        && settings().isSpringSupport()) {
                    loadSpringMetadata(lifecycle.get(), root);
                }
            }
            return;
        }
        hideProgress(LSP_PROGRESS_ID);
        publishLanguageServerWork(null, -1, false);
        if (state == JdtLsService.State.ERROR) {
            setStatusBarText(message);
        }
    }

    private void publishLanguageServerWork(String message, int percent, boolean active) {
        SyncWork sync = syncWork.get();
        if (sync != null) {
            sync.observe(active);
        }
        if (!active) {
            if (languageServerWorkVisible.compareAndSet(true, false)) {
                hideProgress(LSP_WORK_PROGRESS_ID);
            }
            return;
        }
        String label = message == null || message.isBlank()
                ? text("status.lspWorking", "Java: atualizando o projeto...")
                : message;
        if (languageServerWorkVisible.compareAndSet(false, true)) {
            showProgress(LSP_WORK_PROGRESS_ID, label);
        }
        updateProgress(LSP_WORK_PROGRESS_ID, label, percent);
    }

    private void refreshJavaEditorsAfterIndexing() {
        if (javaEditors.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            List<Path> openFiles = List.copyOf(javaEditors.keySet());
            background.submit(() -> {
                openFiles.forEach(path -> {
                    requestRefreshDiagnostics(path);
                    requestRefreshCodeLenses(path);
                    requestRefreshInlayHints(path);
                    requestRefreshSemanticTokens(path);
                });
            });
        });
    }

    private void cancelLanguageServerIndexing() {
        JdtLsService lsp = jdtLs;
        if (lsp == null) {
            return;
        }
        background.submit(() -> {
            lsp.stop();
            hideProgress(LSP_PROGRESS_ID);
            setStatusBarText(text("status.indexingCanceled",
                    "Java: indexacao cancelada - IntelliSense aproximado"));
        });
    }

    private boolean current(long ticket, Path root) {
        return lifecycle.get() == ticket && root != null && root.equals(projectRoot);
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
        configureGhostText(editorContext);
        installCoverageGutter(editorContext);
        installTestGutter(editorContext);
        installCodeActionCommandHandler(editorContext);
        installJavaShortcuts(editorContext);
        if (JavaProjectConventions.isMavenPom(editorContext.filePath())
                && settings().isBuildFileCompletion()) {
            dependencyManagerHost().warmLocalCatalog();
        }
    }

    @Override
    public Set<Character> getCompletionTriggerCharacters() {
        return COMPLETION_TRIGGER_CHARACTERS;
    }

    @Override
    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        if (debugActive.get() || context == null) {
            return false;
        }
        if (BuildFileCompletionProvider.handles(context.filePath())) {
            return settings().isBuildFileCompletion();
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            return settings().isSpringSupport();
        }
        if (!JavaProjectConventions.isJava(context.filePath())) {
            return false;
        }
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return false;
        }
        char previous = line.charAt(col - 1);
        if (previous == '"') {
            return isJpaQueryLiteral(context) || isSpringAnnotationLiteral(line, col);
        }
        return JavaTypingContext.allowsTriggerCharacter(context.text(), context.caretOffset(), previous);
    }

    private boolean isIdleCompletionEligible(Path filePath) {
        return !debugActive.get() && JavaProjectConventions.isJava(filePath);
    }

    private boolean isIdleCompletionReady() {
        return !debugActive.get() && !isAutoCompletePopupVisible();
    }

    private boolean isAutoCompletePopupVisible() {
        IdeEditorContext editor = activeJavaEditor;
        return editor != null && editor.isAutoCompleteVisible();
    }

    private AutoCompleteIdleTrigger.Caret currentIdleCaret() {
        IdeEditorContext editor = activeJavaEditor;
        if (editor == null) {
            return null;
        }
        return new AutoCompleteIdleTrigger.Caret(
                JavaProjectConventions.normalize(editor.filePath()), editor.getCaretOffset());
    }

    private void onLateCompletion(Path filePath, int line, int col) {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = activeJavaEditor;
            if (editor == null || debugActive.get() || !Objects.equals(
                    JavaProjectConventions.normalize(editor.filePath()),
                    JavaProjectConventions.normalize(filePath))) {
                return;
            }
            String text = editor.getText();
            int offset = editor.getCaretOffset();
            if (text == null || offset < 0 || offset > text.length()) {
                return;
            }
            int caretLine = 0;
            int lineStart = 0;
            for (int index = 0; index < offset; index++) {
                if (text.charAt(index) == '\n') {
                    caretLine++;
                    lineStart = index + 1;
                }
            }
            if (caretLine == line && offset - lineStart == col) {
                requestCodeEditorAutocomplete();
            }
        });
    }

    private void fireIdleCompletion() {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = activeJavaEditor;
            if (editor == null || editor.isAutoCompleteVisible()
                    || !JavaTypingContext.allowsIdleCompletion(editor.getText(), editor.getCaretOffset())) {
                return;
            }
            log.debug("Autocomplete: disparo automatico apos {} ms de pausa", AUTO_COMPLETE_IDLE_DELAY_MS);
            requestCodeEditorAutocomplete();
        });
    }

    @Override
    public boolean isAutoCompletionOnTypingEnabled() {
        return !debugActive.get();
    }

    @Override
    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        if (debugActive.get() || context == null) {
            return null;
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            List<AutoCompleteItem> values = SpringConfigSupport.completeValues(springConfigIndex,
                    context.filePath(), context.text(), context.caretLine(), context.caretCol());
            if (!values.isEmpty()) {
                return values;
            }
            return SpringConfigSupport.complete(springMetadata, context.filePath(),
                    context.text(), context.caretLine(), context.caretCol());
        }
        List<AutoCompleteItem> query = jpaQueryCompletion(context);
        if (query != null) {
            return query;
        }
        List<AutoCompleteItem> springAnnotation = springAnnotationCompletion(context);
        if (springAnnotation != null) {
            return springAnnotation;
        }
        if (BuildFileCompletionProvider.handles(context.filePath())) {
            return settings().isBuildFileCompletion()
                    ? buildFileCompletion.suggestions(context)
                    : null;
        }
        if (!JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        boolean memberAccess = JavaFastCompletionProvider.isMemberAccess(context);
        JavaProjectDescriptor current = descriptor;
        List<AutoCompleteItem> snippetsLocal = memberAccess ? List.of() : snippets.suggestions(
                context.prefix(), current != null && current.spring());

        JdtLsService lsp = jdtLs;
        if (lsp != null && lsp.isInteractive() && lsp.isReady()) {
            long started = System.nanoTime();
            List<AutoCompleteItem> semantic = filterCompletionSuggestions(lsp.reusableCompletions(
                    context.filePath(), context.text(), context.caretLine(), context.caretCol()),
                    context.prefix());
            boolean reused = !semantic.isEmpty();
            if (!reused) {
                Character triggerCharacter = completionTriggerCharacter(context.currentLine(),
                        context.caretCol(), getCompletionTriggerCharacters());
                JdtLsService.CompletionTrigger trigger =
                        context.triggerKind() == IdeCompletionTriggerKind.TYPING && triggerCharacter != null
                                ? JdtLsService.CompletionTrigger.TRIGGER_CHARACTER
                                : JdtLsService.CompletionTrigger.INVOKED;
                semantic = lsp.complete(context.filePath(), context.text(),
                        context.caretLine(), context.caretCol(), trigger, triggerCharacter,
                        lsp.documentVersion(context.filePath()), true);
            }
            if (semantic.isEmpty()) {
                semantic = filterCompletionSuggestions(lsp.cachedCompletions(context.filePath(),
                        context.text(), context.caretLine(), context.caretCol()), context.prefix());
            }
            if (semantic.isEmpty()) {
                semantic = fastCompletion.suggestions(context);
            }
            long fetched = System.nanoTime();
            List<AutoCompleteItem> result = javaCompletion(
                    mergeCompletionSuggestions(semantic, snippetsLocal), context);
            log.debug("Autocomplete: {} item(ns) em {} ms (origem {}, pos-processamento {} ms)",
                    result.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    reused ? "cache" : "jdtls",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fetched));
            return result;
        }

        List<AutoCompleteItem> lexical = fastCompletion.suggestions(context);
        List<AutoCompleteItem> local = mergeCompletionSuggestions(lexical, snippetsLocal);
        List<AutoCompleteItem> contextual = List.of();
        if (lsp != null && lsp.isInteractive()) {
            contextual = filterCompletionSuggestions(lsp.cachedCompletions(context.filePath(),
                    context.text(), context.caretLine(), context.caretCol()), context.prefix());
            lsp.warmCompletion(context.filePath(), context.text(),
                    context.caretLine(), context.caretCol());
        }
        return javaCompletion(mergeCompletionSuggestions(contextual, local), context);
    }

    private List<AutoCompleteItem> javaCompletion(List<AutoCompleteItem> items,
                                                  IdeCompletionContext context) {
        List<AutoCompleteItem> ranked = CallParentheses.apply(CompletionRanking.rank(items, context.prefix()),
                context.text(), context.prefixOffset(), context.caretOffset());
        return markUnusedMethods(ranked, names -> lexicalIndex.unusedMethods(names,
                context.filePath(), context.text()));
    }

    static List<AutoCompleteItem> markUnusedMethods(List<AutoCompleteItem> items,
                                                    Function<Set<String>, Set<String>> unusedLookup) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        Set<String> methods = new LinkedHashSet<>();
        for (AutoCompleteItem item : items) {
            if (isMethodCompletion(item)) {
                methods.add(CompletionRanking.name(item));
            }
        }
        if (methods.isEmpty()) {
            return items;
        }
        try {
            Set<String> unused = unusedLookup.apply(methods);
            if (unused == null || unused.isEmpty()) {
                return items;
            }
            List<AutoCompleteItem> marked = new ArrayList<>(items.size());
            for (AutoCompleteItem item : items) {
                marked.add(isMethodCompletion(item) && unused.contains(CompletionRanking.name(item))
                        ? item.withUnused(true)
                        : item);
            }
            return List.copyOf(marked);
        } catch (LinkageError | RuntimeException e) {
            log.debug("Marcacao de metodos sem uso indisponivel: {}", e.toString());
            return items;
        }
    }

    private static boolean isMethodCompletion(AutoCompleteItem item) {
        return item != null && (item.kind() == AutoCompleteItem.Kind.METHOD
                || item.kind() == AutoCompleteItem.Kind.FUNCTION);
    }

    static Character completionTriggerCharacter(String line, int col, Set<Character> triggers) {
        if (line == null || col <= 0 || col > line.length() || triggers == null) {
            return null;
        }
        char previous = line.charAt(col - 1);
        return triggers.contains(previous) ? previous : null;
    }

    static List<AutoCompleteItem> filterCompletionSuggestions(List<AutoCompleteItem> source,
                                                               String prefix) {
        if (source == null || source.isEmpty()) return List.of();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.label() != null
                        && (needle.isEmpty()
                        || item.label().toLowerCase(Locale.ROOT).startsWith(needle)))
                .toList();
    }

    static List<AutoCompleteItem> mergeCompletionSuggestions(List<AutoCompleteItem> contextual,
                                                              List<AutoCompleteItem> snippets) {
        List<AutoCompleteItem> merged = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        Set<String> labels = new HashSet<>();
        appendBySignature(contextual, merged, signatures, labels);
        appendByLabel(snippets, merged, labels);
        return List.copyOf(merged);
    }

    private static void appendBySignature(List<AutoCompleteItem> source,
                                          List<AutoCompleteItem> target,
                                          Set<String> signatures, Set<String> labels) {
        if (source == null) {
            return;
        }
        for (AutoCompleteItem item : source) {
            if (item == null || item.label() == null
                    || !signatures.add(completionSignature(item))) {
                continue;
            }
            labels.add(item.label().toLowerCase(Locale.ROOT));
            target.add(item);
        }
    }

    private static void appendByLabel(List<AutoCompleteItem> source,
                                      List<AutoCompleteItem> target, Set<String> labels) {
        if (source == null) {
            return;
        }
        for (AutoCompleteItem item : source) {
            if (item != null && item.label() != null
                    && labels.add(item.label().toLowerCase(Locale.ROOT))) {
                target.add(item);
            }
        }
    }

    static String completionSignature(AutoCompleteItem item) {
        return item.label().toLowerCase(Locale.ROOT)
                + "|" + (item.insertText() == null ? "" : item.insertText())
                + "|" + (item.detail() == null ? "" : item.detail())
                + "|" + item.kind();
    }

    private static boolean blockedByFollowingText(String line, int caretCol) {
        if (line == null || caretCol < 0 || caretCol >= line.length()) {
            return false;
        }
        char next = line.charAt(caretCol);
        return Character.isJavaIdentifierPart(next)
                || next == '"'
                || next == '\''
                || next == '('
                || next == '.'
                || next == '@';
    }

    @Override
    public String getGhostText(IdeGhostTextContext context) {
        GhostTextSuggestion suggestion = getGhostSuggestion(context);
        return suggestion == null ? null : suggestion.text();
    }

    @Override
    public GhostTextSuggestion getGhostSuggestion(IdeGhostTextContext context) {
        if (debugActive.get() || context == null) {
            return null;
        }
        if (blockedByFollowingText(context.currentLine(), context.caretCol())) {
            return null;
        }
        String prefix = identifierPrefix(context.currentLine(), context.caretCol());
        boolean memberAccess = isMemberAccess(context.currentLine(), context.caretCol(), prefix);
        if (prefix.isEmpty() && !memberAccess) {
            return null;
        }
        JdtLsService lsp = interactiveServerFor(context.filePath());
        List<AutoCompleteItem> contextual = List.of();
        if (lsp != null) {
            contextual = lsp.reusableCompletions(context.filePath(), context.text(),
                    context.caretLine(), context.caretCol());
            if (contextual.isEmpty()) {
                contextual = lsp.complete(context.filePath(), context.text(),
                        context.caretLine(), context.caretCol(),
                        JdtLsService.CompletionTrigger.INVOKED, null,
                        lsp.documentVersion(context.filePath()));
            }
        }
        JavaProjectDescriptor current = descriptor;
        List<AutoCompleteItem> local = memberAccess ? List.of() : snippets.suggestions(prefix,
                current != null && current.spring());
        List<AutoCompleteItem> ghostCandidates = new ArrayList<>(contextual);
        ghostCandidates.addAll(local);
        GhostChoice choice = ghostTextChoice(ghostCandidates, prefix, memberAccess);
        if (choice != null) {
            return new GhostTextSuggestion(indentMultilineGhostText(choice.suffix(), context.currentLine()),
                    choice.item().additionalTextEdits());
        }
        return GhostTextSuggestion.of(lexicalGhostTextSuffix(context.text(), prefix));
    }

    record GhostChoice(String suffix, AutoCompleteItem item) {
    }

    static String ghostTextSuffix(List<AutoCompleteItem> items, String prefix) {
        return ghostTextSuffix(items, prefix, false);
    }

    static String ghostTextSuffix(List<AutoCompleteItem> items, String prefix,
                                  boolean allowEmptyPrefix) {
        GhostChoice choice = ghostTextChoice(items, prefix, allowEmptyPrefix);
        return choice == null ? null : choice.suffix();
    }

    static GhostChoice ghostTextChoice(List<AutoCompleteItem> items, String prefix,
                                       boolean allowEmptyPrefix) {
        if (items == null || prefix == null || (prefix.isEmpty() && !allowEmptyPrefix)) {
            return null;
        }
        GhostChoice insensitive = null;
        for (AutoCompleteItem item : items) {
            if (item == null) {
                continue;
            }
            String insert = sanitizeSnippetForGhostText(item.insertText());
            if (insert == null || insert.isBlank() || insert.length() <= prefix.length()) {
                continue;
            }
            insert = limitGhostText(insert);
            if (insert.startsWith(prefix)) {
                return new GhostChoice(insert.substring(prefix.length()), item);
            }
            if (insensitive == null && insert.regionMatches(true, 0, prefix, 0,
                    prefix.length())) {
                insensitive = new GhostChoice(insert.substring(prefix.length()), item);
            }
        }
        return insensitive;
    }

    private static boolean isMemberAccess(String line, int col, String prefix) {
        if (line == null || col <= 0 || col > line.length() || !prefix.isEmpty()) {
            return false;
        }
        int index = col - 1;
        while (index >= 0 && Character.isWhitespace(line.charAt(index))) {
            index--;
        }
        return index >= 0 && line.charAt(index) == '.';
    }

    static String indentMultilineGhostText(String suffix, String currentLine) {
        if (suffix == null || suffix.indexOf('\n') < 0) {
            return suffix;
        }
        String line = currentLine == null ? "" : currentLine;
        int indentEnd = 0;
        while (indentEnd < line.length()) {
            char value = line.charAt(indentEnd);
            if (value != ' ' && value != '\t') {
                break;
            }
            indentEnd++;
        }
        String indent = line.substring(0, indentEnd);
        return suffix.replace("\r\n", "\n").replace("\n", "\n" + indent);
    }

    private static String limitGhostText(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int lines = 1;
        int end = Math.min(normalized.length(), 2_000);
        for (int index = 0; index < end; index++) {
            if (normalized.charAt(index) == '\n' && ++lines > 24) {
                end = index;
                break;
            }
        }
        return normalized.substring(0, end);
    }

    static String lexicalGhostTextSuffix(String text, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        String exact = shortestIdentifierSuffix(text, prefix, false);
        if (exact != null) {
            return exact;
        }
        for (String keyword : JAVA_GHOST_KEYWORDS) {
            if (keyword.startsWith(prefix) && keyword.length() > prefix.length()) {
                return keyword.substring(prefix.length());
            }
        }
        String insensitive = shortestIdentifierSuffix(text, prefix, true);
        if (insensitive != null) {
            return insensitive;
        }
        for (String keyword : JAVA_GHOST_KEYWORDS) {
            if (keyword.length() > prefix.length()
                    && keyword.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return keyword.substring(prefix.length());
            }
        }
        return null;
    }

    private static String shortestIdentifierSuffix(String text, String prefix,
                                                   boolean ignoreCase) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String best = null;
        int identifiers = 0;
        for (int index = 0; index < text.length() && identifiers < 512;) {
            if (!Character.isJavaIdentifierStart(text.charAt(index))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                end++;
            }
            identifiers++;
            String candidate = text.substring(index, end);
            boolean matches = ignoreCase
                    ? candidate.regionMatches(true, 0, prefix, 0, prefix.length())
                    : candidate.startsWith(prefix);
            if (matches && candidate.length() > prefix.length()) {
                String suffix = candidate.substring(prefix.length());
                if (best == null || suffix.length() < best.length()) {
                    best = suffix;
                }
            }
            index = end;
        }
        return best;
    }

    static String sanitizeSnippetForGhostText(String value) {
        if (value == null) {
            return null;
        }
        String expanded = SNIPPET_DEFAULT.matcher(value).replaceAll("$1");
        return SNIPPET_PLACEHOLDER.matcher(expanded).replaceAll("");
    }

    private static String identifierPrefix(String line, int col) {
        if (line == null || col <= 0 || col > line.length()) {
            return "";
        }
        int start = col;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, col);
    }

    @Override
    public boolean supportsIncrementalDiagnostics() {
        return true;
    }

    @Override
    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context, boolean incremental,
                                                 Collection<Diagnostic> diagnostics) {
        if (context == null) {
            return null;
        }
        Path filePath = context.getFilePath();
        if (SpringConfigSupport.isConfigFile(filePath)) {
            return pluginDiagnostics(filePath, context.getText());
        }
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        if (incremental && context.getPreviousText() != null
                && JavaDiagnosticEdits.sameCode(context.getPreviousText(), context.getText())) {
            return JavaDiagnosticEdits.move(context.getPreviousDiagnostics(),
                    context.getPreviousText(), context.getText());
        }
        List<Diagnostic> merged = new ArrayList<>();

        JdtLsService lsp = jdtLs;
        if (lsp != null && lsp.isInteractive()) {
            merged.addAll(lsp.diagnostics(filePath));
        }
        merged.addAll(problems.diagnostics(filePath));

        merged.addAll(pluginDiagnostics(filePath, context.getText()));
        merged.addAll(unusedMethodDiagnostics(context.getText(), merged,
                names -> lexicalIndex.unusedMethods(names, filePath, context.getText())));
        merged.addAll(unusedFieldDiagnostics(context.getText(), merged,
                names -> lexicalIndex.unusedFields(names, filePath, context.getText())));
        List<Diagnostic> visible = InspectionSuppressions.filter(merged, context.getText(),
                settings().getDisabledInspections(), occurrenceFilterFor(filePath));
        return visible.isEmpty() && (lsp == null || !lsp.isInteractive()) ? null : visible;
    }

    static List<Diagnostic> unusedMethodDiagnostics(String text, Collection<Diagnostic> existing,
                                                    Function<Set<String>, Set<String>> unusedLookup) {
        return unusedDeclarationDiagnostics(text, existing, unusedLookup, SymbolKind.METHOD,
                "diagnostic.unusedMethod", "Metodo sem uso no projeto", JavaInspection.UNUSED_METHOD.id());
    }

    static List<Diagnostic> unusedFieldDiagnostics(String text, Collection<Diagnostic> existing,
                                                   Function<Set<String>, Set<String>> unusedLookup) {
        return unusedDeclarationDiagnostics(text, existing, unusedLookup, SymbolKind.FIELD,
                "diagnostic.unusedField", "Campo sem uso no projeto", JavaInspection.UNUSED_FIELD.id());
    }

    private static List<Diagnostic> unusedDeclarationDiagnostics(String text, Collection<Diagnostic> existing,
            Function<Set<String>, Set<String>> unusedLookup, SymbolKind kind,
            String labelKey, String fallback, String inspectionId) {
        if (text == null || text.isBlank() || !DiagnosticTags.isSupported()) {
            return List.of();
        }
        List<JavaLexicalSource.Declared> methods = JavaLexicalSource.declarations(text).stream()
                .filter(declared -> declared.kind() == kind)
                .toList();
        if (methods.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        methods.forEach(method -> names.add(method.name()));
        Set<String> unused = unusedLookup.apply(names);
        if (unused == null || unused.isEmpty()) {
            return List.of();
        }
        String label = text(labelKey, fallback);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JavaLexicalSource.Declared method : methods) {
            Range range = method.range();
            if (!unused.contains(method.name()) || alreadyMarkedUnnecessary(existing, range)) {
                continue;
            }
            Diagnostic hint = DiagnosticTags.unnecessary(new Diagnostic(range.start().line(),
                    range.start().col(), range.end().line(), range.end().col(), DiagnosticSeverity.HINT,
                    label + ": " + method.name(), inspectionId, null), true);
            if (!DiagnosticTags.isUnnecessary(hint)) {
                return List.of();
            }
            diagnostics.add(hint);
        }
        return List.copyOf(diagnostics);
    }

    private static boolean alreadyMarkedUnnecessary(Collection<Diagnostic> existing, Range range) {
        if (existing == null) {
            return false;
        }
        int line = range.start().line();
        for (Diagnostic diagnostic : existing) {
            if (DiagnosticTags.isUnnecessary(diagnostic) && diagnostic.startLine() <= line && diagnostic.endLine() >= line
                    && diagnostic.startCol() <= range.end().col()
                    && (diagnostic.endLine() > line || diagnostic.endCol() >= range.start().col())) {
                return true;
            }
        }
        return false;
    }

    private List<Diagnostic> pluginDiagnostics(Path filePath, String text) {
        if (SpringConfigSupport.isConfigFile(filePath)) {
            return DiagnosticRanges.clamp(InspectionSuppressions.filter(
                    SpringConfigSupport.validate(springMetadata, filePath, text),
                    text, settings().getDisabledInspections(), occurrenceFilterFor(filePath)), text);
        }
        JavaProjectDescriptor current = descriptor;
        if (!JavaProjectConventions.isJava(filePath) || current == null || !current.spring()
                || !settings().isSpringSupport()) {
            return List.of();
        }
        SpringIndexSnapshot snapshot = springIndex.snapshot();
        List<Diagnostic> plugin = new ArrayList<>(
                SpringDiagnostics.analyze(snapshot, filePath, text));
        if (settings().isSpringJpa()) {
            plugin.addAll(JpaDiagnostics.analyze(snapshot, filePath));
            plugin.addAll(JpqlDiagnostics.analyze(snapshot, filePath));
        }
        if (settings().isSpringConfigNavigation()) {
            plugin.addAll(SpringValueDiagnostics.analyze(snapshot, springConfigIndex,
                    springMetadata, filePath));
        }
        if (settings().isSpringInfra()) {
            plugin.addAll(SpringInfraDiagnostics.analyze(snapshot.infra(), filePath));
        }
        return DiagnosticRanges.clamp(InspectionSuppressions.filter(plugin, text,
                settings().getDisabledInspections(), occurrenceFilterFor(filePath)), text);
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        if (context == null) {
            return null;
        }
        if (isDebugPaused()) {
            return null;
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            return SpringConfigSupport.hover(springMetadata, context.filePath(),
                    context.text(), context.line());
        }
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) {
            return null;
        }
        HoverInfo diagnostic = lsp.diagnosticHover(
                context.filePath(), context.line(), context.col());
        return diagnostic != null ? diagnostic
                : lsp.hover(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public boolean canDeletePaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return true;
        }

        List<Path> targets = paths.stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();

        boolean safeDeleteAvailable = targets.stream().anyMatch(JavaIdeAdapter::containsJavaSource);
        JavaDeleteDialogPanel panel = onUi(() -> new JavaDeleteDialogPanel(
                targets, projectRoot, settings().isSafeDelete(), safeDeleteAvailable));

        if (panel == null) {
            return false;
        }

        Boolean confirmed = onUi(() -> this.<Boolean>createModernComponentDialogBuilder()
                .title(text("delete.title", "Delete"))
                .type(ModernDialog.Type.QUESTION)
                .accentColor(DELETE_ACCENT)
                .showTypeLabel(false)
                .component(panel)
                .option(text("delete.confirm", "Delete"), Boolean.TRUE, DELETE_ACCENT, Color.WHITE)
                .cancelOption(text("delete.cancel", "Cancel"))
                .show());

        if (!Boolean.TRUE.equals(confirmed)) {
            return false;
        }

        boolean safeDelete = safeDeleteAvailable && Boolean.TRUE.equals(onUi(panel::isSafeDeleteSelected));
        if (safeDeleteAvailable) {
            settings().setSafeDelete(safeDelete);
            settings().save();
        }

        return !safeDelete || confirmUsages(targets);
    }

    private boolean confirmUsages(List<Path> targets) {
        JavaSafeDeleteScanner.ScanResult result = findExternalUsages(targets);
        List<Location> usages = result.locations();
        if (usages.isEmpty() && result.complete()) {
            return true;
        }

        String message = !result.complete()
                ? text("delete.incomplete", "A busca de usos ficou incompleta; nao foi possivel verificar todos os arquivos.")
                : usages.size() == 1
                ? text("delete.usagesOne", "1 usage was found outside the selection.")
                : usages.size() + text("delete.usagesMany", " usages were found outside the selection.");

        Integer choice = onUi(() -> createModernDialogBuilder()
                .type(ModernDialog.Type.QUESTION)
                .accentColor(DELETE_ACCENT)
                .title(text("delete.usagesTitle", "Usages detected"))
                .message(message + " " + text("delete.usagesQuestion", "Delete anyway?"))
                .option(text("delete.viewUsages", "View usages"), 2)
                .option(text("delete.deleteAnyway", "Delete anyway"), 0, DELETE_ACCENT, Color.WHITE)
                .option(text("delete.cancel", "Cancel"), 1, new Color(90, 90, 90), Color.WHITE)
                .show());

        if (choice != null && choice == 2) {
            showUsagesPopup(usages, null, null, null, null, Kind.REFERENCES);
            return false;
        }

        return choice != null && choice == 0;
    }

    private JavaSafeDeleteScanner.ScanResult findExternalUsages(List<Path> targets) {
        JdtLsService lsp = jdtLs;
        Set<Path> deleted = new LinkedHashSet<>(targets);
        Map<String, Location> unique = new LinkedHashMap<>();
        boolean semanticComplete = lsp != null && lsp.isReady() && !lsp.isWarmingUp()
                && semanticUsages(lsp, targets, deleted, unique);
        if (semanticComplete) {
            return new JavaSafeDeleteScanner.ScanResult(List.copyOf(unique.values()), true);
        }

        Map<Path, String> buffers = onUi(() -> {
            Map<Path, String> snapshots = new HashMap<>();
            javaEditors.forEach((file, editor) -> snapshots.put(file, editor.getText()));
            return snapshots;
        });
        JavaSafeDeleteScanner.ScanResult scan = JavaSafeDeleteScanner.scan(projectRoot, targets,
                buffers == null ? Map.of() : buffers);
        for (Location location : scan.locations()) {
            Path referenced = JavaNavigation.path(location);
            if (referenced != null && !isInside(referenced, deleted)) {
                unique.putIfAbsent(locationKey(location), location);
            }
        }
        return new JavaSafeDeleteScanner.ScanResult(List.copyOf(unique.values()), false);
    }

    private boolean semanticUsages(JdtLsService lsp, List<Path> targets, Set<Path> deleted,
                                   Map<String, Location> unique) {
        record Search(Path source, String content, Range declaration) {
        }
        boolean complete = true;
        List<Path> temporarilyOpened = new ArrayList<>();
        List<Search> searches = new ArrayList<>();
        try {
            for (Path source : collectJavaSources(targets)) {
                IdeEditorContext openEditor = editorContextFor(source);
                String content = openEditor == null ? readSource(source) : onUi(openEditor::getText);
                if (content == null) {
                    complete = false;
                    continue;
                }
                if (getEditor(source) == null) {
                    temporarilyOpened.add(source);
                }
                lsp.openDocument(source, content);
                List<Range> declarations = typeDeclarationRanges(lsp.documentSymbols(source, content));
                if (declarations.isEmpty()) {
                    complete = false;
                }
                declarations.forEach(range -> searches.add(new Search(source, content, range)));
            }
            if (searches.isEmpty()) {
                return false;
            }
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(DELETE_SEARCH_PARALLELISM, searches.size()));
            try {
                List<Future<Result>> results = new ArrayList<>();
                for (Search search : searches) {
                    results.add(pool.submit(() -> lsp.navigation(Kind.REFERENCES, search.source(),
                            search.content(), search.declaration().start().line(),
                            search.declaration().start().col(), DELETE_REFERENCES_TIMEOUT_MS)));
                }
                for (Future<Result> pending : results) {
                    Result references = pending.get();
                    complete &= references.status() == Status.COMPLETE;
                    for (Location location : references.locations()) {
                        Path referenced = JavaNavigation.path(location);
                        if (referenced != null && !isInside(referenced, deleted)) {
                            unique.putIfAbsent(locationKey(location), location);
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException failure) {
                log.debug("Busca de usos para a exclusao falhou: {}", rootMessage(failure));
                return false;
            } finally {
                pool.shutdownNow();
            }
        } finally {
            temporarilyOpened.forEach(lsp::closeDocument);
        }
        return complete;
    }

    private void registerFileWatcher() {
        if (fileWatcherListenerId != null) {
            return;
        }
        IdeProjectFileWatcher watcher;
        try {
            watcher = getProjectFileWatcher();
        } catch (Exception e) {
            log.debug("Observador de arquivos indisponivel: {}", e.getMessage());
            return;
        }
        if (watcher == null) {
            log.debug("Observador de arquivos indisponivel para este projeto");
            return;
        }
        JavaFileChangeRouter router = new JavaFileChangeRouter(this::onWatchedFileChanged,
                path -> javaEditors.containsKey(JavaProjectConventions.normalize(path)), projectRoot);
        projectFileWatcher = watcher;
        fileChangeRouter = router;
        fileWatcherListenerId = watcher.addFileWatcherListener(router::accept);
        log.info("Observador de arquivos do Java registrado: {}", fileWatcherListenerId);
    }

    private void unregisterFileWatcher() {
        String listenerId = fileWatcherListenerId;
        IdeProjectFileWatcher watcher = projectFileWatcher;
        fileWatcherListenerId = null;
        projectFileWatcher = null;
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
        requestJavaTreeIconRefresh(file);
        if (change == JavaFileChangeRouter.Change.DELETED) {
            forgetJavaFile(file);
            JdtLsService lsp = jdtLs;
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
        lexicalIndex.refreshFile(file, content);
        refreshSpringIndexFor(file, content);

        JdtLsService lsp = jdtLs;
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
        requestRefreshCodeLenses(file);
    }

    private void onExternalChangeToOpenFile(Path file, String rawDiskContent) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || rawDiskContent == null) {
            return;
        }
        String diskContent = normalizeDiskText(rawDiskContent);
        String mirrored = lsp.documentContent(file);
        if (mirrored == null) {
            lexicalIndex.refreshFile(file, diskContent);
            lsp.pathChanged(file);
            return;
        }
        if (diskContent.equals(mirrored)) {
            return;
        }
        lexicalIndex.refreshFile(file, diskContent);
        Path normalized = JavaProjectConventions.normalize(file);
        IdeEditorContext editor = javaEditors.get(normalized);
        String baseline = diskBaseline.get(normalized);
        if (editor == null || baseline == null || !baseline.equals(mirrored)) {
            lsp.requestExternalResync();
            return;
        }
        diskBaseline.put(normalized, diskContent);
        SwingUtilities.invokeLater(() -> editor.setText(diskContent));
    }

    static String normalizeDiskText(String raw) {
        return raw == null ? null : raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String diskBaselineFor(Path file, String editorText) {
        if (file == null || !Files.isRegularFile(file)) {
            return editorText;
        }
        return normalizeDiskText(JavaProjectConventions.readOrEmpty(file));
    }

    private boolean insideKnownSourceRoot(Path file) {
        JavaProjectDescriptor current = descriptor;
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
        background.schedule(() -> {
            JdtLsService lsp = jdtLs;
            if (lsp != null) {
                lsp.projectConfigurationUpdate();
            }
        }, PROJECT_CONFIGURATION_REQUEST_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void onWatchedSpringConfigFile(Path file, JavaFileChangeRouter.Change change) {
        JavaProjectDescriptor current = descriptor;
        Path root = projectRoot;
        if (current == null || root == null || !current.spring()) {
            return;
        }
        loadSpringConfigIndex(lifecycle.get(), root);
    }

    private void onWatchedBuildFile(Path file, JavaFileChangeRouter.Change change) {
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            if (change == JavaFileChangeRouter.Change.DELETED) {
                lsp.pathDeleted(file);
            } else {
                lsp.pathChanged(file);
            }
            lsp.projectConfigurationUpdate();
        }
        onBuildFileChanged(file);
    }

    private void refreshSpringIndexFor(Path file, String content) {
        JavaProjectDescriptor current = descriptor;
        Path root = projectRoot;
        long ticket = lifecycle.get();
        if (current == null || root == null || !current.spring()
                || !settings().isSpringSupport()) {
            return;
        }
        springIndex.refreshFile(file, content).thenAccept(snapshot -> {
            if (!current(ticket, root)) {
                return;
            }
            requestRefreshCodeLenses(file);
            SpringExplorerPanel panel = springPanel;
            if (panel != null) {
                panel.reload();
            }
        });
    }

    private void forgetJavaFile(Path file) {
        lexicalIndex.refreshFile(file, "");
        if (todoScanner.forget(file) && todoPanel != null) {
            todoPanel.setItems(todoScanner.items(), projectRoot);
        }
        JavaProjectDescriptor current = descriptor;
        if (current != null && current.spring() && settings().isSpringSupport()) {
            refreshSpringIndexFor(file, "");
        }
    }

    @Override
    public PathRenameDecision beforePathRename(Path path) {
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return PathRenameDecision.useDefault();
        }
        Optional<MavenModuleRename.Target> target = MavenModuleRename.of(current, path, this::readCurrentText);
        if (target.isEmpty()) {
            return PathRenameDecision.useDefault();
        }
        JavaModuleRenameDialogPanel.Result result = askModuleRename(target.get());
        if (result == null) {
            return PathRenameDecision.cancel();
        }
        IdeWorkspaceEdit edit = MavenModuleRename.plan(target.get(), result.scope(), result.name(),
                this::readCurrentText);
        if (edit.isEmpty()) {
            return PathRenameDecision.cancel();
        }
        boolean movesDirectory = edit.operations().stream()
                .anyMatch(operation -> operation instanceof IdeWorkspaceEdit.RenameFile);
        if (movesDirectory) {
            pendingModuleDirectoryRenames.add(target.get().directory());
        }
        SwingUtilities.invokeLater(this::syncProject);
        String label = text("moduleRename.label", "Rename module \"{module}\" to \"{name}\"")
                .replace("{module}", target.get().artifactId())
                .replace("{name}", result.name());
        return PathRenameDecision.apply(label, edit);
    }

    private JavaModuleRenameDialogPanel.Result askModuleRename(MavenModuleRename.Target target) {
        CompletableFuture<JavaModuleRenameDialogPanel.Result> answer = new CompletableFuture<>();
        Runnable open = () -> {
            JavaModuleRenameDialogPanel panel = new JavaModuleRenameDialogPanel(target, answer::complete);
            showPopup(PlatformPopupBuilder.builder()
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

    @Override
    public void onPathRenamed(Path oldPath, Path newPath) {
        Path movedFrom = JavaProjectConventions.normalize(oldPath);
        Path movedTo = JavaProjectConventions.normalize(newPath);
        if (movedFrom != null && movedTo != null && !pendingModuleDirectoryRenames.remove(movedFrom)
                && isModuleRoot(movedFrom)) {
            onBuildFileChanged(movedTo.resolve(JavaProjectConventions.POM_FILE));
        }
        Set<Path> editorsBeforeMove = movedFrom == null ? Set.of() : javaEditors.keySet().stream()
                .filter(path -> path.startsWith(movedFrom))
                .map(path -> movedTo == null ? path : movedTo.resolve(movedFrom.relativize(path)))
                .collect(Collectors.toUnmodifiableSet());
        if (oldPath != null) {
            onPathDeleted(oldPath);
        }
        if (newPath == null) {
            return;
        }
        JavaFileChangeRouter router = fileChangeRouter;
        if (router == null) {
            return;
        }
        if (!Files.isDirectory(newPath)) {
            announceMovedFile(router, newPath, editorsBeforeMove);
            return;
        }
        background.submit(() -> {
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
        if (!editorManagedTargets.contains(normalized) && !javaEditors.containsKey(normalized)) {
            router.acceptCreated(file);
            return;
        }
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            lsp.pathCreated(normalized);
        }
    }

    @Override
    public void onPathDeleted(Path path) {
        if (path == null) {
            return;
        }
        Path deleted = path.toAbsolutePath().normalize();
        if (JavaProjectConventions.isJava(deleted)) {
            forgetJavaFile(deleted);
        }
        if (JavaProjectConventions.isMavenPom(deleted)
                || JavaProjectConventions.isGradleBuildFile(deleted)) {
            onBuildFileChanged(deleted);
        }
        problems.removeBelow(deleted);
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            lsp.pathDeleted(deleted);
        }
        refreshProblemsPanel();
    }

    private boolean isModuleRoot(Path directory) {
        JavaProjectDescriptor current = descriptor;
        return current != null && current.modules().stream()
                .anyMatch(module -> module.root().equals(directory));
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

    private final class PathTransferHost implements JavaPathTransferRefactoring.Host {

        private static final long DIALOG_TIMEOUT_MINUTES = 30;

        @Override
        public JavaProjectDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public JdtLsService readyServer() {
            JdtLsService lsp = jdtLs;
            return lsp != null && lsp.isReady() ? lsp : null;
        }

        @Override
        public JavaMoveDialogPanel.Choice askMove(JavaPathTransferPlan plan) {
            if (SwingUtilities.isEventDispatchThread()) {
                return JavaMoveDialogPanel.Choice.MOVE_ONLY;
            }
            CompletableFuture<JavaMoveDialogPanel.Choice> answer = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                JavaMoveDialogPanel panel = new JavaMoveDialogPanel(plan, answer::complete);
                showPopup(PlatformPopupBuilder.builder()
                        .component(panel)
                        .title(text("move.title", "Move"))
                        .size(520, 240 + Math.min(6, plan.files().size() + plan.folders().size()) * 26)
                        .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                        .onLoad(component -> panel.focusConfirm())
                        .onClose(component -> panel.closed())
                        .build());
            });
            return await(answer, JavaMoveDialogPanel.Choice.CANCEL);
        }

        @Override
        public JavaCopyDialogPanel.Result askCopy(JavaPathTransferPlan plan, Map<Path, String> defaultNames) {
            if (SwingUtilities.isEventDispatchThread()) {
                return new JavaCopyDialogPanel.Result(false, Map.of());
            }
            CompletableFuture<JavaCopyDialogPanel.Result> answer = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                JavaCopyDialogPanel panel = new JavaCopyDialogPanel(plan, defaultNames, answer::complete);
                showPopup(PlatformPopupBuilder.builder()
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
            return readCurrentText(file);
        }

        @Override
        public void warn(String message) {
            if (message == null || message.isBlank()) {
                return;
            }
            createNotification(NotificationContext.builder()
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

    static List<Range> typeDeclarationRanges(List<DocumentSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<Range> ranges = new ArrayList<>();
        List<DocumentSymbol> pendingSymbols = new ArrayList<>(symbols);
        for (int index = 0; index < pendingSymbols.size(); index++) {
            DocumentSymbol symbol = pendingSymbols.get(index);
            if (!isTypeSymbol(symbol.kind())) {
                continue;
            }
            pendingSymbols.addAll(symbol.children());
            Range range = symbol.selectionRange() == null ? symbol.range() : symbol.selectionRange();
            if (range != null && range.start() != null) {
                ranges.add(range);
            }
        }
        return ranges;
    }

    private static boolean isTypeSymbol(SymbolKind kind) {
        return kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.ENUM || kind == SymbolKind.STRUCT;
    }

    private static List<Path> collectJavaSources(List<Path> targets) {
        List<Path> sources = new ArrayList<>();

        for (Path target : targets) {
            if (Files.isRegularFile(target)) {
                if (JavaProjectConventions.isJava(target)) {
                    sources.add(target);
                }
                continue;
            }
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(target)) {
                walk.filter(Files::isRegularFile)
                        .filter(JavaProjectConventions::isJava)
                        .forEach(sources::add);
            } catch (Exception e) {
                log.debug("Falha ao percorrer {} para a exclusao segura: {}", target, e.getMessage());
            }
        }
        return sources;
    }

    private static boolean containsJavaSource(Path target) {
        return !collectJavaSources(List.of(target)).isEmpty();
    }

    private static boolean isInside(Path path, Set<Path> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String readSource(Path source) {
        try {
            return Files.readString(source);
        } catch (Exception e) {
            log.debug("Falha ao ler {} para a exclusao segura: {}", source, e.getMessage());
            return null;
        }
    }


    private static <T> T onUi(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Falha ao executar acao na interface.", e);
            return null;
        }
        return result.get();
    }

    @Override
    public IdeDiagnosticHoverPolicy getDiagnosticHoverPolicy(Path filePath) {
        if (SpringConfigSupport.isConfigFile(filePath) || JavaProjectConventions.isJava(filePath)) {
            return IdeDiagnosticHoverPolicy.diagnosticFirst();
        }
        return IdeDiagnosticHoverPolicy.disabled();
    }

    @Override
    public void onHover(IdeHoverContext context) {
        if (!isDebugPaused()) {
            debugHoverTicket.incrementAndGet();
            hideDebugValuePopup();
            return;
        }
        String expression = context == null || context.text() == null
                || !JavaProjectConventions.isJava(context.filePath())
                ? null : safeDebugExpression(context.text(), context.offset());
        JavaDebugValuePopup popup = debugValuePopup();
        if (expression != null && popup.isShowing(expression)) {
            return;
        }
        long ticket = debugHoverTicket.incrementAndGet();
        if (expression == null) {
            popup.requestHide();
            return;
        }
        Point location = pointerLocation();
        background.submit(() -> {
            try {
                JavaDebugSession session = debugSession;
                JavaDebugSnapshot.Variable value = session == null ? null
                        : session.evaluate(expression, 0);
                if (ticket == debugHoverTicket.get() && isDebugPaused() && value != null) {
                    popup.show(value, location, expression);
                }
            } catch (Exception error) {
                log.debug("Valor sob o cursor indisponivel para '{}': {}", expression,
                        rootMessage(error));
                if (ticket == debugHoverTicket.get()) {
                    popup.requestHide();
                }
            }
        });
    }

    private synchronized JavaDebugValuePopup debugValuePopup() {
        if (debugValuePopup == null) {
            debugValuePopup = new JavaDebugValuePopup(background);
        }
        return debugValuePopup;
    }

    private void hideDebugValuePopup() {
        JavaDebugValuePopup popup = debugValuePopup;
        if (popup != null) popup.hide();
    }

    @Override
    public List<CodeLens> getCodeLenses(IdeCodeLensContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        List<CodeLens> lenses = new ArrayList<>();
        JdtLsService lsp = runningServerFor(context.filePath());
        if (lsp != null) {
            for (JdtLsService.JavaCodeLens lens : lsp.codeLenses(
                    context.filePath(), context.text())) {
                List<Location> targets = uniqueLocations(lens.locations());
                Kind lensKind = Kind.forLens(lens.command());
                if (lensKind == null) continue;
                CodeLensItem item = lensItem(lensKind, lens.status(), targets, context);
                if (item == null) continue;
                lenses.add(CodeLens.inline(Math.max(0, lens.range().start().line()), item));
            }
        }

        addRunLens(lenses, context);
        addCoverageLens(lenses, context);

        List<JavaTest> fileTests = JUnitTestDiscovery.discoverInSource(
                context.filePath(), context.text());
        for (JavaTest test : fileTests) {
            int line = Math.max(0, test.line() - 1);
            lenses.add(CodeLens.inline(line, CodeLensItem.builder()
                    .text(text("lens.runTest", "Run test"))
                    .tooltip(test.selector())
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> runTestFromLens(test, false))
                    .build()));
            lenses.add(CodeLens.inline(line, CodeLensItem.builder()
                    .text(text("lens.debugTest", "Debug test"))
                    .tooltip(test.selector())
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> runTestFromLens(test, true))
                    .build()));
        }

        JavaProjectDescriptor current = descriptor;
        if (current == null || !current.spring() || !settings().isSpringCodeLens()) {
            return lenses;
        }
        SpringIndexSnapshot snapshot = springIndex.snapshot();

        for (SpringBean bean : snapshot.beansIn(context.filePath())) {
            List<SpringInjection> usages = snapshot.injectionsOf(bean);
            String label = usages.size() == 1
                    ? "1 " + text("lens.injection", "injecao")
                    : usages.size() + " " + text("lens.injections", "injecoes");
            List<Location> targets = springLocations(new SpringNavigation.Target(
                    SpringNavigation.Kind.BEAN, bean.simpleName(), usages.stream()
                    .map(injection -> new SpringNavigation.Anchor(injection.file(),
                            injection.line(), injection.memberName()))
                    .<SpringNavigation.Anchor>toList()));
            int beanLine = Math.max(0, bean.line() - 1);

            lenses.add(CodeLens.inline(beanLine, CodeLensItem.builder()
                    .text(label)
                    .tooltip(text("lens.tooltip", "Ver quem injeta") + " " + bean.simpleName())
                    .cursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    .onClick(event -> openSpringTargets(targets, context, event))
                    .build()));
        }

        addInjectionLenses(lenses, snapshot, context);
        addJpaLenses(lenses, snapshot, context);
        addEndpointLenses(lenses, snapshot, context);
        return lenses;
    }

    private void addInjectionLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                                    IdeCodeLensContext context) {
        if (!settings().isSpringNavigation()) {
            return;
        }
        for (SpringInjection injection : snapshot.injectionsIn(context.filePath())) {
            List<SpringBean> candidates = snapshot.candidatesFor(injection);
            if (candidates.isEmpty()) {
                continue;
            }
            String label = candidates.size() == 1
                    ? "-> " + candidates.getFirst().simpleName()
                    : candidates.size() + " " + text("lens.candidates", "candidatos");
            List<Location> targets = springLocations(new SpringNavigation.Target(
                    SpringNavigation.Kind.INJECTION, injection.targetSimpleName(),
                    candidates.stream()
                            .map(bean -> new SpringNavigation.Anchor(bean.file(), bean.line(),
                                    bean.simpleName()))
                            .toList()));
            lenses.add(CodeLens.inline(Math.max(0, injection.line() - 1), CodeLensItem.builder()
                    .text(label)
                    .tooltip(text("lens.beanTarget", "Ir para o bean injetado"))
                    .cursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    .onClick(event -> openSpringTargets(targets, context, event))
                    .build()));
        }
    }

    private void addEndpointLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                                   IdeCodeLensContext context) {
        String baseUrl = springBaseUrl;
        for (SpringEndpoint endpoint : snapshot.endpoints()) {
            if (!context.filePath().equals(endpoint.file())) {
                continue;
            }
            String url = endpoint.urlOn(baseUrl);
            int endpointLine = Math.max(0, endpoint.line() - 1);
            lenses.add(CodeLens.inline(endpointLine, CodeLensItem.builder()
                    .text(text("lens.openInBrowser", "abrir"))
                    .tooltip(url)
                    .cursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    .onClick(event -> openWebBrowser(url))
                    .build()));
            lenses.add(CodeLens.inline(endpointLine, CodeLensItem.builder()
                    .text(text("lens.copyUrl", "copiar URL"))
                    .tooltip(url)
                    .cursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    .onClick(event -> copyToClipboard(url,
                            text("status.urlCopied", "Java: URL copiada")))
                    .build()));
            lenses.add(CodeLens.inline(endpointLine, CodeLensItem.builder()
                    .text(text("lens.copyCurl", "copiar cURL"))
                    .tooltip(url)
                    .cursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    .onClick(event -> copyToClipboard(curlOf(endpoint, url),
                            text("status.curlCopied", "Java: comando cURL copiado")))
                    .build()));
        }
    }

    private static String curlOf(SpringEndpoint endpoint, String url) {
        StringBuilder command = new StringBuilder("curl -X ")
                .append(SpringEndpoint.ANY_METHOD.equals(endpoint.method())
                        ? "GET" : endpoint.method())
                .append(" \"").append(url).append('"');
        if (!endpoint.produces().isEmpty()) {
            command.append(" -H \"Accept: ").append(endpoint.produces().getFirst()).append('"');
        }
        return command.toString();
    }

    private void copyToClipboard(String value, String status) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(value), null);
            setStatusBarText(status);
        } catch (Exception e) {
            log.debug("Falha ao copiar para a area de transferencia: {}", e.getMessage());
        }
    }

    private void addCoverageLens(List<CodeLens> lenses, IdeCodeLensContext context) {
        FileCoverage coverage = coverageStore.forFile(context.filePath()).orElse(null);
        if (coverage == null || coverage.isEmpty()) {
            return;
        }
        String branches = CoverageDisplay.branchSummary(coverage);
        String tooltip = text("lens.coverageTooltip", "Cobertura da ultima execucao de testes");
        if (!branches.isBlank()) {
            tooltip = tooltip + " - " + text("lens.coverageBranches", "branches") + ": " + branches;
        }
        int line = CoverageDisplay.lensLineOf(lexicalIndex.outline(context.text()));
        lenses.add(CodeLens.inline(line, CodeLensItem.builder()
                .text(text("lens.coverage", "Cobertura") + ": " + CoverageDisplay.summary(coverage))
                .tooltip(tooltip)
                .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                .onClick(event -> requestOpenToolPanel(testPanelId))
                .build()));
    }

    private void addJpaLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                              IdeCodeLensContext context) {
        if (!settings().isSpringJpa()) {
            return;
        }
        for (JpaRepositoryInfo repository : snapshot.repositoriesIn(context.filePath())) {
            snapshot.entityNamed(repository.entityType()).ifPresent(entity -> {
                List<Location> targets = springLocations(new SpringNavigation.Target(
                        SpringNavigation.Kind.ENTITY, entity.simpleName(),
                        List.of(new SpringNavigation.Anchor(entity.file(), entity.line(),
                                entity.simpleName()))));
                lenses.add(CodeLens.inline(Math.max(0, repository.line() - 1),
                        CodeLensItem.builder()
                                .text(entity.simpleName() + " (" + entity.effectiveTable() + ")")
                                .tooltip(text("lens.entityTarget", "Ir para a entidade"))
                                .cursor(java.awt.Cursor.getPredefinedCursor(
                                        java.awt.Cursor.HAND_CURSOR))
                                .onClick(event -> openSpringTargets(targets, context, event))
                                .build()));
            });
        }
    }

    private CodeLensItem lensItem(Kind kind, Status status, List<Location> targets,
                                  IdeCodeLensContext context) {
        if (status == Status.COMPLETE) {
            if (targets.isEmpty()) return null;
            return CodeLensItem.builder()
                    .text(countLabel(kind, targets.size()))
                    .tooltip(codeLensTooltip(targets))
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> openLensTargets(kind, context, targets, event))
                    .build();
        }
        if (status == Status.FAILED) {
            return CodeLensItem.builder()
                    .text(text("lens.failed", "Tentar novamente"))
                    .tooltip(text("status.navigation.failed", "Java: a busca falhou; tente novamente"))
                    .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    .onClick(event -> requestRefreshCodeLenses(context.filePath()))
                    .build();
        }
        return null;
    }

    private String countLabel(Kind kind, int count) {
        boolean one = count == 1;
        String key = switch (kind) {
            case IMPLEMENTATION -> one ? "navigation.implementation" : "navigation.implementations";
            case DEFINITION -> one ? "navigation.definition" : "navigation.definitions";
            case REFERENCES -> one ? "navigation.usage" : "navigation.usages";
        };
        String fallback = switch (kind) {
            case IMPLEMENTATION -> one ? "implementacao" : "implementacoes";
            case DEFINITION -> one ? "definicao" : "definicoes";
            case REFERENCES -> one ? "uso" : "usos";
        };
        return count + " " + text(key, fallback);
    }

    private void openLensTargets(Kind kind, IdeCodeLensContext context, List<Location> targets,
                                 CodeLensClickEvent event) {
        if (targets.isEmpty()) return;
        if (targets.size() == 1 && kind != Kind.REFERENCES) {
            Location target = targets.getFirst();
            navigateToLocation(target, JavaNavigation.path(target));
            return;
        }
        IdeEditorContext editor = editorContextFor(context.filePath());
        MouseEvent mouse = event == null ? null : event.mouseEvent();
        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
        showUsagesPopup(targets, context.filePath(),
                editor == null ? context.text() : editor.getText(), editor, screen, kind);
    }

    private void openSpringTargets(List<Location> targets, IdeCodeLensContext context,
                                   CodeLensClickEvent event) {
        if (targets.isEmpty()) {
            openSpringExplorer();
            return;
        }
        MouseEvent mouse = event == null ? null : event.mouseEvent();
        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
        showUsagesPopup(targets, context.filePath(), context.text(),
                editorContextFor(context.filePath()), screen, Kind.REFERENCES);
    }

    private void addRunLens(List<CodeLens> lenses, IdeCodeLensContext context) {
        MainClassScanner.MainLensAnchor anchor = MainClassScanner.mainLensAnchor(context.text());
        if (anchor == null) {
            return;
        }
        if (mainClassAt(context.filePath(), context.text()).isEmpty()) {
            return;
        }
        CodeLensItem item = CodeLensItem.builder()
                .text(text("lens.run", "Run | Debug"))
                .tooltip(text("lens.run.tooltip", "Executar ou depurar este main"))
                .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                .onClick(this::showRunLensMenu)
                .build();
        lenses.add(anchor.inline()
                ? CodeLens.inline(anchor.line(), item)
                : CodeLens.above(anchor.line(), anchor.col(), item));
    }

    private void showRunLensMenu(CodeLensClickEvent event) {
        MouseEvent mouse = event == null ? null : event.mouseEvent();
        if (mouse == null) {
            launchCurrentFile(false);
            return;
        }
        ActionMenu menu = ActionMenu.of(new JMenu());
        menu.item(text("lens.runAction", "Executar"), JavaIcons.run(JavaIcons.SMALL),
                        action -> launchCurrentFile(false))
                .item(text("lens.debugAction", "Depurar"), JavaIcons.debug(JavaIcons.SMALL),
                        action -> launchCurrentFile(true));
        menu.getMenu().getPopupMenu().show(mouse.getComponent(), mouse.getX(), mouse.getY());
    }

    private void launchCurrentFile(boolean debug) {
        if (currentMainClass().isEmpty()) {
            setStatusBarText("Java: " + text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
            return;
        }
        background.submit(() -> {
            try {
                String configurationId = currentFileConfigurationId();
                if (configurationId != null) {
                    requestRunConfigurationExecution(configurationId, debug);
                    return;
                }
                RunConfigurationData configuration = RunConfigurationData.builder()
                        .type(JavaRunSupport.TYPE_CURRENT_FILE)
                        .build();
                RunExecutionContext context = RunExecutionContext.builder()
                        .projectPath(projectRoot)
                        .debug(debug)
                        .build();
                if (debug) {
                    launchDebug(configuration, context);
                } else {
                    launch(configuration, context);
                }
            } catch (Exception error) {
                log.warn("Falha ao executar o arquivo atual pelo code lens.", error);
                setStatusBarText("Java: " + text("error.runLensFailed",
                        "Falha ao executar o arquivo atual") + " - " + error.getMessage());
            }
        });
    }

    private String currentFileConfigurationId() {
        List<RunConfigurationData> configurations = requestRunConfigurations();
        if (configurations == null) {
            return null;
        }
        for (RunConfigurationData configuration : configurations) {
            if (configuration == null
                    || !JavaRunSupport.TYPE_CURRENT_FILE.equalsIgnoreCase(configuration.getType())) {
                continue;
            }
            String id = configuration.getId();
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    private Optional<MainClassScanner.MainClass> mainClassAt(Path filePath, String source) {
        JavaProjectDescriptor current = descriptor;
        if (current == null || filePath == null || !MainClassScanner.hasValidMain(source)) {
            return Optional.empty();
        }
        Path file = JavaProjectConventions.normalize(filePath);
        JavaModule module = moduleContaining(current, file, false);
        boolean test = false;
        if (module == null) {
            module = moduleContaining(current, file, true);
            test = module != null;
        }
        return module == null
                ? Optional.empty()
                : MainClassScanner.inspect(file, source, module, test);
    }

    private static String codeLensTooltip(List<Location> locations) {
        String body = locations.stream()
                .limit(CODE_LENS_TOOLTIP_TARGETS)
                .map(location -> escapeHtml(locationLabel(location)))
                .collect(Collectors.joining("<br>"));
        return "<html>" + (locations.size() > CODE_LENS_TOOLTIP_TARGETS ? body + "<br>…" : body)
                + "</html>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String locationLabel(Location location) {
        Path path = JavaNavigation.path(location);
        String name = path == null ? location.uri() : path.getFileName().toString();
        return name + ":" + (location.range().start().line() + 1);
    }

    private void runTestFromLens(JavaTest test, boolean debug) {
        SwingUtilities.invokeLater(() -> {
            ensureTestPanel();
            if (testPanelId != null && !debug) {
                requestOpenToolPanel(testPanelId);
            }
            if (testPanel != null) {
                if (debug) {
                    testPanel.debugTests(List.of(test));
                } else {
                    testPanel.runTests(List.of(test));
                }
            }
        });
    }

    private void showUsagesPopup(List<Location> locations, Path currentFile, String currentText,
                                 IdeEditorContext context, Point screen, Kind kind) {
        long session = lifecycle.get();
        background.submit(() -> {
            List<UsagesPopup.Item> items = buildUsageItems(locations, currentFile, currentText);
            String header = countLabel(kind, items.size());
            SwingUtilities.invokeLater(() -> {
                if (session == lifecycle.get()) openUsagesPopup(context, screen, header, items);
            });
        });
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

    private void openUsagesPopup(IdeEditorContext context, Point screen, String header,
                                 List<UsagesPopup.Item> items) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> openUsagesPopup(context, screen, header, items));
            return;
        }
        if (context == null) {
            javax.swing.JComponent[] content = new javax.swing.JComponent[1];
            UsagesPopup.Host host = new UsagesPopup.Host() {
                public void close() {
                    java.awt.Window window = SwingUtilities.getWindowAncestor(content[0]);
                    if (window != null) window.dispose();
                }
                public void moveTo(int x, int y) {
                    java.awt.Window window = SwingUtilities.getWindowAncestor(content[0]);
                    if (window != null) window.setLocation(x, y);
                }
            };
            content[0] = UsagesPopup.content(header, items, host);
            this.<Boolean>createModernComponentDialogBuilder()
                    .title(header).component(content[0]).show();
            return;
        }
        Object[] handle = new Object[1];
        UsagesPopup.Host host = new UsagesPopup.Host() {
            @Override
            public void close() {
                context.closeEditorWindow(handle[0]);
            }
            @Override
            public void moveTo(int screenX, int screenY) {
                context.moveEditorWindow(handle[0], new Point(screenX, screenY));
            }
        };
        handle[0] = context.openEditorPopup(
                UsagesPopup.content(header, items, host), screen, true, null);
    }

    private List<UsagesPopup.Item> buildUsageItems(List<Location> locations, Path currentFile,
                                                   String currentText) {
        List<UsagesPopup.Item> items = new ArrayList<>();
        Map<Path, List<String>> linesByFile = new HashMap<>();
        Path root = projectRoot;
        for (Location location : JavaNavigation.unique(locations)) {
            Path path = JavaNavigation.path(location);
            if (path == null) {
                if (JavaClassFileNavigation.isClassFileUri(location.uri())) {
                    String name = JavaClassFileNavigation.sourceFileName(location.uri());
                    items.add(new UsagesPopup.Item(
                            text("navigation.decompiled", "Fonte de dependencia"),
                            name,
                            () -> navigateToLocation(location, null)));
                }
                continue;
            }
            int line = Math.max(0, location.range().start().line());
            String snippet = sourceLine(path, currentFile, currentText, line, linesByFile);
            Path shown = root != null && path.startsWith(root)
                    ? root.relativize(path) : path.getFileName();
            String label = (shown == null ? path.toString() : shown.toString()) + ":" + (line + 1) + ":" + (location.range().start().col() + 1);
            items.add(new UsagesPopup.Item(snippet, label, () -> navigateToLocation(location, path)));
        }
        return List.copyOf(items);
    }

    static String locationKey(Location location) {
        return JavaNavigation.key(location);
    }

    private static List<Location> uniqueLocations(List<Location> locations) {
        return JavaNavigation.unique(locations);
    }

    private String sourceLine(Path path, Path currentFile, String currentText, int line,
                              Map<Path, List<String>> cache) {
        List<String> lines = cache.computeIfAbsent(path, file -> {
            if (currentFile != null && currentText != null
                    && file.equals(currentFile.toAbsolutePath().normalize())) return currentText.lines().toList();
            IdeEditorContext open = editorContextFor(file);
            if (open != null) {
                String snapshot = onUi(open::getText);
                if (snapshot != null) return snapshot.lines().toList();
            }
            try { return Files.readAllLines(file); }
            catch (IOException unavailable) { return List.of(); }
        });
        return line >= 0 && line < lines.size() ? lines.get(line).strip() : "";
    }

    private void navigateToLocation(Location location, Path path) {
        if (location == null || location.range() == null) {
            return;
        }
        if (path == null && JavaClassFileNavigation.isClassFileUri(location.uri())) {
            navigateToClassFile(location);
            return;
        }
        if (path == null) {
            setStatusBarText(text("status.navigation.unsupportedTarget",
                    "Java: nao foi possivel abrir este destino"));
            return;
        }
        openAt(path, location.range().start().line(), location.range().start().col());
    }

    private void openAt(Path path, int line, int col) {
        if (path == null) return;
        getEditor(path, true, editor -> {
            int[] target = clampPosition(editor.getText(), line, col);
            editor.setCaretPosition(target[0], target[1]);
        });
    }

    static int[] clampPosition(String text, int line, int col) {
        if (text == null) return new int[]{Math.max(0, line), Math.max(0, col)};
        String[] lines = text.split("\\R", -1);
        int safeLine = Math.max(0, Math.min(line, lines.length - 1));
        int safeCol = Math.max(0, Math.min(col, lines[safeLine].length()));
        return new int[]{safeLine, safeCol};
    }

    private void navigateToClassFile(Location location) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || !lsp.isInteractive()) return;
        String uri = location.uri();
        int line = location.range().start().line();
        int col = location.range().start().col();
        long ticket = navigationTicket.incrementAndGet();

        SwingUtilities.invokeLater(() -> showProgress(NAVIGATION_PROGRESS_ID,
                text("progress.decompiling", "Java: abrindo fonte da dependencia...")));
        background.submit(() -> {
            String source = lsp.classFileContents(uri);
            SwingUtilities.invokeLater(() -> {
                if (ticket != navigationTicket.get()) return;
                hideProgress(NAVIGATION_PROGRESS_ID);
                if (source == null || source.isBlank()) {
                    setStatusBarText(text("status.decompileFailed",
                            "Java: nao foi possivel obter a fonte da dependencia"));
                    return;
                }
                openClassFileEditor(uri, source, line, col);
            });
        });
    }

    private CodeEditor openClassFileEditor(String uri, String source, int line, int col) {
        String fileName = JavaClassFileNavigation.sourceFileName(uri);
        String tabKey = JavaClassFileNavigation.tabKey(uri);
        closeCenterTab(tabKey);

        CodeEditor editor = requestEmbeddedCodeEditor(fileName, source,
                EmbeddedCodeEditorSettings.highlighted());
        if (editor == null) {
            setStatusBarText(text("status.decompileFailed",
                    "Java: nao foi possivel abrir a fonte da dependencia"));
            return null;
        }
        editor.setReadOnly(true);
        editor.setSearchEnabled(true);
        editor.setFoldingEnabled(true);
        applyClassFileEditorProviders(editor, uri, fileName);
        editor.setWordClickModifier(InputEvent.CTRL_DOWN_MASK);
        editor.setWordClickHandler(event -> {
            MouseEvent mouse = event.mouseEvent();
            if (mouse == null || mouse.getButton() != MouseEvent.BUTTON1
                    || (mouse.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                return;
            }
            navigateFromClassFile(uri, event.line(), event.col());
        });
        openCenterTab(tabKey, fileName + " [dependency]", editor, true);
        editor.setCaretPosition(Math.max(0, line), Math.max(0, col));
        return editor;
    }

    private void applyClassFileEditorProviders(CodeEditor editor, String uri, String fileName) {
        applyClassFileEditorProviders(editor, Path.of(fileName), editors,
                context -> classFileHover(uri, context));
    }

    static void applyClassFileEditorProviders(CodeEditor editor, Path virtual,
                                              JavaEditorRegistry editors,
                                              HoverDocumentationProvider hover) {
        TokenizerCodeEditorProvider tokenizer = editors.tokenizerFor(virtual);
        if (tokenizer != null) {
            editor.addProvider(tokenizer);
            if (editor.getTokenClassifierProvider() == null) {
                editor.addProvider(new DefaultTokenClassifierProvider());
            }
            if (editor.getTokenColorProvider() == null) {
                editor.addProvider(new DefaultTokenColorProvider());
            }
            if (editor.getTokenRenderProvider() == null) {
                editor.addProvider(new DefaultTokenRenderProvider());
            }
            editor.setSyntaxHighlightEnabled(true);
            editor.applySyntaxHighlight();
        }
        Collection<FoldRule> foldRules = editors.foldRulesFor(virtual);
        if (!foldRules.isEmpty()) {
            editor.setFoldRules(foldRules);
        }
        editor.addProvider(hover);
    }

    private HoverInfo classFileHover(String uri, HoverDocumentationContext context) {
        if (context == null || isDebugPaused()) {
            return null;
        }
        JdtLsService lsp = jdtLs;
        return lsp == null || !lsp.isInteractive()
                ? null : lsp.hoverAtUri(uri, context.line(), context.col());
    }

    private void navigateFromClassFile(String uri, int line, int col) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || !lsp.isInteractive()) return;
        background.submit(() -> {
            List<Location> targets = lsp.definitionsAtUri(uri, line, col);
            if (targets == null || targets.isEmpty()) {
                SwingUtilities.invokeLater(() -> setStatusBarText(
                        text("status.navigation.empty", "Java: nenhum destino encontrado")));
                return;
            }
            Location target = targets.getFirst();
            SwingUtilities.invokeLater(() -> navigateToLocation(target, JavaNavigation.path(target)));
        });
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        if (debugActive.get()) {
            return null;
        }
        JdtLsService lsp = interactiveServerFor(context == null ? null : context.filePath());
        return lsp == null ? null : lsp.signatureHelp(context.filePath(), context.text(),
                context.caretLine(), context.caretCol());
    }

    @Override
    public Set<Character> getSignatureTriggerCharacters() {
        return Set.of('(', ',');
    }

    @Override
    public Set<Character> getSignatureRetriggerCharacters() {
        return Set.of(',');
    }

    @Override
    public GlobalSearchResult search(GlobalSearchQuery query, GlobalSearchResult defaultResult) {
        JavaProjectDescriptor current = descriptor;
        if (query == null || current == null || !current.spring()
                || !settings().isSpringSupport()) {
            return defaultResult;
        }
        List<GlobalSearchMatch> matches =
                SpringSearchContributor.search(springIndex.snapshot(), query.term());
        if (matches.isEmpty()) {
            return defaultResult;
        }
        GlobalSearchResult spring = GlobalSearchResult.of(matches);
        return defaultResult == null ? spring : spring.merge(defaultResult);
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        if (context == null) {
            return null;
        }
        if (JavaProjectConventions.isMavenPom(context.filePath())) {
            return pomTarget(context.filePath(), context.text(), context.offset())
                    .map(target -> List.of(target.location())).orElseGet(List::of);
        }
        List<Location> configTargets = configKeyUsages(context);
        if (configTargets != null) {
            return configTargets;
        }
        return resolveDefinitions(context.filePath(), context.text(),
                context.line(), context.col());
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        if (context == null) {
            return null;
        }
        List<Location> configTargets = configKeyUsages(context);
        if (configTargets != null) {
            return configTargets;
        }
        return resolveReferences(context.filePath(), context.text(),
                context.line(), context.col());
    }

    private List<Location> configKeyUsages(IdeDefinitionContext context) {
        Path filePath = context.filePath();
        if (!SpringConfigSupport.isConfigFile(filePath) || !isSpringConfigNavigationEnabled()) {
            return null;
        }
        SpringConfigDocument.Format format =
                SpringConfigDocument.Format.of(filePath.getFileName().toString());
        String key = SpringConfigDocument.keyAt(context.text(), context.line(), format);
        if (key == null || key.isBlank()) {
            return List.of();
        }
        List<SpringNavigation.Anchor> anchors = new ArrayList<>();
        for (SpringPropertyUsage usage : springIndex.snapshot().usagesOfProperty(key)) {
            anchors.add(new SpringNavigation.Anchor(usage.file(), usage.line(), usage.key()));
        }
        return springLocations(new SpringNavigation.Target(
                SpringNavigation.Kind.CONFIG_KEY, key, anchors));
    }

    private boolean isSpringConfigNavigationEnabled() {
        JavaProjectDescriptor current = descriptor;
        return current != null && current.spring() && settings().isSpringSupport()
                && settings().isSpringConfigNavigation();
    }

    private List<Location> configKeyDefinitions(String key) {
        if (key == null || key.isBlank() || !isSpringConfigNavigationEnabled()) {
            return List.of();
        }
        List<SpringNavigation.Anchor> anchors = new ArrayList<>();
        for (SpringConfigIndex.Entry entry : springConfigIndex.definitionsOf(key)) {
            anchors.add(new SpringNavigation.Anchor(entry.file(), entry.line(), entry.key()));
        }
        return springLocations(new SpringNavigation.Target(
                SpringNavigation.Kind.CONFIG_KEY, key, anchors));
    }

    @Override
    public List<DocumentSymbol> getDocumentSymbols(IdeDocumentSymbolContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JdtLsService lsp = interactiveServerFor(filePath);
        List<DocumentSymbol> precise = lsp == null ? List.of()
                : lsp.isReady()
                        ? lsp.documentSymbols(filePath, context.text())
                        : lsp.documentSymbolsInteractive(filePath, context.text());
        if (precise != null && !precise.isEmpty()) {
            return precise;
        }
        if (lsp != null && lsp.isReady() && !lsp.isWarmingUp()) {
            return precise;
        }
        List<DocumentSymbol> outline = lexicalIndex.outline(context.text());
        return outline.isEmpty() ? precise : outline;
    }

    @Override
    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JdtLsService lsp = interactiveServerFor(filePath);
        List<DocumentHighlight> precise = lsp == null ? List.of()
                : lsp.isReady()
                        ? lsp.documentHighlights(filePath, context.text(),
                                context.line(), context.col())
                        : lsp.documentHighlightsInteractive(filePath, context.text(),
                                context.line(), context.col());
        if (precise != null && !precise.isEmpty()) {
            return precise;
        }
        if (lsp != null && lsp.isReady()) {
            return precise;
        }
        List<DocumentHighlight> approximate = bufferHighlights(context.text(),
                context.line(), context.col());
        return approximate.isEmpty() ? precise : approximate;
    }

    private static List<DocumentHighlight> bufferHighlights(String text, int line, int col) {
        JavaLocalScope.Scope scope = JavaLocalScope.at(text, line, col);
        if (scope == null) return List.of();
        List<DocumentHighlight> result = new ArrayList<>();
        result.add(new DocumentHighlight(scope.declaration(), DocumentHighlight.Kind.TEXT));
        scope.usages().forEach(range -> result.add(new DocumentHighlight(range, DocumentHighlight.Kind.TEXT)));
        return List.copyOf(result);
    }

    @Override
    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JdtLsService lsp = runningServerFor(filePath);
        return lsp == null ? null : lsp.rename(context.filePath(), context.text(),
                context.line(), context.col(), context.newName());
    }

    @Override
    public IdeWorkspaceEdit computeRenameWorkspaceEdit(IdeRenameContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        showProgress(RENAME_COMPUTE_PROGRESS_ID, text("rename.progress", "Java: renomeando para '{name}'...")
                .replace("{name}", context.newName() == null ? "" : context.newName().trim()));
        try {
            JdtLsService lsp = runningServerFor(filePath);
            if (lsp == null) {
                lsp = awaitServerForRename(filePath);
            }
            if (lsp == null) {
                return null;
            }
            IdeWorkspaceEdit edit = lsp.renameWorkspace(filePath, context.text(),
                    context.line(), context.col(), context.newName());
            String problem = lsp.lastRenameProblem();
            if (problem != null) {
                setStatusBarText(text("rename.unsafeEdit",
                        "Rename cancelado para proteger o código: {reason}").replace("{reason}", problem));
                return edit;
            }
            return edit == null || edit.isEmpty() ? edit : withLombokAccessors(lsp, context, edit);
        } finally {
            hideProgress(RENAME_COMPUTE_PROGRESS_ID);
        }
    }

    private IdeWorkspaceEdit withLombokAccessors(JdtLsService lsp, IdeRenameContext context, IdeWorkspaceEdit edit) {
        try {
            Path current = JavaProjectConventions.normalize(context.filePath());
            LombokAccessorRename.Result result = LombokAccessorRename.apply(lsp, current, context.text(),
                    context.line(), context.col(), context.newName(), edit, lexicalIndex::filesMayContain,
                    file -> renameContentOf(lsp, file, current, context.text()));
            if (result.accessors().isEmpty()) {
                return edit;
            }
            log.info("Rename com Lombok: {} chamada(s) de {} atualizada(s)", result.calls(),
                    result.accessors().stream().map(LombokAccessors.Accessor::oldName).toList());
            if (result.calls() > 0) {
                setStatusBarText(text("rename.lombokAccessors", "Java: {count} chamada(s) de métodos do Lombok renomeada(s)")
                        .replace("{count}", Integer.toString(result.calls())));
            }
            return result.edit();
        } catch (Exception e) {
            log.warn("Nao foi possivel renomear os metodos gerados pelo Lombok: {}", e.toString());
            return edit;
        }
    }

    private static String renameContentOf(JdtLsService lsp, Path file, Path current, String currentText) {
        Path normalized = JavaProjectConventions.normalize(file);
        if (normalized.equals(current)) {
            return currentText;
        }
        String open = lsp.documentContent(normalized);
        if (open != null) {
            return open;
        }
        String disk = JavaProjectConventions.readOrEmpty(normalized);
        return disk.startsWith("﻿") ? disk.substring(1) : disk;
    }

    @Override
    public boolean isRenameEnabled(Path filePath) {
        return runningServerFor(filePath) != null;
    }

    @Override
    public IdeRenamePolicy getRenamePolicy(Path filePath) {
        return JavaProjectConventions.isJava(filePath) ? IdeRenamePolicy.inline() : IdeRenamePolicy.undeclared();
    }

    @Override
    public IdeRenamePreparation prepareRename(IdeRenamePrepareContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        String text = context.text();
        JdtLsService lsp = runningServerFor(filePath);
        if (lsp == null) {
            return IdeRenamePreparation.rejected(isServerStarting(filePath)
                    ? text("rename.serverLoading", "Aguarde o servidor Java terminar de carregar para renomear")
                    : text("rename.serverUnavailable", "O servidor Java não está disponível para renomear com segurança"));
        }
        JdtLsService.PrepareRenameResult prepared = lsp.prepareRename(filePath, text, context.line(), context.col());
        if (prepared != null && !prepared.renameable()) {
            return IdeRenamePreparation.rejected(text("rename.notRenameable",
                    "Este elemento não pode ser renomeado"));
        }
        Range range = prepared == null ? null : prepared.range();
        if (range == null) {
            range = identifierRangeAt(text, context.line(), context.col());
        }
        if (range == null) {
            return null;
        }
        String placeholder = prepared == null ? null : prepared.placeholder();
        if (placeholder == null || placeholder.isBlank()) {
            placeholder = textOfRange(text, range);
        }
        List<Range> occurrences = new ArrayList<>();
        List<DocumentHighlight> highlights = lsp.documentHighlights(filePath, text, context.line(), context.col());
        if (highlights != null) {
            for (DocumentHighlight highlight : highlights) {
                if (highlight != null && highlight.range() != null) {
                    occurrences.add(highlight.range());
                }
            }
        }
        SymbolKind kind = resolveRenameKind(lsp, filePath, text, context.line(), context.col(), placeholder);
        return IdeRenamePreparation.of(range, placeholder)
                .withOccurrences(occurrences)
                .withKind(kind);
    }

    @Override
    public String validateRenameName(IdeRenamePrepareContext context, String newName) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            return null;
        }
        if (JAVA_RESERVED_WORDS.contains(name)) {
            return text("rename.reservedWord", "'{name}' é uma palavra reservada do Java").replace("{name}", name);
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return text("rename.invalidIdentifier", "'{name}' não é um identificador Java válido").replace("{name}", name);
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return text("rename.invalidIdentifier", "'{name}' não é um identificador Java válido").replace("{name}", name);
            }
        }
        return null;
    }

    private boolean isServerStarting(Path filePath) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || !JavaProjectConventions.isJava(filePath)) {
            return false;
        }
        JdtLsService.State state = lsp.getState();
        return state == JdtLsService.State.NOT_STARTED
                || state == JdtLsService.State.STARTING
                || state == JdtLsService.State.INDEXING;
    }

    private SymbolKind resolveRenameKind(JdtLsService lsp, Path filePath, String text, int line, int col, String name) {
        Position position = new Position(line, col);
        SymbolKind declared = symbolKindAt(lsp.documentSymbols(filePath, text), position);
        if (declared != null) {
            return declared;
        }
        try {
            if (JavaLocalScope.at(text, line, col) != null) {
                return SymbolKind.VARIABLE;
            }
        } catch (Exception ignored) {
        }
        List<Location> definitions = lsp.definitions(filePath, text, line, col);
        Location definition = definitions == null || definitions.isEmpty() ? null : definitions.get(0);
        if (definition != null && definition.range() != null && isSameFile(definition, filePath)) {
            SymbolKind atDefinition = symbolKindAt(lsp.documentSymbols(filePath, text), definition.range().start());
            if (atDefinition != null) {
                return atDefinition;
            }
            return SymbolKind.VARIABLE;
        }
        List<CallHierarchyItem> calls = lsp.prepareCallHierarchy(filePath, text, line, col);
        if (calls != null && !calls.isEmpty() && calls.get(0).kind() != null) {
            return calls.get(0).kind();
        }
        if (definition != null && name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            String fileName = definitionFileName(definition);
            if (fileName != null && fileName.equals(name + ".java")) {
                return SymbolKind.CLASS;
            }
        }
        return SymbolKind.FIELD;
    }

    private static SymbolKind symbolKindAt(List<DocumentSymbol> symbols, Position position) {
        if (symbols == null || position == null) {
            return null;
        }
        for (DocumentSymbol symbol : symbols) {
            if (symbol.range() != null && !symbol.range().contains(position)) {
                continue;
            }
            SymbolKind nested = symbolKindAt(symbol.children(), position);
            if (nested != null) {
                return nested;
            }
            if (symbol.selectionRange() != null && symbol.selectionRange().contains(position)) {
                return symbol.kind();
            }
        }
        return null;
    }

    private static boolean isSameFile(Location location, Path filePath) {
        if (location.isLocal()) {
            return true;
        }
        try {
            Path target = Path.of(URI.create(location.uri())).toAbsolutePath().normalize();
            return filePath != null && target.equals(filePath.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String definitionFileName(Location location) {
        if (location == null || location.isLocal()) {
            return null;
        }
        String uri = location.uri();
        int slash = uri.lastIndexOf('/');
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        int query = name.indexOf('?');
        return query >= 0 ? name.substring(0, query) : name;
    }

    private static Range identifierRangeAt(String text, int line, int col) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length) {
            return null;
        }
        String lineText = lines[line];
        int start = Math.max(0, Math.min(col, lineText.length()));
        int end = start;
        while (start > 0 && Character.isJavaIdentifierPart(lineText.charAt(start - 1))) {
            start--;
        }
        while (end < lineText.length() && Character.isJavaIdentifierPart(lineText.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        return Range.of(line, start, line, end);
    }

    private static String textOfRange(String text, Range range) {
        if (text == null || range == null || range.start().line() != range.end().line()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        int line = range.start().line();
        if (line < 0 || line >= lines.length) {
            return null;
        }
        String lineText = lines[line];
        int start = Math.max(0, Math.min(range.start().col(), lineText.length()));
        int end = Math.max(start, Math.min(range.end().col(), lineText.length()));
        return lineText.substring(start, end);
    }

    private JdtLsService awaitServerForRename(Path filePath) {
        if (!isServerStarting(filePath)) {
            return null;
        }
        JdtLsService lsp = jdtLs;
        if (lsp == null) {
            return null;
        }
        renameWaitCanceled.set(false);
        String waiting = text("status.renameWaiting",
                "Java: aguardando a indexacao para renomear com seguranca");
        showProgress(RENAME_PROGRESS_ID, waiting, true, () -> renameWaitCanceled.set(true));
        updateProgress(RENAME_PROGRESS_ID, waiting, -1, true, () -> renameWaitCanceled.set(true));
        try {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(RENAME_WAIT_BUDGET_MS);
            while (System.nanoTime() < deadline && !renameWaitCanceled.get()) {
                if (lsp.awaitReady(250)) {
                    return runningServerFor(filePath);
                }
                if (lsp.getState() == JdtLsService.State.ERROR
                        || lsp.getState() == JdtLsService.State.STOPPED) {
                    break;
                }
            }
        } finally {
            hideProgress(RENAME_PROGRESS_ID);
        }
        setStatusBarText(text("status.renameDuringIndexing",
                "Java: renomear com seguranca exige a indexacao concluida"));
        return null;
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        if (context == null) {
            return null;
        }
        CodeActionPrefetch prefetch = codeActionPrefetch.get();
        if (prefetch != null && prefetch.matches(context)) {
            List<CodeAction> prefetched = prefetch.await();
            if (prefetched != null) {
                return prefetched;
            }
        }
        return computeCodeActions(context);
    }

    private List<CodeAction> computeCodeActions(IdeCodeActionContext context) {
        List<CodeAction> actions = new ArrayList<>(suppressionActions(context));
        JdtLsService lsp = interactiveServerFor(context.filePath());
        List<CodeAction> semantic = lsp == null ? null : lsp.codeActions(context.filePath(),
                context.text(), context.range(), context.diagnostics());
        if (semantic != null) {
            actions.addAll(semantic);
        }
        return actions.isEmpty() ? semantic : actions;
    }

    private List<CodeAction> suppressionActions(IdeCodeActionContext context) {
        Path filePath = context.filePath();
        if (filePath == null || !supportsCodeActionLamp(filePath)) {
            return List.of();
        }
        String text = context.text();
        List<Diagnostic> candidates = new ArrayList<>(pluginDiagnostics(filePath, text));
        if (context.diagnostics() != null) {
            for (Diagnostic diagnostic : context.diagnostics()) {
                if (InspectionSuppressions.suppressible(diagnostic)) {
                    candidates.add(diagnostic);
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        int fromLine = -1;
        int toLine = -1;
        if (context.range() != null && context.range().start() != null) {
            fromLine = context.range().start().line();
            toLine = context.range().end() == null ? fromLine : context.range().end().line();
        }
        String[] lines = text == null ? new String[0] : text.split("\n", -1);

        List<CodeAction> actions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Diagnostic diagnostic : candidates) {
            if (!InspectionSuppressions.suppressible(diagnostic)) {
                continue;
            }
            if (fromLine >= 0 && (diagnostic.startLine() < Math.min(fromLine, toLine)
                    || diagnostic.startLine() > Math.max(fromLine, toLine))) {
                continue;
            }
            JavaInspection inspection = InspectionSuppressions.inspectionOf(diagnostic).orElse(null);
            if (inspection == null || !seen.add(inspection.id())) {
                continue;
            }
            String anchor = InspectionSuppressions.anchorAt(lines, diagnostic.startLine());
            actions.add(CodeAction.command(
                    text("action.hideHere", "Ocultar este aviso aqui") + ": " + inspection.label(),
                    new Command(HIDE_OCCURRENCE_COMMAND,
                            text("action.hideHere", "Ocultar este aviso aqui"),
                            List.of(inspection.id(),
                                    filePath.toAbsolutePath().toString(), anchor))));
            actions.add(CodeAction.command(
                    text("action.disableInspection", "Ocultar todos os avisos deste tipo")
                            + ": " + inspection.label(),
                    new Command(DISABLE_INSPECTION_COMMAND,
                            text("action.disableInspection", "Ocultar todos os avisos deste tipo"),
                            List.of(inspection.id()))));
            actions.add(suppressHereAction(inspection, lines, diagnostic.startLine()));
        }
        return List.copyOf(actions);
    }

    private CodeAction suppressHereAction(JavaInspection inspection, String[] lines, int line) {
        int anchor = InspectionSuppressions.anchorLineFor(lines, line);
        String anchorText = anchor >= 0 && anchor < lines.length ? lines[anchor] : "";
        String insertion = InspectionSuppressions.suppressionFor(anchorText, inspection.id());
        return CodeAction.quickFix(
                text("action.suppressHere", "Anotar com @SuppressWarnings"),
                List.of(TextEdit.insert(new Position(anchor, 0), insertion)));
    }

    InspectionSuppressionStore suppressions() {
        InspectionSuppressionStore existing = suppressionStore;
        if (existing != null) {
            return existing;
        }
        Path directory = null;
        try {
            directory = getResource().getResourcePath();
        } catch (Exception e) {
            log.debug("Diretorio de recursos indisponivel para as supressoes: {}", e.getMessage());
        }
        InspectionSuppressionStore created = new InspectionSuppressionStore(directory);
        suppressionStore = created;
        return created;
    }

    private InspectionSuppressions.OccurrenceFilter occurrenceFilterFor(Path filePath) {
        InspectionSuppressionStore store = suppressions();
        Path root = projectRoot;
        return (inspectionId, anchor) ->
                store.isSuppressed(root, filePath, inspectionId, anchor);
    }

    private void hideInspectionOccurrence(String inspectionId, String file, String anchor) {
        if (inspectionId == null || file == null || anchor == null) {
            return;
        }
        Path target = Path.of(file);
        suppressions().suppress(projectRoot, target, inspectionId, anchor);
        setStatusBarText(text("status.occurrenceHidden", "Java: aviso ocultado nesta ocorrencia"));
        requestRefreshDiagnostics(target);
    }

    private void disableInspection(String inspectionId) {
        JavaPluginSettings current = settings();
        current.setInspectionDisabled(inspectionId, true);
        current.save();
        setStatusBarText(text("status.inspectionDisabled", "Java: inspecao desativada")
                + " - " + inspectionId);
        javaEditors.keySet().forEach(this::requestRefreshDiagnostics);
    }

    @Override
    public List<InlayHint> getInlayHints(IdeInlayHintContext context) {
        JdtLsService lsp = runningServerFor(context == null ? null : context.filePath());
        return lsp == null ? null : lsp.inlayHints(context.filePath(), context.text(),
                context.firstLine(), context.lastLine());
    }

    @Override
    public boolean isSemanticTokensEnabled() {
        return true;
    }

    @Override
    public List<SemanticToken> getSemanticTokens(IdeSemanticTokensContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)
                || !settings().getLanguageServerMode().startsServer()) {
            return null;
        }
        JdtLsService lsp = jdtLs;
        JdtLsService.State state = lsp == null
                ? JdtLsService.State.NOT_STARTED : lsp.getState();
        if (state == JdtLsService.State.ERROR || state == JdtLsService.State.STOPPED) {
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
        JdtLsService lsp = runningServerFor(file);
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
    public boolean isGoToDeclarationEnabled() {
        return true;
    }

    @Override
    public boolean isGoToImplementationEnabled() {
        return true;
    }

    @Override
    public boolean isFindUsagesEnabled() {
        return true;
    }

    @Override
    public void onWordClick(IdeWordClickContext context) {
        if (isCtrlPomClick(context)) {
            navigatePom(context.filePath(), context.text(), context.startOffset());
            return;
        }
        if (!isCtrlDefinitionClick(context)) return;
        long ticket = beginNavigation();
        long session = lifecycle.get();
        long clickStart = System.nanoTime();
        background.submit(() -> {
            IdeEditorContext editor = context.editorContext();
            Supplier<NavigationRequest> live = () -> NavigationRequest.of(editor);
            NavigationRequest request = new NavigationRequest(context.text(), context.line(), context.col());
            Result result = resolveCurrent(live, request, context.filePath(), Kind.DEFINITION, false).result();
            Kind kind = Kind.DEFINITION;
            if (isResolved(result) && isOwnDeclaration(result.locations(), context)) {
                kind = Kind.REFERENCES;
                result = JavaNavigation.restrict(resolveCurrent(live, request, context.filePath(), kind, false)
                        .result(), Extent.DOCUMENT, context.filePath());
            }
            publishNavigationResult(ticket, session, context.editorContext(), context.filePath(),
                    context.text(), kind, result);
            if (log.isDebugEnabled()) {
                log.debug("ctrl+click resolvido em {}ms ({} destinos)",
                        elapsedMs(clickStart), result.locations().size());
            }
        });
    }

    public record NavigationRequest(String text, int line, int col) {
        static NavigationRequest of(IdeEditorContext editor) {
            return editor == null ? null : onUi(() -> new NavigationRequest(
                    editor.getText(), editor.getCaretLine(), editor.getCaretCol()));
        }
    }

    public record ResolvedNavigation(NavigationRequest request, Result result) { }

    ResolvedNavigation resolveCurrent(Supplier<NavigationRequest> live, NavigationRequest request,
                                      Path file, Kind kind, boolean followEdits) {
        Result result = resolveNavigation(file, request.text(), request.line(), request.col(), kind);
        for (int attempt = 0; attempt < NAVIGATION_RETRIES && shouldRetryNavigation(result); attempt++) {
            NavigationRequest current = live == null ? null : live.get();
            if (current == null || current.text() == null) break;
            if (!current.text().equals(request.text())) {
                if (!followEdits) break;
                request = current;
            }
            try {
                Thread.sleep(NAVIGATION_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            result = resolveNavigation(file, request.text(), request.line(), request.col(), kind);
        }
        return new ResolvedNavigation(request, result);
    }

    static boolean shouldRetryNavigation(Result result) {
        return result != null && (result.status() == Status.STALE
                || result.status() == Status.FAILED && result.locations().isEmpty());
    }

    private long beginNavigation() {
        setStatusBarText(text("status.navigation.loading", "Java: buscando destinos..."));
        return navigationRequestTicket.incrementAndGet();
    }

    private void publishNavigationResult(long ticket, long session, IdeEditorContext editor,
                                         Path file, String requestedText, Kind kind, Result result) {
        if (ticket != navigationRequestTicket.get() || session != lifecycle.get()) return;
        // Read snippets off the EDT, including snapshots of other open buffers.
        List<UsagesPopup.Item> items = needsUsagesPopup(kind, result.locations())
                ? buildUsageItems(result.locations(), file, requestedText) : List.of();
        SwingUtilities.invokeLater(() -> {
            if (ticket != navigationRequestTicket.get() || session != lifecycle.get()) return;
            if (editor == null || liveEditorFor(file) == null) {
                setStatusBarText(text("status.navigation.unavailable",
                        "Java: navegacao semantica indisponivel"));
                return;
            }
            String current = editor.getText();
            if (!Objects.equals(requestedText, current)) {
                JdtLsService lsp = jdtLs;
                if (lsp != null && current != null) background.submit(() -> lsp.changeDocument(file, current));
                setStatusBarText(text("status.navigation.stale",
                        "Java: o codigo mudou; tente novamente"));
                return;
            }
            showNavigationResult(editor, kind, result, items);
        });
    }

    private static boolean needsUsagesPopup(Kind kind, List<Location> targets) {
        return targets != null && !targets.isEmpty()
                && (targets.size() > 1 || kind == Kind.REFERENCES);
    }

    private static List<Location> localDeclaration(Path filePath, JavaLocalScope.Scope scope) {
        Path file = JavaProjectConventions.normalize(filePath);
        return file == null ? List.of()
                : List.of(Location.of(file.toUri().toString(), scope.declaration()));
    }

    static boolean isResolved(Result result) {
        return result != null && (result.status() == Status.COMPLETE || result.status() == Status.LOCAL);
    }

    static boolean isOwnDeclaration(List<Location> definitions, IdeWordClickContext context) {
        return context != null && definitions != null && definitions.size() == 1
                && JavaNavigation.contains(definitions.getFirst(), context.filePath(), context.line(), context.col());
    }

    static boolean isCtrlDefinitionClick(IdeWordClickContext context) {
        return isCtrlClick(context) && JavaProjectConventions.isJava(context.filePath());
    }

    static boolean isCtrlPomClick(IdeWordClickContext context) {
        return isCtrlClick(context) && JavaProjectConventions.isMavenPom(context.filePath());
    }

    private static boolean isCtrlClick(IdeWordClickContext context) {
        return context != null
                && context.filePath() != null
                && context.editorContext() != null
                && context.mouseButton() == MouseEvent.BUTTON1
                && (context.modifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
    }

    record PomTarget(Path file, int line, int col) {
        Location location() {
            Position position = new Position(line, col);
            return new Location(file.toUri().toString(), new Range(position, position));
        }
    }

    Optional<PomTarget> pomTarget(Path file, String text, int offset) {
        if (file == null || text == null) {
            return Optional.empty();
        }
        Optional<PomProperties.Placeholder> placeholder = PomProperties.placeholderAt(text, offset);
        if (placeholder.isPresent()) {
            return pomProperties.find(file, text, placeholder.get().name())
                    .filter(PomProperties.Declaration::navigable)
                    .map(declaration -> new PomTarget(declaration.file(), declaration.line(),
                            declaration.col()));
        }
        return pomProperties.parentAt(file, text, offset)
                .map(parent -> new PomTarget(parent.file(), parent.line(), parent.col()));
    }

    private void navigatePom(Path file, String text, int offset) {
        long session = lifecycle.get();
        background.submit(() -> {
            Optional<PomTarget> target;
            try {
                target = pomTarget(file, text, offset);
            } catch (Exception e) {
                log.debug("Falha ao resolver destino no pom: {}", e.getMessage());
                target = Optional.empty();
            }
            Optional<PomTarget> resolved = target;
            SwingUtilities.invokeLater(() -> {
                if (session != lifecycle.get()) return;
                if (resolved.isEmpty()) {
                    setStatusBarText(text("status.pomTargetMissing",
                            "Maven: declaracao nao encontrada"));
                    return;
                }
                openAt(resolved.get().file(), resolved.get().line(), resolved.get().col());
            });
        });
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
    public void onGoToDeclaration(IdeEditorContext context) {
        navigateFromEditor(context, "definition");
    }

    @Override
    public void onGoToImplementation(IdeEditorContext context) {
        navigateFromEditor(context, "implementation");
    }

    @Override
    public void onFindUsages(IdeEditorContext context) {
        navigateFromEditor(context, "usages");
    }

    @Override
    public boolean isCallHierarchyEnabled() {
        JdtLsService lsp = jdtLs;
        return lsp != null && lsp.isInteractive() && lsp.supportsCallHierarchy();
    }

    @Override
    public List<CallHierarchyItem> prepareCallHierarchy(IdeCallHierarchyContext context) {
        if (context == null) {
            return List.of();
        }
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) {
            return List.of();
        }
        return lsp.prepareCallHierarchy(context.filePath(), context.text(),
                context.line(), context.col());
    }

    @Override
    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        JdtLsService lsp = jdtLs;
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.incomingCalls(item);
    }

    @Override
    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        JdtLsService lsp = jdtLs;
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.outgoingCalls(item);
    }

    private void navigateFromEditor(IdeEditorContext context, String action) {
        if (context != null && JavaProjectConventions.isMavenPom(context.filePath())) {
            if ("definition".equals(action)) {
                navigatePom(context.filePath(), context.getText(), context.getCaretOffset());
            }
            return;
        }
        if (context == null || !isNavigationAvailable(context.filePath())) return;
        Path file = context.filePath();
        NavigationRequest request = new NavigationRequest(context.getText(),
                context.getCaretLine(), context.getCaretCol());
        long ticket = beginNavigation(), session = lifecycle.get();
        Kind kind = Kind.forAction(action);
        background.submit(() -> {
            ResolvedNavigation resolved = resolveCurrent(() -> NavigationRequest.of(context),
                    request, file, kind, true);
            publishNavigationResult(ticket, session, context, file, resolved.request().text(),
                    kind, resolved.result());
        });
    }

    private boolean isNavigationAvailable(Path filePath) {
        return JavaProjectConventions.isJava(filePath);
    }

    private void showNavigationResult(IdeEditorContext context, Kind kind, Result result,
                                      List<UsagesPopup.Item> items) {
        String status = switch (result.status()) {
            case COMPLETE -> text("status.navigation.empty", "Java: nenhum destino encontrado");
            case LOCAL -> text("status.navigation.local", "Java: resultado local");
            case INDEXING -> text("status.navigation.indexing", "Java: indexacao em andamento; tente novamente");
            case UNAVAILABLE -> text("status.navigation.unavailable", "Java: navegacao semantica indisponivel");
            case FAILED -> text("status.navigation.failed", "Java: a busca falhou; tente novamente");
            case STALE -> text("status.navigation.stale", "Java: o codigo mudou; tente novamente");
        };
        List<Location> targets = result.locations();
        setStatusBarText(targets.isEmpty() && result.status() == Status.LOCAL
                ? text("status.navigation.empty", "Java: nenhum destino encontrado") : status);
        if (targets.isEmpty()) return;
        if (result.status() == Status.COMPLETE) setStatusBarText(text("status.navigation.done", "Java: busca concluida"));
        if (targets.size() == 1 && kind != Kind.REFERENCES) {
            Location target = targets.getFirst();
            navigateToLocation(target, JavaNavigation.path(target));
            return;
        }
        openUsagesPopup(context, null, countLabel(kind, items.size()), items);
    }

    @Override
    public void onWordCaretChange(IdeWordCaretContext context) {
        long ticket = wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (context == null || context.filePath() == null || context.editorContext() == null
                || !supportsCodeActionLamp(context.filePath())) {
            return;
        }
        background.schedule(
                () -> showCodeActionLampIfCaretStayed(context, ticket), 250, TimeUnit.MILLISECONDS);
    }

    private void showCodeActionLampIfCaretStayed(IdeWordCaretContext context, long ticket) {
        if (ticket != wordCaretTicket.get()
                || !sameCaret(context, context.editorContext())) {
            return;
        }
        DiagnosticSeverity severity = lampSeverityAt(context);
        if (severity == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (ticket == wordCaretTicket.get() && sameCaret(context, context.editorContext())) {
                showCodeActionLamp(context, severity);
            }
        });
        prefetchCodeActions(context);
    }

    private void prefetchCodeActions(IdeWordCaretContext context) {
        IdeCodeActionContext actionContext = new IdeCodeActionContext(context.text(),
                context.filePath(), Range.point(context.line(), context.col()), List.of());
        CodeActionPrefetch prefetch = new CodeActionPrefetch(actionContext, new CompletableFuture<>());
        codeActionPrefetch.set(prefetch);
        try {
            prefetch.actions().complete(computeCodeActions(actionContext));
        } catch (RuntimeException e) {
            prefetch.actions().completeExceptionally(e);
        }
    }

    private record CodeActionPrefetch(IdeCodeActionContext context,
                                      CompletableFuture<List<CodeAction>> actions) {

        private static final long WAIT_MS = 3_000;

        boolean matches(IdeCodeActionContext other) {
            Range range = other.range();
            return range != null && range.start() != null && range.start().equals(range.end())
                    && range.start().equals(context.range().start())
                    && other.filePath() != null && context.filePath() != null
                    && other.filePath().toAbsolutePath().normalize()
                            .equals(context.filePath().toAbsolutePath().normalize())
                    && Objects.equals(other.text(), context.text());
        }

        List<CodeAction> await() {
            try {
                return actions.get(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private boolean supportsCodeActionLamp(Path filePath) {
        return JavaProjectConventions.isJava(filePath)
                || SpringConfigSupport.isConfigFile(filePath);
    }

    private DiagnosticSeverity lampSeverityAt(IdeWordCaretContext context) {
        DiagnosticSeverity strongest = null;
        JdtLsService lsp = jdtLs;
        if (lsp != null && JavaProjectConventions.isJava(context.filePath())) {
            Diagnostic fromServer = lsp.diagnosticAt(
                    context.filePath(), context.line(), context.col());
            if (fromServer != null) {
                strongest = fromServer.severity();
            }
        }
        for (Diagnostic diagnostic : pluginDiagnosticsAt(context.filePath(), context.text(),
                context.line())) {
            if (InspectionSuppressions.suppressible(diagnostic)) {
                strongest = strongest(strongest, diagnostic.severity());
            }
        }
        return strongest;
    }

    private List<Diagnostic> pluginDiagnosticsAt(Path filePath, String text, int line) {
        List<Diagnostic> atLine = new ArrayList<>();
        for (Diagnostic diagnostic : pluginDiagnostics(filePath, text)) {
            if (diagnostic.startLine() == line) {
                atLine.add(diagnostic);
            }
        }
        return atLine;
    }

    private static DiagnosticSeverity strongest(DiagnosticSeverity current,
                                                DiagnosticSeverity candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> 3;
            case WARNING -> 2;
            case INFO -> 1;
            case HINT -> 0;
        };
    }

    private static boolean sameCaret(IdeWordCaretContext context, IdeEditorContext editor) {
        return context != null && editor != null && editor.filePath() != null
                && Objects.equals(JavaProjectConventions.normalize(editor.filePath()),
                        JavaProjectConventions.normalize(context.filePath()))
                && editor.getCaretLine() == context.line()
                && editor.getCaretCol() == context.col()
                && Objects.equals(editor.getText(), context.text());
    }

    private void showCodeActionLamp(IdeWordCaretContext context, DiagnosticSeverity severity) {
        IdeEditorContext editorContext = context.editorContext();
        Rectangle editorBounds = editorContext == null
                ? null : editorContext.getEditorBoundsOnScreen();
        if (editorBounds == null) {
            return;
        }
        hideCodeActionLamp();
        CodeActionLamp lamp = new CodeActionLamp(loadCodeActionLampIcon(severity));
        lamp.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                hideCodeActionLamp();
                requestShowCodeActions(context.filePath());
            }
        });

        Dimension size = lamp.getPreferredSize();
        Object handle = editorContext.addEditorOverlay(lamp,
                codeActionLampBounds(editorBounds, context.mouseY(), size));
        if (handle == null) {
            return;
        }
        codeActionLampHandle = handle;
        codeActionLampContext = editorContext;
    }

    static Rectangle codeActionLampBounds(Rectangle editorBounds, int anchorY, Dimension size) {
        int maxY = Math.max(0, editorBounds.height - size.height);
        int y = Math.max(0, Math.min(anchorY - size.height / 2, maxY));
        return new Rectangle(editorBounds.x - size.width + 2, editorBounds.y + y,
                size.width, size.height);
    }

    private Icon loadCodeActionLampIcon(DiagnosticSeverity severity) {
        String path = switch (severity) {
            case ERROR -> "imgs/codeActionLampRed.svg";
            case WARNING -> "imgs/codeActionLampYellow.svg";
            default -> "imgs/codeActionLampGreen.svg";
        };
        String fallback = switch (severity) {
            case ERROR -> "OptionPane.errorIcon";
            case WARNING -> "OptionPane.warningIcon";
            default -> "OptionPane.informationIcon";
        };
        return ImageUtils.getIconByResource(JavaIdeAdapter.class, path)
                .map(icon -> ImageUtils.resizeIcon(icon, 18, 18))
                .orElseGet(() -> UIManager.getIcon(fallback));
    }


    private void hideCodeActionLamp() {
        Object handle = codeActionLampHandle;
        IdeEditorContext context = codeActionLampContext;
        codeActionLampHandle = null;
        codeActionLampContext = null;
        if (handle != null && context != null) {
            context.removeEditorOverlay(handle);
        }
    }


    private CoverageProvisioner coverageProvisioner() {
        CoverageProvisioner existing = coverageProvisioner;
        if (existing != null) {
            return existing;
        }
        JdkService jdks = jdkService;
        if (jdks == null) {
            return null;
        }
        CoverageProvisioner created = new CoverageProvisioner(jdks);
        coverageProvisioner = created;
        return created;
    }

    private void readCoverage(Path execFile, JavaProjectDescriptor current) {
        if (execFile == null || current == null) {
            return;
        }
        List<Path> classDirectories = new ArrayList<>();
        List<Path> sourceRoots = new ArrayList<>();
        for (JavaModule module : current.modules()) {
            if (module.outputDir() != null) {
                classDirectories.add(module.outputDir());
            }
            sourceRoots.addAll(module.existingSourceRoots());
            sourceRoots.addAll(module.existingTestRoots());
        }
        CoverageReadResult result = JacocoExecReader.read(execFile, classDirectories, sourceRoots);
        if (!result.isSuccess()) {
            setStatusBarText(coverageFailureText(result));
            return;
        }
        coverageStore.set(result.report());
        JavaTestExplorerPanel panel = testPanel;
        if (panel != null) {
            panel.setCoverage(result.report());
        }
        Path root = projectRoot;
        if (root != null) {
            requestRefreshCodeLenses(root);
        }
        SwingUtilities.invokeLater(this::refreshCoverageGutters);
        setStatusBarText(coverageSummaryText(result.report()));
        showCoveragePopup(result.report());
    }

    private void showCoveragePopup(CoverageReport report) {
        if (report == null || report.totals().isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> showPopup(PlatformPopupBuilder.builder()
                .component(new JavaCoveragePanel(report, this::openCoverageRow))
                .title(text("coverage.popupTitle", "Cobertura de codigo"))
                .size(760, 460)
                .modalityType(java.awt.Dialog.ModalityType.MODELESS)
                .build()));
    }

    private void openCoverageRow(JavaCoveragePanel.Row row) {
        if (row == null || row.file() == null) {
            return;
        }
        requestOpenFile(row.file());
    }

    private String coverageSummaryText(CoverageReport report) {
        CoverageReport.Totals totals = report.totals();
        if (totals.isEmpty()) {
            return text("coverage.empty",
                    "Java: nenhuma classe compilada foi coberta pela execucao");
        }
        return text("coverage.summary", "Java: cobertura")
                + " " + CoverageDisplay.percent(totals.linePercentage())
                + " (" + totals.coveredLines() + "/" + totals.totalLines() + " "
                + text("coverage.lines", "linhas") + ")";
    }

    private static boolean awaitExecFile(Path execFile) {
        long deadline = System.currentTimeMillis() + COVERAGE_SETTLE_TIMEOUT_MS;
        long lastSize = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(execFile)) {
                    long size = Files.size(execFile);
                    if (size > 0 && size == lastSize) {
                        return true;
                    }
                    lastSize = size;
                }
                Thread.sleep(COVERAGE_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Files.isRegularFile(execFile);
            } catch (Exception error) {
                log.debug("Falha ao aguardar estabilizacao do arquivo de cobertura {}",
                        execFile, error);
                return Files.isRegularFile(execFile);
            }
        }
        return Files.isRegularFile(execFile);
    }

    private String coverageFailureText(CoverageReadResult result) {
        return switch (result.failure()) {
            case MISSING_EXEC -> text("coverage.missingExec",
                    "Java: a execucao nao gerou dados de cobertura");
            case UNREADABLE_EXEC -> text("coverage.unreadableExec",
                    "Java: dados de cobertura ilegiveis") + ": " + result.detail();
            case UNSUPPORTED_BYTECODE -> text("coverage.unsupportedBytecode",
                    "Java: cobertura indisponivel, bytecode nao suportado pela versao do JaCoCo");
            case NO_CLASSES -> text("coverage.noClasses",
                    "Java: compile o projeto antes de medir a cobertura");
            case NONE -> "";
        };
    }

    private void clearCoverage() {
        coverageStore.clear();
        JavaTestExplorerPanel panel = testPanel;
        if (panel != null) {
            panel.setCoverage(null);
        }
        SwingUtilities.invokeLater(this::refreshCoverageGutters);
    }

    private void detachCoverageGutter(Path filePath) {
        IdeEditorContext context = javaEditors.get(JavaProjectConventions.normalize(filePath));
        if (context != null) {
            CoverageGutter.detach(context);
        }
    }

    private void detachAllCoverageGutters() {
        javaEditors.values().forEach(CoverageGutter::detach);
    }

    private void installCoverageGutter(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        Path file = JavaProjectConventions.normalize(context.filePath());
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return;
        }
        CoverageGutterLayer layer = CoverageGutter.attach(context);
        if (layer == null) {
            return;
        }
        if (settings().isCoverageGutter()) {
            CoverageGutter.apply(layer, coverageStore.forFile(file).orElse(null));
        } else {
            CoverageGutter.clear(layer);
        }
        context.repaintGutter();
    }

    private void refreshCoverageGutters() {
        javaEditors.values().forEach(this::installCoverageGutter);
    }

    private void installTestGutter(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        Path file = JavaProjectConventions.normalize(context.filePath());
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return;
        }
        JavaTestGutterLayer layer = context.getGutterLayer(JavaTestGutterLayer.class);
        if (layer == null) {
            layer = new JavaTestGutterLayer(this::showTestGutterMenu);
            if (!context.addGutterLayer(layer)) {
                return;
            }
        }
        layer.setColor(UiTokens.success());
        layer.setTests(JUnitTestDiscovery.discoverInSource(file, context.getText()));
        context.repaintGutter();
    }

    private void showTestGutterMenu(MouseEvent event, JavaTest test) {
        ActionMenu menu = ActionMenu.of(new JMenu());
        menu.item(text("lens.runAction", "Executar"), JavaIcons.test(JavaIcons.SMALL),
                        action -> runTestFromLens(test, false))
                .item(text("lens.debugAction", "Depurar"), JavaIcons.debug(JavaIcons.SMALL),
                        action -> runTestFromLens(test, true));
        if (coverageSupportedForProject()) {
            menu.item(text("action.runCoverage", "Rodar com cobertura"),
                    JavaIcons.test(JavaIcons.SMALL), action -> runTestWithCoverage(test));
        }
        menu.getMenu().getPopupMenu().show(event.getComponent(), event.getX(), event.getY());
    }


    private void awaitCoverageRun(RunProcessHandle handle, Path execFile,
                                  JavaProjectDescriptor current) {
        if (handle == null) {
            return;
        }
        background.submit(() -> {
            while (handle.isAlive()) {
                try {
                    Thread.sleep(COVERAGE_POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            awaitExecFile(execFile);
            readCoverage(execFile, current);
        });
    }

    private boolean coverageSupportedForProject() {
        JavaProjectDescriptor current = descriptor;
        return current != null && (current.isMaven() || current.isGradle());
    }

    private void runTestWithCoverage(JavaTest test) {
        SwingUtilities.invokeLater(() -> {
            ensureTestPanel();
            if (testPanelId != null) {
                requestOpenToolPanel(testPanelId);
            }
            if (testPanel != null) {
                testPanel.runTestsWithCoverage(List.of(test));
            }
        });
    }

    private void configureGhostText(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        context.setGhostTextEnabled(true);
        context.setGhostTextActivationMode(GhostTextActivationMode.CARET_IDLE);
        context.setGhostTextCaretIdleDelay(GHOST_TEXT_IDLE_DELAY_MS);
    }




    private static final class CodeActionLamp extends JComponent {
        private static final Color HOVER_BG = new Color(128, 128, 128, 60);
        private static final Color HOVER_BORDER = new Color(128, 128, 128, 120);
        private final Icon icon;
        private boolean hovered;

        private CodeActionLamp(Icon icon) {
            this.icon = icon;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(text("tooltip.codeActions", "Mostrar correcoes (Alt+Enter)"));
            Dimension size = new Dimension((icon == null ? 16 : icon.getIconWidth()) + 8,
                    (icon == null ? 16 : icon.getIconHeight()) + 4);
            setPreferredSize(size);
            setSize(size);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered) {
                    g.setColor(HOVER_BG);
                    g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                    g.setColor(HOVER_BORDER);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                }
                if (icon != null) {
                    icon.paintIcon(this, g, (getWidth() - icon.getIconWidth()) / 2,
                            (getHeight() - icon.getIconHeight()) / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }

    @Override
    public void onEditorOpen(IdeEditorContext editorContext) {
        configureGhostText(editorContext);
        installCoverageGutter(editorContext);
        installTestGutter(editorContext);
        installCodeActionCommandHandler(editorContext);
        installJavaShortcuts(editorContext);
        JdtLsService lsp = jdtLs;
        if (editorContext == null || !JavaProjectConventions.isJava(editorContext.filePath())) {
            return;
        }
        Path openedPath = JavaProjectConventions.normalize(editorContext.filePath());
        javaEditors.put(openedPath, editorContext);
        String openedText = Objects.toString(editorContext.getText(), "");
        lastEditorContents.put(openedPath, openedText);
        diskBaseline.put(openedPath, diskBaselineFor(openedPath, openedText));
        if (activeJavaEditor == null) {
            activeJavaEditor = editorContext;
        }
        if (lsp != null) {
            lsp.openDocument(editorContext.filePath(), openedText);
        }
        background.submit(() -> JavaLocalScope.preload(openedText));
        SwingUtilities.invokeLater(editorContext::refreshCodeLenses);
    }

    @Override
    public void onEditorSelected(IdeEditorContext editorContext) {
        autoCompleteIdle.cancel();
        activeJavaEditor = editorContext != null && JavaProjectConventions.isJava(editorContext.filePath())
                ? editorContext : null;
        if (debugActive.get()) {
            setDebugEditorAssistEnabled(false);
        }
        refreshRunButtonsForCurrentFile();
    }

    private void installCodeActionCommandHandler(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        if (!context.setCommandHandler(this::handleCodeActionCommand)) {
            log.debug("Nao foi possivel registrar as correcoes Java em {}", context.filePath());
        }
    }

    private void installJavaShortcuts(IdeEditorContext context) {
        if (context == null) {
            return;
        }
        context.registerShortcut("java.goToDefinition", "control B",
                () -> onGoToDeclaration(context));
        context.registerShortcut("java.goToImplementation", "control alt B",
                () -> onGoToImplementation(context));
        context.registerShortcut("java.findUsages", "alt F7",
                () -> onFindUsages(context));
        context.registerShortcut("java.generate", "alt INSERT",
                () -> showGenerateActions(context));
        context.registerShortcut("java.overrideMethods", "control INSERT",
                () -> showOverrideMethods(context, false));
        context.registerShortcut("java.implementMethods", "control I",
                () -> showOverrideMethods(context, true));
        context.registerShortcut("java.evaluateExpression", "alt F8",
                () -> showEvaluateDialog(context, 0));
        context.registerShortcut("java.pasteImports", "control V", EditorShortcutScope.EDITOR, () -> {
            SwingUtilities.invokeLater(() -> onPasted(context));
            return false;
        });
        bindDebugShortcut(context, "F5", "java.debug.continue",
                () -> withPausedDebugSession(JavaDebugSession::continueExecution));
        bindDebugShortcut(context, "F6", "java.debug.pause",
                () -> withDebugSession(JavaDebugSession::pause));
        bindDebugShortcut(context, "F10", "java.debug.stepOver",
                () -> withPausedDebugSession(JavaDebugSession::next));
        bindDebugShortcut(context, "F11", "java.debug.stepInto",
                () -> withPausedDebugSession(JavaDebugSession::stepIn));
        bindDebugShortcut(context, "shift F11", "java.debug.stepOut",
                () -> withPausedDebugSession(JavaDebugSession::stepOut));
        bindDebugShortcut(context, "shift F5", "java.debug.stop", this::closeDebugSession);
        bindDebugShortcut(context, "control F5", "java.debug.hotReload", this::runHotReload);
    }


    private void onPasted(IdeEditorContext context) {
        Path file = context.filePath();
        if (jdtLs == null || !JavaProjectConventions.isJava(file) || context.isReadOnly()) {
            return;
        }
        String pasted = clipboardText();
        if (pasted == null || pasted.isBlank() || !TYPE_LIKE_NAME.matcher(pasted).find()) {
            return;
        }
        String text = context.getText();
        int offset = context.getCaretOffset() - pasted.length();
        if (text == null || offset < 0 || !text.startsWith(pasted, offset)) {
            return;
        }
        pendingPasteImports.put(JavaProjectConventions.normalize(file), new PendingPasteImport(
                file, offset, pasted, rangeOf(text, offset, offset + pasted.length()),
                System.currentTimeMillis() + PASTE_IMPORT_WINDOW_MS, 1, Set.of()));
    }

    private static String clipboardText() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data instanceof String value ? value.replace("\r\n", "\n").replace("\r", "\n") : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static Range rangeOf(String text, int start, int end) {
        return Range.of(positionOf(text, start), positionOf(text, end));
    }

    private static Position positionOf(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return Position.of(line, offset - lineStart);
    }

    private void resolvePastedImports(Path path) {
        Path key = JavaProjectConventions.normalize(path);
        PendingPasteImport pending = pendingPasteImports.get(key);
        if (pending == null) {
            return;
        }
        if (System.currentTimeMillis() > pending.deadline()) {
            pendingPasteImports.remove(key, pending);
            return;
        }
        JdtLsService lsp = interactiveServerFor(pending.file());
        if (lsp == null || !pasteImportsResolving.add(key)) {
            return;
        }
        background.execute(() -> {
            boolean retry = false;
            try {
                String text = lsp.documentContent(pending.file());
                if (text == null || !text.startsWith(pending.pasted(), pending.offset())) {
                    return;
                }
                ImportCandidates.Lookup lookup = lsp.importCandidates(pending.file(), text, pending.range(),
                        pending.handled());
                if (!lookup.diagnosed()) {
                    retry = true;
                    return;
                }
                if (pendingPasteImports.remove(key, pending)) {
                    Set<String> handled = new HashSet<>(pending.handled());
                    handled.addAll(lookup.queried());
                    PendingPasteImport resolved = new PendingPasteImport(pending.file(), pending.offset(),
                            pending.pasted(), pending.range(), pending.deadline(), pending.round(),
                            Set.copyOf(handled));
                    SwingUtilities.invokeLater(() -> chooseImports(resolved, lookup.candidates()));
                }
            } finally {
                pasteImportsResolving.remove(key);
                if (retry && pendingPasteImports.get(key) == pending) {
                    background.schedule(() -> resolvePastedImports(path), PASTE_IMPORT_RETRY_MS,
                            TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    private void chooseImports(PendingPasteImport pending, Map<String, List<String>> candidates) {
        List<String> chosen = new ArrayList<>();
        Deque<Map.Entry<String, List<String>>> ambiguous = new ArrayDeque<>();
        candidates.forEach((name, options) -> {
            if (options.size() == 1) {
                chosen.add(options.getFirst());
            } else if (options.size() > 1) {
                ambiguous.add(Map.entry(name, options));
            }
        });
        askNextImport(pending, chosen, ambiguous);
    }

    private void askNextImport(PendingPasteImport pending, List<String> chosen,
                               Deque<Map.Entry<String, List<String>>> ambiguous) {
        Map.Entry<String, List<String>> next = ambiguous.poll();
        if (next == null) {
            applyPastedImports(pending, chosen);
            return;
        }
        ImportChoicePanel panel = new ImportChoicePanel(next.getKey(), next.getValue(), choice -> {
            if (choice != null) {
                chosen.add(choice);
            }
            SwingUtilities.invokeLater(() -> askNextImport(pending, chosen, ambiguous));
        });
        showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("pasteImports.title", "Importar classe"))
                .size(460, Math.min(380, 120 + next.getValue().size() * 44))
                .modalityType(java.awt.Dialog.ModalityType.MODELESS)
                .onLoad(component -> panel.focusList())
                .onClose(component -> panel.closed())
                .build());
    }

    private void applyPastedImports(PendingPasteImport pending, List<String> imports) {
        Path file = pending.file();
        IdeEditorContext editor = getEditor(file);
        if (imports.isEmpty() || editor == null || editor.isReadOnly()) {
            return;
        }
        String before = editor.getText();
        JavaImportInserter.Result result = JavaImportInserter.insert(before, imports);
        if (result.insertedLines() == 0) {
            return;
        }
        int line = editor.getCaretLine();
        int col = editor.getCaretCol();
        if (!editor.applyEdits(result.edits())) {
            return;
        }
        int shiftedLines = result.edits().stream()
                .filter(edit -> edit.range().start().line() < line
                        || (edit.range().start().line() == line && edit.range().start().col() <= col))
                .mapToInt(edit -> (int) edit.newText().chars().filter(ch -> ch == '\n').count())
                .sum();
        int expectedLine = line + shiftedLines;
        if (editor.getCaretLine() != expectedLine || editor.getCaretCol() != col) {
            editor.setCaretPosition(expectedLine, col);
        }
        String after = editor.getText();
        followUpPastedImports(pending, before, after);
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            lsp.changeDocument(file, after);
        }
        editor.refreshDiagnostics();
        setStatusBarText(text("status.pasteImports", "Java: imports adicionados") + " - "
                + imports.stream().map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.joining(", ")));
    }

    private void followUpPastedImports(PendingPasteImport pending, String before, String after) {
        if (pending.round() >= PASTE_IMPORT_MAX_ROUNDS || before == null || after == null
                || !before.startsWith(pending.pasted(), pending.offset())) {
            return;
        }
        int offset = pending.offset() + after.length() - before.length();
        if (offset < 0 || !after.startsWith(pending.pasted(), offset)) {
            return;
        }
        pendingPasteImports.putIfAbsent(JavaProjectConventions.normalize(pending.file()), new PendingPasteImport(
                pending.file(), offset, pending.pasted(),
                rangeOf(after, offset, offset + pending.pasted().length()),
                System.currentTimeMillis() + PASTE_IMPORT_FOLLOW_UP_MS, pending.round() + 1, pending.handled()));
    }

    private void bindDebugShortcut(IdeEditorContext context, String stroke,
                                   String actionId, Runnable action) {
        context.registerShortcut(actionId, stroke, EditorShortcutScope.WINDOW, () -> {
            if (!debugActive.get()) {
                return false;
            }
            action.run();
            return true;
        });
    }

    private void handleCodeActionCommand(Command command) {
        if (command == null || command.arguments() == null || command.arguments().isEmpty()) {
            return;
        }
        if (DISABLE_INSPECTION_COMMAND.equals(command.id())) {
            disableInspection(String.valueOf(command.arguments().getFirst()));
            return;
        }
        if (HIDE_OCCURRENCE_COMMAND.equals(command.id())) {
            List<Object> arguments = command.arguments();
            if (arguments.size() >= 3) {
                hideInspectionOccurrence(String.valueOf(arguments.get(0)),
                        String.valueOf(arguments.get(1)), String.valueOf(arguments.get(2)));
            }
            return;
        }
        if (!JdtLsService.APPLY_CODE_ACTION_COMMAND.equals(command.id())) {
            return;
        }
        String rawAction = String.valueOf(command.arguments().getFirst());
        String sourcePrompt = sourcePromptId(rawAction);
        if (sourcePrompt != null) {
            runSourceAction(sourcePrompt, activeJavaEditor);
            return;
        }
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            background.submit(() -> applyResolvedCodeAction(lsp, rawAction));
        }
    }

    private void applyResolvedCodeAction(JdtLsService lsp, String rawAction) {
        JdtLsService.ResolvedCodeAction resolved = lsp.resolveCodeAction(rawAction);
        if (resolved == null) {
            setStatusBarText(text("status.codeActionFailed",
                    "Java: nao foi possivel aplicar a correcao"));
            return;
        }
        if (!resolved.edit().isEmpty()
                && onUi(() -> applyWorkspaceEdit(lsp, resolved.edit())) == null) {
            return;
        }
        if (resolved.commandJson() != null) {
            lsp.executeCodeAction(resolved.commandJson());
        }
    }

    private Boolean applyWorkspaceEdit(JdtLsService lsp, IdeWorkspaceEdit edit) {
        boolean skipped = false;
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (!(operation instanceof IdeWorkspaceEdit.TextEdits textEdits)) {
                skipped = true;
                continue;
            }
            IdeEditorContext editor = getEditor(textEdits.file(), true);
            if (editor == null || editor.isReadOnly() || !editor.applyEdits(textEdits.edits())) {
                skipped = true;
                continue;
            }
            lsp.changeDocument(textEdits.file(), editor.getText());
            editor.refreshDiagnostics();
        }
        if (skipped) {
            setStatusBarText(text("status.codeActionPartial",
                    "Java: a correcao nao pode ser aplicada por completo"));
        }
        return !skipped;
    }

    private static String sourcePromptId(String rawAction) {
        if (rawAction == null) return null;
        for (String id : List.of(JdtLsService.OVERRIDE_METHODS_PROMPT,
                JdtLsService.HASHCODE_EQUALS_PROMPT,
                JdtLsService.GENERATE_TOSTRING_PROMPT,
                JdtLsService.GENERATE_ACCESSORS_PROMPT,
                JdtLsService.GENERATE_CONSTRUCTORS_PROMPT,
                JdtLsService.GENERATE_DELEGATE_METHODS_PROMPT)) {
            if (rawAction.contains(id)) return id;
        }
        return null;
    }

    private void showGenerateActions(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        List<JavaSourceActionDialogs.Choice<String>> choices = List.of(
                new JavaSourceActionDialogs.Choice<>(JdtLsService.GENERATE_CONSTRUCTORS_PROMPT,
                        text("generate.constructor", "Constructor..."), "",
                        JavaSourceActionDialogs.Kind.CONSTRUCTOR),
                new JavaSourceActionDialogs.Choice<>(JdtLsService.GENERATE_ACCESSORS_PROMPT,
                        text("generate.accessors", "Getter and Setter..."), "",
                        JavaSourceActionDialogs.Kind.ACCESSOR),
                new JavaSourceActionDialogs.Choice<>(JdtLsService.HASHCODE_EQUALS_PROMPT,
                        "equals() and hashCode()...", "", JavaSourceActionDialogs.Kind.EQUALS_HASH),
                new JavaSourceActionDialogs.Choice<>(JdtLsService.GENERATE_TOSTRING_PROMPT,
                        "toString()...", "", JavaSourceActionDialogs.Kind.TO_STRING),
                new JavaSourceActionDialogs.Choice<>("override",
                        text("generate.override", "Override Methods..."), "",
                        JavaSourceActionDialogs.Kind.OVERRIDE, "Ctrl+Insert"),
                new JavaSourceActionDialogs.Choice<>("implement",
                        text("generate.implement", "Implement Methods..."), "",
                        JavaSourceActionDialogs.Kind.IMPLEMENT, "Ctrl+I"),
                new JavaSourceActionDialogs.Choice<>(JdtLsService.GENERATE_DELEGATE_METHODS_PROMPT,
                        text("generate.delegate", "Delegate Methods..."), "",
                        JavaSourceActionDialogs.Kind.DELEGATE)
        );
        String selected = JavaSourceActionDialogs.chooseOne(createModernComponentDialogBuilder(),
                text("generate.title", "Generate"),
                text("generate.choose", "Escolha o codigo que deseja gerar"), choices);
        if (selected == null) return;
        if ("override".equals(selected)) showOverrideMethods(context, false);
        else if ("implement".equals(selected)) showOverrideMethods(context, true);
        else runSourceAction(selected, context);
    }

    private static String sourceActionTitle(String command, String fallback) {
        return switch (command) {
            case JdtLsService.GENERATE_CONSTRUCTORS_PROMPT ->
                    text("generate.constructor", "Constructor...");
            case JdtLsService.GENERATE_ACCESSORS_PROMPT ->
                    text("generate.accessors", "Getter and Setter...");
            case JdtLsService.HASHCODE_EQUALS_PROMPT -> "equals() and hashCode()...";
            case JdtLsService.GENERATE_TOSTRING_PROMPT -> "toString()...";
            case JdtLsService.GENERATE_DELEGATE_METHODS_PROMPT ->
                    text("generate.delegate", "Delegate Methods...");
            default -> fallback;
        };
    }

    private void runSourceAction(String command, IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        switch (command) {
            case JdtLsService.OVERRIDE_METHODS_PROMPT -> showOverrideMethods(context, false);
            case JdtLsService.GENERATE_CONSTRUCTORS_PROMPT -> showConstructors(context);
            case JdtLsService.GENERATE_ACCESSORS_PROMPT -> showAccessors(context);
            case JdtLsService.HASHCODE_EQUALS_PROMPT -> showHashCodeEquals(context);
            case JdtLsService.GENERATE_TOSTRING_PROMPT -> showToString(context);
            case JdtLsService.GENERATE_DELEGATE_METHODS_PROMPT -> showDelegateMethods(context);
            default -> { }
        }
    }

    private void showOverrideMethods(IdeEditorContext context, boolean implementOnly) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            JdtLsService.OverrideStatus status = lsp.overridableMethods(
                    context.filePath(), source, line, col);
            List<JdtLsService.SourceItem> methods = status.methods().stream()
                    .filter(item -> item.selected() == implementOnly).toList();
            SwingUtilities.invokeLater(() -> {
                if (methods.isEmpty()) {
                    sourceActionUnavailable(implementOnly ? "Implement Methods" : "Override Methods");
                    return;
                }
                List<JdtLsService.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(),
                        implementOnly ? text("generate.implement", "Implement Methods")
                                : text("generate.override", "Override Methods"),
                        status.type(), sourceChoices(methods, implementOnly
                                ? JavaSourceActionDialogs.Kind.IMPLEMENT
                                : JavaSourceActionDialogs.Kind.OVERRIDE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> lsp.generateOverridableMethods(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showConstructors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            JdtLsService.ConstructorsStatus status = lsp.constructorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.constructors().isEmpty()) {
                    sourceActionUnavailable(text("generate.constructor", "Constructor"));
                    return;
                }
                List<JdtLsService.SourceItem> constructors = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.constructor", "Constructor"),
                        text("generate.chooseConstructors", "Selecione os construtores da superclasse"),
                        sourceChoices(status.constructors(), JavaSourceActionDialogs.Kind.CONSTRUCTOR), item -> true);
                if (constructors == null || constructors.isEmpty()) return;
                List<JdtLsService.SourceItem> fields = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(createModernComponentDialogBuilder(),
                        text("generate.constructor", "Constructor"),
                        text("generate.chooseFields", "Selecione os campos que serao inicializados"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        JdtLsService.SourceItem::selected);
                if (fields == null) return;
                submitGeneration(context, source, () -> lsp.generateConstructors(
                        context.filePath(), source, line, col, constructors, fields));
            });
        });
    }

    private void showAccessors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            List<JdtLsService.SourceItem> available = lsp.accessorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (available.isEmpty()) {
                    sourceActionUnavailable(text("generate.accessors", "Getter and Setter"));
                    return;
                }
                List<JdtLsService.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.accessors", "Getter and Setter"),
                        text("generate.chooseAccessors", "Selecione os campos"),
                        sourceChoices(available, JavaSourceActionDialogs.Kind.ACCESSOR), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> lsp.generateAccessors(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showHashCodeEquals(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            JdtLsService.FieldsStatus status = lsp.hashCodeEqualsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.fields().isEmpty()) {
                    sourceActionUnavailable("equals() and hashCode()");
                    return;
                }
                if (status.exists() && !JavaSourceActionDialogs.confirm(createModernComponentDialogBuilder(Boolean.class),
                        "equals() and hashCode()", text("generate.regenerate",
                                "Os metodos ja existem. Deseja gerar novamente?"),
                        text("generate.regenerateAction", "Gerar novamente"))) return;
                List<JdtLsService.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), "equals() and hashCode()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> lsp.generateHashCodeEquals(
                        context.filePath(), source, line, col, selected, status.exists()));
            });
        });
    }

    private void showToString(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            JdtLsService.FieldsStatus status = lsp.toStringStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.exists() && !JavaSourceActionDialogs.confirm(createModernComponentDialogBuilder(Boolean.class),
                        "toString()", text("generate.replaceToString",
                                "toString() ja existe. Deseja substituir a implementacao?"),
                        text("generate.replace", "Substituir"))) return;
                List<JdtLsService.SourceItem> selected = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(createModernComponentDialogBuilder(), "toString()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        JdtLsService.SourceItem::selected);
                if (selected == null) return;
                submitGeneration(context, source, () -> lsp.generateToString(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showDelegateMethods(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        JdtLsService lsp = interactiveServerFor(context.filePath());
        if (lsp == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            List<JdtLsService.DelegateTarget> targets = lsp.delegateTargets(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (targets.isEmpty()) {
                    sourceActionUnavailable(text("generate.delegate", "Delegate Methods"));
                    return;
                }
                List<JavaSourceActionDialogs.Choice<JdtLsService.DelegateTarget>> choices = targets.stream()
                        .map(target -> new JavaSourceActionDialogs.Choice<>(target, target.label(), "",
                                JavaSourceActionDialogs.Kind.FIELD))
                        .toList();
                JdtLsService.DelegateTarget target = JavaSourceActionDialogs.chooseOne(
                        createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateTarget", "Selecione o campo delegado"), choices);
                if (target == null) return;
                List<JdtLsService.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateMethods", "Selecione os metodos delegados"),
                        sourceChoices(target.methods(), JavaSourceActionDialogs.Kind.DELEGATE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> lsp.generateDelegateMethods(
                        context.filePath(), source, line, col, target, selected));
            });
        });
    }

    private static List<JavaSourceActionDialogs.Choice<JdtLsService.SourceItem>> sourceChoices(
            List<JdtLsService.SourceItem> items, JavaSourceActionDialogs.Kind kind) {
        return items.stream().map(item -> new JavaSourceActionDialogs.Choice<>(
                item, item.label(), item.detail(), kind)).toList();
    }

    private void submitGeneration(IdeEditorContext context, String source,
                                  java.util.function.Supplier<List<TextEdit>> generation) {
        background.submit(() -> {
            List<TextEdit> edits = generation.get();
            SwingUtilities.invokeLater(() -> applyGeneratedEdits(context, source, edits));
        });
    }

    private void applyGeneratedEdits(IdeEditorContext context, String source, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            sourceActionUnavailable(text("generate.title", "Generate"));
            return;
        }
        if (!Objects.equals(source, context.getText())) {
            setStatusBarText(text("status.generate.changed",
                    "Java: o arquivo mudou durante a geracao; tente novamente"));
            return;
        }
        JdtLsService lsp = jdtLs;
        if (lsp == null) return;
        int line = context.getCaretLine(), col = context.getCaretCol();
        String generated = lsp.applyTextEdits(source, edits);
        context.setText(generated);
        context.setCaretPosition(line, col);
        lsp.changeDocument(context.filePath(), generated);
        context.refreshDiagnostics();
        context.refreshCodeLenses();
        setStatusBarText(text("status.generate.done", "Java: codigo gerado"));
    }

    private void sourceActionUnavailable(String action) {
        setStatusBarText(text("status.generate.unavailable", "Java: acao indisponivel")
                + " - " + action);
    }

    @Override
    public void onCodeEditorInsertText(IdeEditorContext editorContext, int offset, String inserted) {
        if (editorContext == null) {
            autoCompleteIdle.cancel();
            return;
        }
        autoCompleteIdle.typed(JavaProjectConventions.normalize(editorContext.filePath()),
                offset, inserted);
    }

    @Override
    public void onCodeEditorDeleteText(IdeEditorContext editorContext, int offset, String removed) {
        autoCompleteIdle.cancel();
    }

    @Override
    public void onCodeEditorTextChanged(IdeEditorContext editorContext) {
        JdtLsService lsp = jdtLs;
        if (editorContext != null && JavaProjectConventions.isJava(editorContext.filePath())) {
            Path edited = editorContext.filePath().toAbsolutePath().normalize();
            String currentText = Objects.toString(editorContext.getText(), "");
            String previousText = lastEditorContents.put(edited, currentText);
            if (lsp != null) {
                lsp.changeDocument(editorContext.filePath(), currentText);
            }
            if (JavaDiagnosticEdits.sameCode(previousText, currentText)) {
                if (problems.move(edited, previousText, currentText)) refreshProblemsPanel();
            } else if (problems.supersedeAll(edited)) {
                requestRefreshDiagnostics(edited);
                refreshProblemsPanel();
            }
            refreshRunButtonsForCurrentFile();
        }
    }

    @Override
    public void onEditorClose(Path filePath) {
        autoCompleteIdle.cancel();
        IdeEditorContext active = activeJavaEditor;
        if (active != null && Objects.equals(JavaProjectConventions.normalize(active.filePath()),
                JavaProjectConventions.normalize(filePath))) {
            activeJavaEditor = null;
            refreshRunButtonsForCurrentFile();
        }
        JdtLsService lsp = jdtLs;
        if (JavaProjectConventions.isJava(filePath)) {
            detachCoverageGutter(filePath);
            javaEditors.remove(JavaProjectConventions.normalize(filePath));
            diskBaseline.remove(JavaProjectConventions.normalize(filePath));
            lastEditorContents.remove(JavaProjectConventions.normalize(filePath));
            if (lsp != null) {
                lsp.closeDocument(filePath);
            }
        }
    }

    @Override
    public String onBeforeFileSave(Path filePath, String content) {
        JavaPluginSettings preferences = settings();
        if (!preferences.isFormatOnSave() && !preferences.isOrganizeImportsOnSave()) {
            return content;
        }
        JdtLsService lsp = runningServerFor(filePath);
        if (lsp == null) {
            return content;
        }
        IdeEditorContext editor = getEditor(filePath);
        String prepared = lsp.prepareSave(filePath, content, preferences.isOrganizeImportsOnSave(),
                preferences.isFormatOnSave(), editor == null ? 4 : editor.getTabSize(),
                editor == null || editor.isUseSpacesForTab());
        String latest = editor == null ? content : onUi(editor::getText);
        return latest != null && !Objects.equals(content, latest) ? latest : prepared;
    }

    @Override
    public void onAfterFileSave(Path filePath, String content) {
        JdtLsService lsp = jdtLs;
        if (JavaProjectConventions.isJava(filePath)) {
            diskBaseline.put(JavaProjectConventions.normalize(filePath), content);
            JavaProjectTreeIcons.invalidate(filePath);
            requestJavaTreeIconRefresh(filePath);
        }
        if (lsp != null && JavaProjectConventions.isJava(filePath)) {
            lsp.saveDocument(filePath, content);
        }
        if (JavaProjectConventions.isMavenPom(filePath)
                || JavaProjectConventions.isGradleBuildFile(filePath)) {
            onBuildFileChanged(filePath);
        }
        refreshTodosFor(filePath, content);
        lexicalIndex.refreshFile(filePath, content);
        JavaProjectDescriptor current = descriptor;
        if (current != null && current.spring() && JavaProjectConventions.isJava(filePath)) {
            refreshSpringIndexFor(filePath, content);
        }
        if (JavaProjectConventions.isJava(filePath) && debugSession != null
                && settings().getHotReloadMode() == HotReloadMode.AUTOMATIC) {
            long ticket = hotReloadTicket.incrementAndGet();
            background.schedule(() -> {
                if (ticket == hotReloadTicket.get() && debugSession != null) {
                    runHotReload();
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    private JdtLsService interactiveServerFor(Path filePath) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || !lsp.isInteractive() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    private JdtLsService runningServerFor(Path filePath) {
        JdtLsService lsp = jdtLs;
        if (lsp == null || !lsp.isReady() || !JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        return lsp;
    }

    private boolean isIndexing(Path filePath) {
        JdtLsService lsp = jdtLs;
        return lsp != null && lsp.getState() == JdtLsService.State.INDEXING
                && JavaProjectConventions.isJava(filePath);
    }

    private List<Location> resolveDefinitions(Path filePath, String text, int line, int col) {
        return resolveNavigation(filePath, text, line, col, Kind.DEFINITION).locations();
    }

    Result resolveNavigation(Path filePath, String source, int line, int col, Kind kind) {
        if (!JavaProjectConventions.isJava(filePath)) return Result.of(Status.UNAVAILABLE);
        // Spring property literals have their own identity; plain Java symbols belong to JDT LS.
        long springStart = System.nanoTime();
        SpringNavigation.Target spring = springTargetAt(filePath, source, line, col);
        long springMs = elapsedMs(springStart);
        if (kind == Kind.DEFINITION && spring != null && spring.kind() == SpringNavigation.Kind.CONFIG_KEY) {
            List<Location> keys = configKeyDefinitions(spring.token());
            if (!keys.isEmpty()) return new Result(Status.LOCAL, keys);
        }
        JdtLsService lsp = interactiveServerFor(filePath);
        long lspStart = System.nanoTime();
        Result semantic = lsp == null ? Result.of(isIndexing(filePath) ? Status.INDEXING : Status.UNAVAILABLE)
                : lsp.navigation(kind, filePath, source, line, col);
        long lspMs = elapsedMs(lspStart);
        if (semantic.status() == Status.COMPLETE || semantic.status() == Status.STALE
                || !semantic.locations().isEmpty()) {
            logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, 0);
            return semantic;
        }
        if (kind != Kind.IMPLEMENTATION) {
            long scopeStart = System.nanoTime();
            JavaLocalScope.Scope scope = JavaLocalScope.at(source, line, col);
            long scopeMs = elapsedMs(scopeStart);
            logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, scopeMs);
            if (scope != null) return new Result(Status.LOCAL, kind == Kind.DEFINITION
                    ? localDeclaration(filePath, scope)
                    : scope.usages().stream().map(range -> Location.of(
                            filePath.toAbsolutePath().normalize().toUri().toString(), range)).toList());
            return semantic;
        }
        logNavigationTiming(kind, filePath, semantic.status(), springMs, lspMs, 0);
        return semantic;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void logNavigationTiming(Kind kind, Path filePath, Status status,
                                            long springMs, long lspMs, long scopeMs) {
        if (!log.isDebugEnabled()) return;
        log.debug("navegacao {} em {}: status={} spring={}ms lsp={}ms localScope={}ms",
                kind, filePath == null ? "?" : filePath.getFileName(), status, springMs, lspMs, scopeMs);
    }

    private boolean isSpringAnnotationLiteral(String line, int col) {
        return isSpringNavigationEnabled()
                && SpringAnnotationCompletionProvider.opensAnnotationLiteral(line, col);
    }

    private boolean isJpaQueryLiteral(IdeCompletionContext context) {
        return isJpaCompletionEnabled(context)
                && JpaQueryCompletionProvider.isInsideQuery(context.text(), context.caretOffset());
    }

    private List<AutoCompleteItem> jpaQueryCompletion(IdeCompletionContext context) {
        if (!isJpaCompletionEnabled(context)) {
            return null;
        }
        return JpaQueryCompletionProvider.suggestions(springIndex.snapshot(), context.filePath(),
                context.text(), context.caretOffset());
    }

    private boolean isJpaCompletionEnabled(IdeCompletionContext context) {
        JavaProjectDescriptor current = descriptor;
        return context != null && JavaProjectConventions.isJava(context.filePath())
                && current != null && current.spring() && settings().isSpringSupport()
                && settings().isSpringJpa();
    }

    private List<AutoCompleteItem> springAnnotationCompletion(IdeCompletionContext context) {
        if (!isSpringNavigationEnabled()
                || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        return SpringAnnotationCompletionProvider.suggestions(springIndex.snapshot(),
                context.filePath(), context.currentLine(), context.caretCol(),
                context.caretLine());
    }

    private SpringNavigation.Target springTargetAt(Path filePath, String text, int line, int col) {
        if (!isSpringNavigationEnabled()) {
            return null;
        }
        return SpringNavigation.definitions(springIndex.snapshot(), filePath, text, line, col)
                .orElse(null);
    }

    private boolean isSpringNavigationEnabled() {
        JavaProjectDescriptor current = descriptor;
        return current != null && current.spring() && settings().isSpringSupport()
                && settings().isSpringNavigation();
    }

    private static List<Location> springLocations(SpringNavigation.Target target) {
        if (target == null || target.isEmpty()) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>();
        for (SpringNavigation.Anchor anchor : target.anchors()) {
            if (anchor.file() == null) {
                continue;
            }
            int editorLine = Math.max(0, anchor.line() - 1);
            locations.add(Location.of(anchor.file().toUri().toString(),
                    Range.of(editorLine, 0, editorLine, 0)));
        }
        return List.copyOf(locations);
    }

    private List<Location> resolveReferences(Path filePath, String text, int line, int col) {
        return resolveNavigation(filePath, text, line, col, Kind.REFERENCES).locations();
    }

    static String identifierAt(String text, int line, int col) {
        if (text == null || text.isEmpty() || line < 0 || col < 0) {
            return null;
        }
        int offset = 0;
        for (int current = 0; current < line; current++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                return null;
            }
            offset = next + 1;
        }
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        int caret = Math.min(offset + col, lineEnd);
        int start = caret;
        while (start > offset && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < lineEnd && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (start >= end) {
            return null;
        }
        String word = text.substring(start, end);
        return Character.isJavaIdentifierStart(word.charAt(0)) ? word : null;
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
        JavaProjectDescriptor current = descriptor;
        if (current == null || selectedPaths == null || selectedPaths.isEmpty()) {
            return;
        }
        Path selected = selectedPaths.getFirst();
        Path directory = Files.isDirectory(selected) ? selected : selected.getParent();
        if (directory == null) {
            return;
        }

        boolean singleDirectory = selectedPaths.size() == 1 && Files.isDirectory(selected);
        contributeNewJavaFileMenu(menu, current, directory, singleDirectory);

        int position = singleDirectory ? IDE_NEW_MENU_INDEX + 1 : Integer.MAX_VALUE;
        if (singleDirectory) {
            contributeMarkDirectoryMenu(menu, position++, directory);
        }
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        if (module != null && current.kind().hasBuildTool()) {
            menu.at(position++)
                    .withId(MENU_ID_BUILD_MODULE)
                    .item(text("tree.buildModule", "Compilar modulo"),
                            JavaIcons.buildTool(current, JavaIcons.SMALL),
                            event -> runBuild(BuildSystem.BuildAction.COMPILE,
                                    text("menu.compile", "Compilar"), module));
            menu.at(position++)
                    .withId(MENU_ID_SYNC)
                    .item(text("tree.sync", "Sincronizar projeto"),
                            JavaIcons.sync(JavaIcons.SMALL), event -> syncProject());
            menu.at(position++)
                    .withId(MENU_ID_ADD_DEPENDENCY)
                    .item(text("tree.addDependency", "Adicionar dependencia..."),
                            JavaIcons.dependency(JavaIcons.SMALL),
                            event -> openDependencyManager());
        }
        menu.at(position)
                .withId(MENU_ID_RELOAD)
                .item(text("tree.reload", "Recarregar projeto"),
                        JavaIcons.refresh(JavaIcons.SMALL), event -> clearCaches());
    }

    private void contributeMarkDirectoryMenu(IdeMenuBuilder menu, int position, Path directory) {
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        Path folder = JavaProjectConventions.normalize(directory);
        Path normalizedRoot = JavaProjectConventions.normalize(root);
        if (!folder.startsWith(normalizedRoot) || folder.equals(normalizedRoot)) {
            return;
        }
        ProjectLayout.Role marked = ProjectLayout.of(root).roleOf(folder);
        menu.at(position).withId(MENU_ID_MARK_DIRECTORY)
                .submenu(text("tree.markDirectory", "Marcar diretorio como"),
                        JavaIcons.folder(JavaIcons.SMALL), target -> {
                            for (ProjectLayout.Role role : ProjectLayout.Role.values()) {
                                target.item(markDirectoryLabel(role), markDirectoryIcon(role),
                                        role != marked,
                                        event -> markDirectoryAs(folder, role));
                            }
                            target.separator();
                            target.item(text("tree.markDirectory.clear",
                                            "Usar o padrao do projeto"),
                                    JavaIcons.sync(JavaIcons.SMALL), marked != null,
                                    event -> markDirectoryAs(folder, null));
                        });
    }

    private String markDirectoryLabel(ProjectLayout.Role role) {
        return switch (role) {
            case SOURCE -> text("tree.markDirectory.source", "Codigo-fonte");
            case TEST -> text("tree.markDirectory.test", "Codigo de teste");
            case RESOURCE -> text("tree.markDirectory.resource", "Recursos");
            case TEST_RESOURCE -> text("tree.markDirectory.testResource", "Recursos de teste");
            case EXCLUDED -> text("tree.markDirectory.excluded", "Excluida");
        };
    }

    private static Icon markDirectoryIcon(ProjectLayout.Role role) {
        return switch (role) {
            case SOURCE, RESOURCE -> JavaIcons.java(JavaIcons.SMALL);
            case TEST, TEST_RESOURCE -> JavaIcons.test(JavaIcons.SMALL);
            case EXCLUDED -> JavaIcons.stop(JavaIcons.SMALL);
        };
    }

    private void markDirectoryAs(Path folder, ProjectLayout.Role role) {
        Path root = projectRoot;
        if (root == null || folder == null) {
            return;
        }
        background.submit(() -> {
            try {
                ProjectLayout layout = ProjectLayout.of(root);
                layout.setRole(folder, role);
                layout.save();
                syncProject();
                JavaProjectStructurePanel panel = structurePanel;
                if (panel != null) {
                    panel.reload();
                }
                requestProjectTreeViewRefresh();
                String label = role == null
                        ? text("tree.markDirectory.clear", "Usar o padrao do projeto")
                        : markDirectoryLabel(role);
                setStatusBarText("Java: " + root.relativize(folder) + " - " + label);
            } catch (Exception error) {
                log.warn("Falha ao marcar o diretorio {}", folder, error);
                setStatusBarText("Java: " + text("tree.markDirectory.failed",
                        "Falha ao marcar o diretorio") + " - " + rootMessage(error));
            }
        });
    }

    private void contributeNewJavaFileMenu(IdeMenuBuilder menu, JavaProjectDescriptor current,
                                           Path directory, boolean ideOffersNewMenu) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<JavaFileTemplates.Kind> kinds = new ArrayList<>();
        for (JavaFileTemplates.Kind kind : JavaFileTemplates.Kind.values()) {
            if (!kind.isSpring() || current.spring()) {
                kinds.add(kind);
            }
        }
        if (ideOffersNewMenu) {
            menu.into(IDE_MENU_ID_NEW, target -> {
                target.separator();
                addTemplateItems(target, kinds, directory);
            });
            return;
        }
        menu.withId(MENU_ID_NEW_JAVA).submenu(text("tree.new", "Novo Java"),
                JavaIcons.java(JavaIcons.SMALL), target -> addTemplateItems(target, kinds, directory));
    }

    private void addTemplateItems(IdeMenuBuilder target, List<JavaFileTemplates.Kind> kinds,
                                  Path directory) {
        for (JavaFileTemplates.Kind kind : kinds) {
            target.item(kind.displayName(), iconFor(kind),
                    event -> createJavaFile(kind, directory));
        }
    }

    private static Icon iconFor(JavaFileTemplates.Kind kind) {
        if (kind.isSpring()) {
            return JavaIcons.spring(JavaIcons.SMALL);
        }
        return kind == JavaFileTemplates.Kind.TEST
                ? JavaIcons.test(JavaIcons.SMALL)
                : JavaIcons.java(JavaIcons.SMALL);
    }

    private void createJavaFile(JavaFileTemplates.Kind kind, Path directory) {
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return;
        }
        if (kind == JavaFileTemplates.Kind.REPOSITORY) {
            createRepository(directory, current);
            return;
        }
        if (JavaFileTemplates.acceptsInterfaces(kind)) {
            createTypeWithHeritage(kind, directory, current);
            return;
        }
        ModernInputDialog.ModernInputDialogBuilder dialog = createModernInputDialogBuilder();
        if (dialog == null) {
            return;
        }
        String typed = dialog
                .title(text("dialog.newType.title", "Novo") + " " + kind.displayName())
                .message(text("dialog.newType.message", "Nome do tipo:"))
                .show();
        if (typed == null || typed.isBlank()) {
            return;
        }
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        Path file = directory.resolve(JavaFileTemplates.fileNameOf(typed));
        writeCreatedFile(file, JavaFileTemplates.render(kind,
                JavaFileTemplates.packageOf(directory, module), typed));
    }

    private void createTypeWithHeritage(JavaFileTemplates.Kind kind, Path directory,
                                        JavaProjectDescriptor current) {
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        String packageName = JavaFileTemplates.packageOf(directory, module);
        JavaTypeCreationPanel panel = new JavaTypeCreationPanel(
                JavaFileTemplates.acceptsSuperclass(kind),
                kind == JavaFileTemplates.Kind.INTERFACE
                        ? text("dialog.newType.extends", "Estende")
                        : text("dialog.newType.implements", "Implementa"),
                this::searchTypeCandidates, background, choice -> {
            Path file = directory.resolve(JavaFileTemplates.fileNameOf(choice.name()));
            if (openIfExists(file)) {
                return;
            }
            String skeleton = JavaFileTemplates.render(kind, packageName, choice.name(),
                    choice.superclass(), choice.interfaces());
            boolean hasSuperclass = JavaFileTemplates.acceptsSuperclass(kind)
                    && !choice.superclass().isBlank();
            if (!hasSuperclass && choice.interfaces().isEmpty()) {
                writeCreatedFile(file, skeleton);
                return;
            }
            setStatusBarText(text("status.newType.generating", "Java: gerando os metodos herdados..."));
            background.submit(() -> {
                String generated = withInheritedMembers(file, skeleton, hasSuperclass);
                SwingUtilities.invokeLater(() -> {
                    writeCreatedFile(file, generated == null ? skeleton : generated);
                    if (generated == null) {
                        setStatusBarText(text("status.newType.notGenerated",
                                "Java: tipo criado sem os metodos herdados (JDT LS indisponivel)"));
                    }
                });
            });
        });
        showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("dialog.newType.title", "Novo") + " " + kind.displayName())
                .size(540, 250)
                .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                .onLoad(component -> panel.focusName())
                .build());
    }

    private List<JavaTypeCreationPanel.TypeCandidate> searchTypeCandidates(String query) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        String lower = term.toLowerCase(Locale.ROOT);
        Map<String, JavaTypeCreationPanel.TypeCandidate> found = new LinkedHashMap<>();
        springIndex.snapshot().types().stream()
                .filter(type -> type.kind() == JavaType.Kind.CLASS
                        || type.kind() == JavaType.Kind.INTERFACE)
                .filter(type -> type.simpleName().toLowerCase(Locale.ROOT).contains(lower))
                .sorted(Comparator.comparing((JavaType type) ->
                                !type.simpleName().toLowerCase(Locale.ROOT).startsWith(lower))
                        .thenComparing(JavaType::simpleName))
                .forEach(type -> found.putIfAbsent(type.qualifiedName(),
                        new JavaTypeCreationPanel.TypeCandidate(type.qualifiedName(),
                                type.kind() == JavaType.Kind.INTERFACE)));
        JdtLsService lsp = jdtLs;
        if (lsp != null && lsp.isInteractive()) {
            for (JdtLsService.TypeSymbol symbol : lsp.workspaceTypes(term)) {
                found.putIfAbsent(symbol.qualifiedName(), new JavaTypeCreationPanel.TypeCandidate(
                        symbol.qualifiedName(), symbol.isInterface()));
            }
        }
        return found.values().stream().limit(80).toList();
    }

    private String withInheritedMembers(Path file, String skeleton, boolean hasSuperclass) {
        JdtLsService lsp = interactiveServerFor(file);
        if (lsp == null) {
            return null;
        }
        try {
            String source = skeleton;
            if (hasSuperclass) {
                int line = JavaFileTemplates.closingBraceLine(source);
                JdtLsService.ConstructorsStatus constructors =
                        lsp.constructorsStatus(file, source, line, 0);
                boolean needsConstructor = !constructors.constructors().isEmpty()
                        && constructors.constructors().stream()
                        .noneMatch(item -> item.label().endsWith("()"));
                if (needsConstructor) {
                    var edits = lsp.generateConstructors(file, source, line, 0,
                            constructors.constructors(), List.of());
                    if (edits != null && !edits.isEmpty()) {
                        source = lsp.applyTextEdits(source, edits);
                    }
                }
            }
            int line = JavaFileTemplates.closingBraceLine(source);
            JdtLsService.OverrideStatus status = lsp.overridableMethods(file, source, line, 0);
            if (status.type().isBlank()) {
                return null;
            }
            List<JdtLsService.SourceItem> abstracts = status.methods().stream()
                    .filter(JdtLsService.SourceItem::selected).toList();
            if (!abstracts.isEmpty()) {
                var edits = lsp.generateOverridableMethods(file, source, line, 0, abstracts);
                if (edits != null && !edits.isEmpty()) {
                    source = lsp.applyTextEdits(source, edits);
                }
            }
            return source;
        } catch (RuntimeException error) {
            log.warn("Falha ao gerar os metodos herdados de {}", file, error);
            return null;
        } finally {
            lsp.closeDocument(file);
        }
    }

    private boolean openIfExists(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        setStatusBarText(text("status.fileExists", "Java: o arquivo ja existe") + " - "
                + file.getFileName());
        requestOpenFile(file);
        return true;
    }

    private void writeCreatedFile(Path file, String source) {
        if (openIfExists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            JavaFileChangeRouter router = fileChangeRouter;
            if (router != null) {
                router.acceptCreated(file);
            }
            requestProjectTreeRevealCreated(file);
            requestOpenFile(file);
        } catch (Exception error) {
            log.warn("Falha ao criar {}", file, error);
            setStatusBarText(text("status.createFailed", "Java: falha ao criar o arquivo") + " - "
                    + rootMessage(error));
        }
    }

    private void createRepository(Path directory, JavaProjectDescriptor current) {
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        java.util.function.Function<JpaEntity, JavaModule> moduleOf = entity -> entity.file() == null
                ? null : current.moduleOf(entity.file()).orElse(null);
        List<JpaEntity> entities = springIndex.snapshot().entities().stream()
                .filter(JpaEntity::persistent)
                .sorted(Comparator.comparing((JpaEntity entity) -> module != null
                                && !Objects.equals(module, moduleOf.apply(entity)))
                        .thenComparing(JpaEntity::type))
                .toList();
        RepositoryCreationPanel panel = new RepositoryCreationPanel(entities, entity -> {
            JavaModule owner = moduleOf.apply(entity);
            return owner == null || owner.equals(module) ? "" : owner.name();
        }, choice -> writeCreatedFile(directory.resolve(JavaFileTemplates.fileNameOf(choice.name())),
                JavaFileTemplates.renderRepository(JavaFileTemplates.packageOf(directory, module),
                        choice.name(), choice.entity().type(), choice.idType())));
        showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("dialog.repository.title", "Novo repository"))
                .size(500, 320)
                .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                .onLoad(component -> panel.focusName())
                .build());
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
        boolean debugPaused = isDebugPaused();
        String debugExpression = selectedDebugExpression(editorContext);
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
                        event -> showGenerateActions(editorContext))
                .item(text("generate.override", "Override Methods..."), enabled,
                        event -> showOverrideMethods(editorContext, false))
                .item(text("generate.implement", "Implement Methods..."), enabled,
                        event -> showOverrideMethods(editorContext, true))
                .separator()
                .item(text("debug.evaluate", "Evaluate Expression..."), debugPaused,
                        event -> showEvaluateDialog(editorContext, 0))
                .item(text("debug.addWatch", "Add Watch"), debugPaused
                                && debugExpression != null,
                        event -> addDebugWatch(debugExpression));
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
                        .onClick(event -> showGenerateActions(activeJavaEditor)))
                .add(MenuNode.item("javaOverrideMethods",
                                text("generate.override", "Override Methods..."))
                        .shortcut("control INSERT")
                        .onClick(event -> showOverrideMethods(activeJavaEditor, false)))
                .add(MenuNode.item("javaImplementMethods",
                                text("generate.implement", "Implement Methods..."))
                        .shortcut("control I")
                        .onClick(event -> showOverrideMethods(activeJavaEditor, true)))
                .add(MenuNode.item("javaEvaluateExpression",
                                text("debug.evaluate", "Evaluate Expression..."))
                        .shortcut("alt F8")
                        .onClick(event -> showEvaluateDialog(activeJavaEditor, 0)))
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
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return List.of();
        }
        RunFormContext formContext = RunFormContext.sharing(() -> descriptor, runFormChoicesLoader(),
                this::requestRunConfigurations, this::createModernDialogBuilder,
                () -> this.<Boolean>createModernComponentDialogBuilder());
        List<RunConfigurationContribution> contributions = new ArrayList<>();
        contributions.add(new JavaRunConfigurationContribution(
                JavaRunTypes.APPLICATION, formContext));
        if (current.springBoot()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.SPRING_BOOT, formContext));
        }
        contributions.add(new JavaRunConfigurationContribution(JavaRunTypes.JAR, formContext));
        // Maven aparece apenas em projetos Maven e Gradle apenas em projetos Gradle.
        if (current.isMaven()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.MAVEN, formContext));
        }
        if (current.isGradle()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.GRADLE, formContext));
        }
        if (current.isMaven() || current.isGradle()) {
            contributions.add(new JavaRunConfigurationContribution(
                    JavaRunTypes.TEST, formContext));
        }
        contributions.add(new JavaRunConfigurationContribution(JavaRunTypes.REMOTE, formContext));
        return contributions;
    }

    private List<JdkInstallation> availableJdks() {
        JdkService service = jdkService;
        return service == null ? List.of() : service.available();
    }

    private synchronized RunFormChoicesLoader runFormChoicesLoader() {
        RunFormChoicesLoader existing = runFormChoicesLoader;
        if (existing != null) {
            return existing;
        }
        RunFormChoicesLoader created = new RunFormChoicesLoader(() -> descriptor,
                this::availableJdks, background, SwingUtilities::invokeLater,
                dtm.stools.i18n.I18n.getText(
                        dtm.ide.run.form.RunConfigurationFormBase.class,
                        "field.jdk.project", "JDK do projeto"));
        runFormChoicesLoader = created;
        return created;
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
        selectedRunConfig = configuration;
        refreshCoverageButton();
        if (JavaRunSupport.isCurrentFileType(configuration)) {
            refreshRunButtonsForCurrentFile();
            return;
        }
        if (configuration == null || !JavaRunTypes.isJavaType(configuration)) {
            return;
        }
        String type = configuration.getType();
        // A API do formulario nao permite bloquear o Apply, entao a configuracao invalida e
        // aceita mas nao pode ser executada: Run e Debug ficam desabilitados.
        boolean valid = JavaRunValidation.validate(configuration,
                JavaRunValidation.Context.of(descriptor)).isValid();
        boolean runnable = valid && JavaRunTypes.supportsRun(type);
        boolean debuggable = valid && JavaRunTypes.supportsDebug(type);
        SwingUtilities.invokeLater(() -> {
            requestSetRunButtonEnabled(runnable);
            requestSetDebugButtonEnabled(debuggable);
        });
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
        if (configuration != null && JavaRunTypes.REMOTE.equals(configuration.getType())) {
            return ensureRunSupport().failure(text("error.remoteIsDebugOnly",
                    "Remote JVM so pode ser iniciado pelo botao Debug."));
        }
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                () -> ensureRunSupport().launch(resolved, context));
        trackRunningProcess(configuration, handle);
        return handle;
    }

    @Override
    public RunProcessHandle launchCoverage(RunConfigurationData configuration,
                                           RunExecutionContext context) {
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        String coverageArgument = coverageArgumentFor(resolved);
        if (coverageArgument == null) {
            return ensureRunSupport().failure(text("coverage.unsupportedRunType",
                    "Java: cobertura no Run so vale para Aplicacao, Spring Boot e JAR"));
        }
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                () -> ensureRunSupport().launchWithCoverage(resolved, context, coverageArgument));
        trackRunningProcess(configuration, handle);
        awaitCoverageRun(handle, CoverageAgent.execFileFor(descriptor.root()), descriptor);
        return handle;
    }

    private String coverageArgumentFor(RunConfigurationData configuration) {
        JavaProjectDescriptor current = descriptor;
        if (current == null || configuration == null) {
            return null;
        }
        if (!JavaRunTypes.LOCAL_JVM.contains(configuration.getType())) {
            return null;
        }
        CoverageProvisioner provisioner = coverageProvisioner();
        Path agent = provisioner == null ? null : provisioner.ensureAgent().orElse(null);
        Path execFile = CoverageAgent.execFileFor(current.root());
        if (agent == null || execFile == null) {
            setStatusBarText(text("coverage.agentMissing",
                    "Java: nao foi possivel preparar o agente de cobertura"));
            return null;
        }
        try {
            Files.createDirectories(execFile.getParent());
            Files.deleteIfExists(execFile);
        } catch (Exception error) {
            setStatusBarText(text("coverage.agentMissing",
                    "Java: nao foi possivel preparar o agente de cobertura"));
            return null;
        }
        return CoverageAgent.agentArgument(agent, execFile, false);
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData configuration,
                                        RunExecutionContext context) {
        if (configuration != null && JavaRunTypes.REMOTE.equals(configuration.getType())) {
            return launchRemoteDebug(configuration, context);
        }
        if (configuration != null && JavaRunTypes.BUILD_TOOL.contains(configuration.getType())
                && !JavaRunTypes.TEST.equals(configuration.getType())) {
            return launchBuildToolDebug(configuration, context);
        }
        RunConfigurationData resolved = resolveCurrentFileConfiguration(configuration).orElse(null);
        if (resolved == null) {
            return ensureRunSupport().failure(text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
        }
        RunExecutionContext debugContext = context == null
                ? RunExecutionContext.builder().projectPath(projectRoot).debug(true).build()
                : context;
        debugContext.setDebug(true);
        if (debugContext.getBreakpoints() == null || debugContext.getBreakpoints().isEmpty()) {
            debugContext.setBreakpoints(requestWorkspaceBreakpoints());
        }
        warmUpDebugAdapter();
        int jdwpPort = DebugPorts.allocate();
        JavaModule targetModule = resolveDebugModule(resolved);
        RunProcessHandle handle = launchWithBuildProgress(resolved,
                () -> ensureRunSupport().launch(resolved, debugContext, jdwpPort));
        if (handle.isAlive()) {
            trackRunningProcess(configuration, handle);
            startDebugSession(jdwpPort, debugContext, handle::terminate, targetModule, handle);
            requestSetRunButtonRunning(true);
        }
        return handle;
    }

    /**
     * Inicia a depuracao de uma JVM remota.
     *
     * <p>No modo Attach a Orion conecta diretamente na JVM. No modo Listen ela abre a porta e
     * espera a JVM conectar, retransmitindo o trafego JDWP por um {@link JdwpRelay} porque o
     * debug adapter oficial nao sabe escutar.</p>
     */
    private RunProcessHandle launchRemoteDebug(RunConfigurationData configuration,
                                               RunExecutionContext context) {
        JavaRunValidation.Report report = JavaRunValidation.validate(configuration,
                JavaRunValidation.Context.of(descriptor));
        if (!report.isValid()) {
            return ensureRunSupport().failure(report.firstMessage());
        }
        Optional<String> chainFailure = ensureRunSupport().runBeforeLaunchChain(configuration);
        if (chainFailure.isPresent()) {
            return ensureRunSupport().failure(chainFailure.get());
        }
        RemoteDebugSettings settings = RemoteDebugSettings.from(configuration);
        RunExecutionContext debugContext = debugContextOf(context);
        JavaModule targetModule = resolveDebugModule(configuration);

        if (!settings.listen()) {
            startDebugSession(settings.attachTarget(), debugContext, null, targetModule, null);
            return ProcessLauncher.message(text("remote.attaching", "Conectando a")
                    + " " + settings.host() + ":" + settings.port());
        }

        JdwpRelay relay;
        try {
            relay = JdwpRelay.open(settings.host(), settings.port());
        } catch (IOException error) {
            return ensureRunSupport().failure(text("error.remoteBind",
                    "Nao foi possivel escutar em") + " " + settings.host() + ":"
                    + settings.port() + " - " + rootMessage(error));
        }
        debugRelay = relay;
        background.submit(() -> {
            try {
                relay.awaitTarget(settings.timeoutMillis());
                startDebugSession(settings.relayTarget(relay.adapterPort()), debugContext,
                        null, targetModule, null);
            } catch (SocketTimeoutException timeout) {
                closeDebugRelay();
                setStatusBarText(text("error.remoteTimeout",
                        "Java Debug: nenhuma JVM conectou dentro do tempo limite."));
            } catch (IOException error) {
                closeDebugRelay();
                setStatusBarText("Java Debug: " + rootMessage(error));
            }
        });
        return ProcessLauncher.message(text("remote.listening", "Aguardando a JVM conectar em")
                + " " + settings.host() + ":" + settings.port());
    }

    private RunProcessHandle launchBuildToolDebug(RunConfigurationData configuration,
                                                  RunExecutionContext context) {
        warmUpDebugAdapter();
        JavaModule targetModule = resolveDebugModule(configuration);
        AtomicReference<RunProcessHandle> launched = new AtomicReference<>();
        BuildToolDebugListener listener;
        try {
            listener = openBuildDebugListener(targetModule, () -> {
                RunProcessHandle running = launched.get();
                if (running != null) {
                    running.terminate();
                }
            });
        } catch (IOException error) {
            return ensureRunSupport().failure(text("error.buildDebugListen",
                    "Nao foi possivel abrir a porta de debug:") + " " + rootMessage(error));
        }
        RunProcessHandle handle = launchWithBuildProgress(configuration,
                () -> ensureRunSupport().launch(configuration, context, listener.listenPort()));
        if (!handle.isAlive()) {
            listener.close();
            return handle;
        }
        launched.set(handle);
        trackRunningProcess(configuration, handle);
        requestSetRunButtonRunning(true);
        background.submit(() -> {
            try {
                while (handle.isAlive() && !listener.isClosed()) {
                    Thread.sleep(200);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                listener.close();
            }
        });
        return handle;
    }

    private BuildToolDebugListener openBuildDebugListener(JavaModule targetModule,
                                                          Runnable cancelProcess) throws IOException {
        return openBuildDebugListener(targetModule, cancelProcess, () -> {
        });
    }

    private BuildToolDebugListener openBuildDebugListener(JavaModule targetModule,
                                                          Runnable cancelProcess,
                                                          Runnable onAttach) throws IOException {
        return BuildToolDebugListener.open((target, detached) -> {
                    onAttach.run();
                    startDebugSession(target, debugContextOf(null),
                            () -> detached.accept(!naturalDebugEnd.getAndSet(false)),
                            targetModule, null);
                },
                cancelProcess, background);
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

    private RunExecutionContext debugContextOf(RunExecutionContext context) {
        RunExecutionContext debugContext = context == null
                ? RunExecutionContext.builder().projectPath(projectRoot).debug(true).build()
                : context;
        debugContext.setDebug(true);
        if (debugContext.getBreakpoints() == null || debugContext.getBreakpoints().isEmpty()) {
            debugContext.setBreakpoints(requestWorkspaceBreakpoints());
        }
        return debugContext;
    }

    private boolean supportsHotReloadForSelection() {
        RunConfigurationData selected = selectedRunConfig;
        String type = selected == null ? null : selected.getType();
        return type == null || JavaRunTypes.supportsHotReload(type);
    }

    private void closeDebugRelay() {
        JdwpRelay relay = debugRelay;
        debugRelay = null;
        if (relay != null) {
            relay.close();
        }
    }

    private void trackRunningProcess(RunConfigurationData configuration,
                                     RunProcessHandle handle) {
        if (handle == null || !handle.isAlive()) {
            return;
        }
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        runningProcesses.put(key, handle);
        background.submit(() -> {
            try {
                while (handle.isAlive()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                runningProcesses.remove(key, handle);
                requestSetRunButtonRunning(hasRunningProcess());
            }
        });
    }

    private RunProcessHandle runningProcess(RunConfigurationData configuration) {
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        RunProcessHandle handle = runningProcesses.get(key);
        if (handle != null && !handle.isAlive()) {
            runningProcesses.remove(key, handle);
            return null;
        }
        return handle;
    }

    private boolean hasRunningProcess() {
        return runningProcesses.values().stream().anyMatch(RunProcessHandle::isAlive);
    }

    private record RunConfigurationKey(String type, String title, Map<String, Object> properties) {

        private static RunConfigurationKey of(RunConfigurationData configuration) {
            if (configuration == null) {
                return new RunConfigurationKey("", "", Map.of());
            }
            Map<String, Object> properties = configuration.getProperties() == null
                    ? Map.of() : new LinkedHashMap<>(configuration.getProperties());
            return new RunConfigurationKey(
                    Objects.toString(configuration.getType(), ""),
                    Objects.toString(configuration.getTitle(), ""), properties);
        }
    }

    private void refreshRunButtonsForCurrentFile() {
        refreshCoverageButton();
        if (!JavaRunSupport.isCurrentFileType(selectedRunConfig)) {
            return;
        }
        boolean runnable = currentMainClass().isPresent();
        SwingUtilities.invokeLater(() -> {
            requestSetRunButtonEnabled(runnable);
            requestSetDebugButtonEnabled(runnable);
        });
    }

    private void refreshCoverageButton() {
        boolean available = coverageSupportedForProject() && coverageRunnableConfiguration();
        SwingUtilities.invokeLater(() -> {
            requestSetCoverageButtonVisible(available);
            requestSetCoverageButtonEnabled(available);
        });
    }

    private boolean coverageRunnableConfiguration() {
        RunConfigurationData configuration = selectedRunConfig;
        if (JavaRunSupport.isCurrentFileType(configuration)) {
            return currentMainClass().isPresent();
        }
        return configuration != null && JavaRunTypes.LOCAL_JVM.contains(configuration.getType());
    }

    private Optional<RunConfigurationData> resolveCurrentFileConfiguration(
            RunConfigurationData configuration) {
        if (!JavaRunSupport.isCurrentFileType(configuration)) {
            return Optional.ofNullable(configuration);
        }
        return currentMainClass().map(mainClass -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            if (configuration.getProperties() != null) {
                properties.putAll(configuration.getProperties());
            }
            properties.put(JavaRunSupport.PROPERTY_MAIN_CLASS, mainClass.qualifiedName());
            properties.put(JavaRunSupport.PROPERTY_MODULE, mainClass.module().name());
            if (mainClass.test()) {
                properties.put(JavaRunSupport.PROPERTY_TEST_CLASSPATH, Boolean.TRUE.toString());
            }
            return RunConfigurationData.builder()
                    .type(mainClass.springBoot() ? JavaRunSupport.TYPE_SPRING_BOOT
                            : JavaRunSupport.TYPE_RUN)
                    .title(mainClass.simpleName())
                    .properties(properties)
                    .build();
        });
    }

    private Optional<MainClassScanner.MainClass> currentMainClass() {
        IdeEditorContext editor = activeJavaEditor;
        JavaProjectDescriptor current = descriptor;
        if (editor == null || current == null || editor.filePath() == null) {
            return Optional.empty();
        }
        Path file = JavaProjectConventions.normalize(editor.filePath());
        JavaModule module = moduleContaining(current, file, false);
        boolean test = false;
        if (module == null) {
            module = moduleContaining(current, file, true);
            test = module != null;
        }
        if (module == null) {
            return Optional.empty();
        }
        String source = editor.getText();
        if (!MainClassScanner.hasValidMain(source)) {
            return Optional.empty();
        }
        return MainClassScanner.inspect(file, source, module, test);
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
        return module == null || module.isAggregator() ? null : module.artifactId();
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

    private void startDebugSession(int jdwpPort, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule) {
        startDebugSession(JavaAttachTarget.local(jdwpPort), context, terminateDebuggee,
                targetModule, null);
    }

    private void startDebugSession(int jdwpPort, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule,
                                   RunProcessHandle processHandle) {
        startDebugSession(JavaAttachTarget.local(jdwpPort), context, terminateDebuggee,
                targetModule, processHandle);
    }

    private boolean awaitLanguageServerForDebug(JdtLsService lsp, long generation)
            throws InterruptedException {
        boolean announced = false;
        while (!lsp.isWorkspaceSettled()) {
            if (generation != debugStartGeneration.get()) {
                return false;
            }
            if (lsp.getState() == JdtLsService.State.ERROR) {
                throw new IllegalStateException(text("debug.lspFailed",
                        "O IntelliSense Java falhou; reinicie-o para depurar."));
            }
            if (!announced) {
                announced = true;
                publishDebugSnapshot(JavaDebugSnapshot.starting(text("debug.waitingLsp",
                        "Aguardando o IntelliSense Java terminar de carregar o projeto...")));
            }
            Thread.sleep(LSP_DEBUG_POLL_MS);
        }
        return generation == debugStartGeneration.get();
    }

    private void warmUpDebugAdapter() {
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            background.submit(lsp::prepareDebugAdapter);
        }
    }

    private void startDebugSession(JavaAttachTarget attachTarget, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule,
                                   RunProcessHandle processHandle) {
        closeDebugSession();
        debugActive.set(true);
        requestSetRunButtonRunning(true);
        debugEdtWatchdog.start();
        debuggeeTerminator = terminateDebuggee;
        debugProcessHandle = processHandle;
        debugSteppedFiles.clear();
        setDebugEditorAssistEnabled(false);
        debugModule = targetModule;
        JavaDebugPanel panel = ensureDebugPanel();
        panel.update(JavaDebugSnapshot.starting("Preparando depurador Java..."));
        if (debugPanelId != null) {
            requestOpenToolPanel(debugPanelId);
        }
        requestSetHotReloadButtonVisible(true);
        requestSetHotReloadButtonEnabled(false);
        long generation = debugStartGeneration.get();
        background.submit(() -> {
            try {
                JdtLsService lsp = ensureLanguageServer();
                if (!awaitLanguageServerForDebug(lsp, generation)) {
                    return;
                }
                if (!lsp.isDebugAdapterAvailable()) {
                    throw new IllegalStateException("O servidor de debug Java nao esta disponivel. Reinicie o IntelliSense Java.");
                }
                int adapterPort = lsp.startDebugSession();
                if (adapterPort <= 0) {
                    throw new IllegalStateException("O JDT LS nao abriu uma sessao de debug.");
                }
                JavaDebugSession session = new JavaDebugSession(adapterPort, attachTarget,
                        projectRoot, context.getBreakpoints(), this::publishDebugSnapshot,
                        background)
                        .projectName(debugProjectName(targetModule));
                debugSession = session;
                session.start();
            } catch (Exception error) {
                log.warn("Falha ao iniciar a sessao de debug Java", error);
                terminateDebuggee();
                publishDebugSnapshot(new JavaDebugSnapshot(JavaDebugSnapshot.State.ERROR,
                        rootMessage(error), 0, List.of(), List.of(), List.of()));
            }
        });
    }

    private JavaDebugPanel ensureDebugPanel() {
        JavaDebugPanel existing = debugPanel;
        if (existing != null) {
            return existing;
        }
        JavaDebugPanel created = new JavaDebugPanel(new DebugPanelHost(), background);
        debugValuePopup().bindChildrenProvider(reference -> {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variables(reference);
        });
        debugPanel = created;
        debugPanelId = registerToolPanel(DockRegion.BOTTOM, "Debug", ToolIconType.DEBUG, created,
                new Dimension(920, 350));
        return created;
    }

    private void publishDebugSnapshot(JavaDebugSnapshot snapshot) {
        JavaDebugPanel panel = debugPanel;
        if (panel != null) {
            panel.update(snapshot);
        }
        boolean active = snapshot.state() != JavaDebugSnapshot.State.TERMINATED
                && snapshot.state() != JavaDebugSnapshot.State.ERROR;
        if (!active) {
            debugActive.set(false);
            debugEdtWatchdog.stop();
            debugSession = null;
            debugProcessHandle = null;
            closeDebugRelay();
            requestSetRunButtonRunning(hasRunningProcess());
            setDebugEditorAssistEnabled(true);
        }
        if (snapshot.state() != JavaDebugSnapshot.State.PAUSED) {
            clearDebugPosition();
            debugHoverTicket.incrementAndGet();
            hideDebugValuePopup();
        }
        if (!active) {
            repaintDebugBreakpointLines();
            forgetDebugLibrarySources();
        }
        // JAR externo e Remote JVM nao tem classes locais confiaveis para recarregar.
        boolean hotReloadable = active && supportsHotReloadForSelection();
        requestSetHotReloadButtonVisible(hotReloadable);
        requestSetHotReloadButtonEnabled(hotReloadable);
        if (!snapshot.message().isBlank()) {
            setStatusBarText("Java Debug: " + snapshot.message().trim());
        }
        if (!active) {
            naturalDebugEnd.set(snapshot.state() == JavaDebugSnapshot.State.TERMINATED);
            terminateDebuggee();
            naturalDebugEnd.set(false);
        }
    }

    private final class DebugPanelHost implements JavaDebugPanel.Host {
        @Override
        public void resume() {
            withDebugSession(JavaDebugSession::continueExecution);
        }

        @Override
        public void pause() {
            withDebugSession(JavaDebugSession::pause);
        }

        @Override
        public void next() {
            withDebugSession(JavaDebugSession::next);
        }

        @Override
        public void stepIn() {
            withDebugSession(JavaDebugSession::stepIn);
        }

        @Override
        public void stepOut() {
            withDebugSession(JavaDebugSession::stepOut);
        }

        @Override
        public void stop() {
            closeDebugSession();
        }

        @Override
        public void hotReload() {
            runHotReload();
        }

        @Override
        public void openFile(Path file, int line) {
            highlightDebugLine(file, line);
        }

        @Override
        public void openLibrarySource(String uri, int line) {
            highlightDebugLibraryLine(uri, line);
        }

        @Override
        public JavaDebugSnapshot.Variable evaluate(String expression, int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? null : session.evaluate(expression, frameId);
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variables(int reference) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variables(reference);
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variablesForFrame(int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.variablesForFrame(frameId);
        }

        @Override
        public List<JavaDebugSnapshot.Scope> scopesForFrame(int frameId) throws Exception {
            JavaDebugSession session = debugSession;
            return session == null ? List.of() : session.scopesForFrame(frameId);
        }

        @Override
        public void showEvaluate(int frameId) {
            showEvaluateDialog(activeJavaEditor, frameId);
        }

        @Override
        public void selectThread(int threadId) {
            withDebugSession(session -> session.selectThread(threadId));
        }
    }

    private void withDebugSession(java.util.function.Consumer<JavaDebugSession> action) {
        JavaDebugSession session = debugSession;
        if (session != null) {
            background.submit(() -> action.accept(session));
        }
    }

    private void withPausedDebugSession(java.util.function.Consumer<JavaDebugSession> action) {
        if (isDebugPaused()) {
            withDebugSession(action);
        }
    }

    private void closeDebugSession() {
        debugStartGeneration.incrementAndGet();
        closeDebugRelay();
        JavaDebugSession session = debugSession;
        debugSession = null;
        debugActive.set(false);
        debugEdtWatchdog.stop();
        debugModule = null;
        debugProcessHandle = null;
        debugHoverTicket.incrementAndGet();
        hideDebugValuePopup();
        if (session != null) {
            background.submit(session::close);
        }
        Runnable terminator = takeDebuggeeTerminator();
        if (terminator != null) {
            background.submit(() -> runDebuggeeTerminator(terminator));
        }
        requestSetRunButtonRunning(hasRunningProcess());
        requestSetHotReloadButtonEnabled(false);
        requestSetHotReloadButtonVisible(false);
        setDebugEditorAssistEnabled(true);
        clearDebugPosition();
        repaintDebugBreakpointLines();
    }

    private void terminateDebuggee() {
        runDebuggeeTerminator(takeDebuggeeTerminator());
    }

    private synchronized Runnable takeDebuggeeTerminator() {
        Runnable terminator = debuggeeTerminator;
        debuggeeTerminator = null;
        return terminator;
    }

    private void runDebuggeeTerminator(Runnable terminator) {
        if (terminator == null) {
            return;
        }
        try {
            terminator.run();
        } catch (RuntimeException error) {
            log.debug("Falha ao finalizar processo Java: {}", error.getMessage());
        }
    }

    private void setDebugEditorAssistEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = activeJavaEditor;
            if (editor == null) {
                return;
            }
            editor.setAutoCompleteOnTyping(enabled);
            if (!enabled) {
                editor.clearGhostText();
                editor.hideAutoCompletePopup();
            }
            requestRepaintCodeEditor(editor.filePath());
        });
    }


    private boolean isDebugPaused() {
        JavaDebugSession session = debugSession;
        return session != null && session.snapshot().state() == JavaDebugSnapshot.State.PAUSED;
    }

    private void showEvaluateDialog(IdeEditorContext editor, int frameId) {
        if (!isDebugPaused()) {
            setStatusBarText(text("debug.evaluate.paused", "Pause o programa para avaliar expressoes."));
            return;
        }
        JavaDebugSession session = debugSession;
        if (session == null) {
            return;
        }
        String initial = selectedDebugExpression(editor);
        JavaEvaluateDialog content = new JavaEvaluateDialog(initial,
                expression -> session.evaluate(expression, frameId), session::variables,
                this::addDebugWatch, background);
        editor.openEditorDialog(text("debug.evaluate.title", "Evaluate Expression"),
                content, false, null);
        content.open();
    }

    private void addDebugWatch(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        ensureDebugPanel().addWatch(expression.trim());
        if (debugPanelId != null) {
            requestOpenToolPanel(debugPanelId);
        }
    }

    private static String selectedDebugExpression(IdeEditorContext editor) {
        if (editor == null) {
            return null;
        }
        String selected = editor.getSelectedTextOrEmpty();
        if (selected != null && !selected.isBlank()) {
            String candidate = selected.trim();
            return candidate.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")
                    ? candidate : null;
        }
        return safeDebugExpression(editor.getText(), editor.getCaretOffset());
    }

    static String safeDebugExpression(String source, int offset) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        int at = Math.max(0, Math.min(offset, source.length() - 1));
        if (!Character.isJavaIdentifierPart(source.charAt(at)) && at > 0
                && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
            at--;
        }
        if (!Character.isJavaIdentifierPart(source.charAt(at))) {
            return null;
        }
        int start = at;
        int end = at + 1;
        while (start > 0 && debugExpressionCharacter(source.charAt(start - 1))) {
            start--;
        }
        while (end < source.length() && debugExpressionCharacter(source.charAt(end))) {
            end++;
        }
        String value = source.substring(start, end).replaceAll("^\\.+|\\.+$", "");
        int following = end;
        while (following < source.length() && Character.isWhitespace(source.charAt(following))) {
            following++;
        }
        if (following < source.length() && source.charAt(following) == '(') {
            return null;
        }
        return value.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*") ? value : null;
    }

    private static Point pointerLocation() {
        try {
            return MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean debugExpressionCharacter(char value) {
        return Character.isJavaIdentifierPart(value) || value == '.';
    }

    private void clearDebugPosition() {
        debugLineTicket.incrementAndGet();
        if (SwingUtilities.isEventDispatchThread()) {
            clearDebugPositionNow();
        } else {
            SwingUtilities.invokeLater(this::clearDebugPositionNow);
        }
    }

    private void highlightDebugLine(Path file, int line) {
        if (file == null) {
            return;
        }
        debugSteppedFiles.add(JavaProjectConventions.normalize(file));
        long ticket = debugLineTicket.incrementAndGet();
        int editorLine = Math.max(0, line - 1);
        SwingUtilities.invokeLater(() -> {
            clearDebugPositionNow();
            requestOpenFile(file);
            applyDebugLine(file, editorLine, ticket, 0);
        });
    }

    private void applyDebugLine(Path file, int line, long ticket, int attempt) {
        if (ticket != debugLineTicket.get()) {
            return;
        }
        IdeEditorContext editor = getEditor(file, true);
        if (editor == null) {
            if (attempt < 10) {
                background.schedule(() -> SwingUtilities.invokeLater(
                        () -> applyDebugLine(file, line, ticket, attempt + 1)),
                        60, TimeUnit.MILLISECONDS);
            }
            return;
        }
        clearDebugPositionNow();
        editor.setCaretPosition(line, 0);
        editor.setPriorityLineColor(line, DEBUG_LINE_COLOR);
        debugLineContext = editor;
        debugLine = line;
        requestRepaintCodeEditor(file);
    }

    private void clearDebugPositionNow() {
        IdeEditorContext editor = debugLineContext;
        int line = debugLine;
        debugLineContext = null;
        debugLine = -1;
        if (editor != null && line >= 0) {
            editor.removeLineColor(line);
            if (editor.filePath() != null) {
                requestRepaintCodeEditor(editor.filePath());
            }
        }
        CodeEditor library = debugLibraryEditor;
        int libraryLine = debugLibraryLine;
        debugLibraryLine = -1;
        if (library != null && libraryLine >= 0) {
            library.removeLineColor(libraryLine);
            library.repaint();
        }
    }

    private void highlightDebugLibraryLine(String uri, int line) {
        JdtLsService lsp = jdtLs;
        if (uri == null || lsp == null) {
            return;
        }
        long ticket = debugLineTicket.incrementAndGet();
        int editorLine = Math.max(0, line - 1);
        String cached = debugLibrarySources.get(uri);
        if (cached != null) {
            SwingUtilities.invokeLater(() -> showDebugLibraryLine(uri, cached, editorLine, ticket));
            return;
        }
        SwingUtilities.invokeLater(() -> showProgress(NAVIGATION_PROGRESS_ID,
                text("progress.decompiling", "Java: abrindo fonte da dependencia...")));
        background.submit(() -> {
            String source = lsp.classFileContents(uri);
            if (source != null && !source.isBlank()) {
                debugLibrarySources.put(uri, source);
            }
            SwingUtilities.invokeLater(() -> {
                hideProgress(NAVIGATION_PROGRESS_ID);
                if (source == null || source.isBlank()) {
                    setStatusBarText(text("status.decompileFailed",
                            "Java: nao foi possivel obter a fonte da dependencia"));
                    return;
                }
                showDebugLibraryLine(uri, source, editorLine, ticket);
            });
        });
    }

    private void showDebugLibraryLine(String uri, String source, int line, long ticket) {
        if (ticket != debugLineTicket.get() || !isDebugPaused()) {
            return;
        }
        clearDebugPositionNow();
        CodeEditor editor = debugLibraryEditor;
        int[] target = clampPosition(source, line, 0);
        if (editor == null || !uri.equals(debugLibraryUri) || !editor.isShowing()) {
            editor = openClassFileEditor(uri, source, target[0], 0);
            if (editor == null) {
                return;
            }
            debugLibraryEditor = editor;
            debugLibraryUri = uri;
        }
        editor.setCaretPosition(target[0], 0);
        editor.setPriorityLineColor(target[0], DEBUG_LINE_COLOR);
        editor.repaint();
        debugLibraryLine = target[0];
    }

    private void forgetDebugLibrarySources() {
        debugLibrarySources.clear();
        SwingUtilities.invokeLater(() -> {
            clearDebugPositionNow();
            debugLibraryEditor = null;
            debugLibraryUri = null;
        });
    }

    private void repaintDebugBreakpointLines() {
        Set<Path> files = Set.copyOf(debugSteppedFiles);
        debugSteppedFiles.removeAll(files);
        files.forEach(this::requestRepaintCodeEditorBreakpointLine);
    }

    @Override
    public void stop(RunConfigurationData configuration) {
        RunProcessHandle process = runningProcess(configuration);
        Runnable pendingTest = process == null ? pendingTestDebug.getAndSet(null) : null;
        if (pendingTest != null) {
            pendingTest.run();
        }
        if (process != null && process == debugProcessHandle
                && (debugSession != null || debugActive.get())) {
            closeDebugSession();
        } else {
            if (process == null && (debugSession != null || debugActive.get())) {
                closeDebugSession();
            } else if (process != null) {
                process.terminate();
            }
        }
        requestSetRunButtonRunning(hasRunningProcess());
    }

    @Override
    public void onHotReload(RunConfigurationData configuration) {
        runHotReload();
    }

    private void runHotReload() {
        if (!supportsHotReloadForSelection()) {
            setStatusBarText("Java: hot reload nao esta disponivel para este tipo de execucao.");
            return;
        }
        if (settings().getHotReloadMode() == HotReloadMode.NEVER) {
            setStatusBarText("Java: hot reload esta desativado nas preferencias.");
            return;
        }
        requestSetHotReloadButtonEnabled(false);
        background.submit(() -> {
            OutputPanelHandle output = requestOutputPanel("Run", OutputPanelOptions.interactive(null));
            JavaHotReloadService.Result result = hotReloadService().reload(
                    line -> writeOutput(output, line));
            String message = switch (result) {
                case APPLIED -> "Hot reload aplicado.";
                case NO_SESSION -> "Nenhuma sessao de debug ativa.";
                case BUILD_FAILED -> "Hot reload cancelado: o build falhou.";
                case STRUCTURAL_CHANGE -> "A JVM nao aceita esta alteracao estrutural; reinicie a sessao.";
                case FAILED -> "Nao foi possivel aplicar o hot reload.";
            };
            setStatusBarText("Java: " + message);
            requestSetHotReloadButtonEnabled(debugSession != null);
        });
    }

    private synchronized JavaHotReloadService hotReloadService() {
        if (hotReloadService == null) {
            hotReloadService = new JavaHotReloadService(() -> descriptor,
                    this::ensureBuildSystem, () -> debugSession, () -> debugModule);
        }
        return hotReloadService;
    }

    @Override
    public void onBreakpointChanged(BreakpointChangedEvent event) {
        super.onBreakpointChanged(event);
        if (event == null || event.getBreakpointIde() == null) {
            return;
        }
        JavaDebugSession session = debugSession;
        if (session == null) {
            return;
        }
        BreakpointChanges.Update update = BreakpointChanges.resolve(event);
        session.updateBreakpoint(event.getFile(), event.getBreakpointIde().line(), update.enabled(),
                update.spec());
    }

    @Override
    public boolean isConditionalBreakpointEnabled(Path fileOpen) {
        return fileOpen != null && fileOpen.getFileName() != null
                && fileOpen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public CodeEditor createConditionalBreakpointEditor(ConditionalBreakpointContext context) {
        if (context == null || !isConditionalBreakpointEnabled(context.file())) {
            return null;
        }
        String condition = context.currentCondition() == null ? "" : context.currentCondition();
        CodeEditor editor;
        try {
            editor = requestEmbeddedCodeEditor("breakpoint-condition.java", condition,
                    EmbeddedCodeEditorSettings.highlighted());
        } catch (RuntimeException | LinkageError error) {
            log.debug("IDE sem editor embutido para a condicao: {}", error.getMessage());
            return null;
        }
        if (editor == null) {
            return null;
        }
        AtomicReference<ConditionEditorSession> created = new AtomicReference<>();
        ConditionEditorSession session = new ConditionEditorSession(context.file(), context.line(), condition,
                () -> sourceTextOf(context), ConditionLanguageService.of(() -> jdtLs), () -> debugSession,
                () -> {
                    ConditionEditorSession closed = created.get();
                    if (closed != null) {
                        conditionSessions.remove(closed.syntheticPath(), closed);
                        activeConditionSession.compareAndSet(closed, null);
                    }
                });
        created.set(session);
        editor.enableBookmark(false);
        editor.enableBreakpoint(false);
        editor.setFoldingEnabled(false);
        editor.getGutter().enableLineNumber(true);
        editor.addProvider(session.completionProvider());
        editor.addProvider(session.diagnosticsProvider());
        if (editor.getTextArea() != null) {
            editor.getTextArea().setAutoCompleteOnTyping(true);
            editor.getTextArea().setAutoCompleteTypingTrigger(c -> Character.isJavaIdentifierPart(c) || c == '.');
        }
        ConditionEditorSession previous = activeConditionSession.getAndSet(session);
        if (previous != null && previous != session) {
            previous.close();
        }
        conditionSessions.put(session.syntheticPath(), session);
        session.bind(editor);
        return editor;
    }

    @Override
    public void configureConditionalBreakpointEditor(IdeEditorContext editorContext,
                                                     ConditionalBreakpointContext context) {
        if (editorContext == null || context == null || context.file() == null) {
            return;
        }
        if (sessionFor(context) != null) {
            return;
        }
        try {
            TokenizerCodeEditorProvider tokenizer = editors.tokenizerFor(context.file());
            if (tokenizer != null) {
                editorContext.addProvider(tokenizer);
            }
            editorContext.addProvider(new ConditionCompletionProvider(context.file(), context.line(),
                    () -> sourceTextOf(context), () -> debugSession));
            editorContext.setAutoCompleteOnTyping(true);
        } catch (RuntimeException | LinkageError error) {
            log.debug("Editor de condicao sem recursos Java: {}", error.getMessage());
        }
    }

    @Override
    public void configureConditionalBreakpointDialog(ConditionalBreakpointDialogView dialogView) {
        if (dialogView == null) {
            return;
        }
        ConditionalBreakpointContext context = dialogView.getBreakpointContext();
        try {
            dialogView.setHitConditionSupported(true);
            dialogView.setLogMessageSupported(true);
        } catch (LinkageError olderIde) {
            log.debug("IDE sem suporte a hit count/logpoint: {}", olderIde.getMessage());
        }
        if (context == null || context.file() == null) {
            return;
        }
        dialogView.setDescription(text("breakpoint.description",
                "Expressao Java avaliada antes desta linha. O debugger so para quando ela for true."));
        ConditionEditorSession session = sessionFor(context);
        List<String> visible = JavaLocalScope.visibleAt(sourceTextOf(context), context.line()).stream()
                .map(JavaLocalScope.Visible::name).toList();
        if (!visible.isEmpty()) {
            dialogView.addComponent(ConditionalBreakpointHints.chips(
                    text("breakpoint.visible", "Nesta linha:"), visible,
                    session == null ? null : session::insertAtCaret));
        }
        if (session != null) {
            try {
                session.attach(dialogView);
                dialogView.addCloseListener(session::close);
            } catch (LinkageError olderIde) {
                log.debug("IDE sem status de condicao: {}", olderIde.getMessage());
            }
        }
    }

    private ConditionEditorSession sessionFor(ConditionalBreakpointContext context) {
        ConditionEditorSession session = activeConditionSession.get();
        if (session == null || session.isClosed() || context == null || context.file() == null) {
            return null;
        }
        boolean same = session.file().equals(context.file().toAbsolutePath().normalize())
                && session.line() == context.line();
        return same ? session : null;
    }

    private String sourceTextOf(ConditionalBreakpointContext context) {
        IdeEditorContext source = context.sourceEditorContext();
        String text = source == null ? null : source.getText();
        if (text != null) {
            return text;
        }
        try {
            return Files.readString(context.file());
        } catch (Exception error) {
            return "";
        }
    }

    private synchronized JavaRunSupport ensureRunSupport() {
        JavaRunSupport existing = runSupport;
        if (existing != null) {
            return existing;
        }
        JavaRunSupport created = new JavaRunSupport(
                () -> descriptor,
                this::getProjectJdk,
                this::ensureBuildSystem,
                line -> {
                    BuildProgressTracker progress = runBuildProgress.get();
                    if (progress != null) {
                        progress.accept(line);
                    }
                })
                .withChainHost(this::chainHost)
                .withIncrementalBuild(() -> settings().isIncrementalBuild())
                .withModuleProgress((module, index, total) -> {
                    BuildProgressTracker progress = runBuildProgress.get();
                    if (progress != null) {
                        progress.moduleStarted(module, index, total);
                    }
                })
                .withBuildResultListener(result -> publishBuildDiagnostics(result, true));
        runSupport = created;
        return created;
    }

    private RunProcessHandle launchWithBuildProgress(RunConfigurationData configuration,
                                                     Supplier<RunProcessHandle> launcher) {
        if (unloaded) {
            return ProcessLauncher.message("Plugin Java descarregado.");
        }
        Optional<BuildSystem.BuildAction> action =
                JavaRunSupport.buildBeforeRunAction(configuration);
        if (action.isEmpty()) {
            return unloaded ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        }
        JavaModule module = resolveDebugModule(configuration);
        BuildProgressTracker progress = new BuildProgressTracker(
                buildProgressAction(action.get()), descriptor, module,
                update -> updateProgress(RUN_BUILD_PROGRESS_ID,
                        update.label(), update.percent()));
        if (!runBuildProgress.compareAndSet(null, progress)) {
            return unloaded ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        }
        BuildProgressTracker.Update initial = progress.initial();
        showProgress(RUN_BUILD_PROGRESS_ID, initial.label());
        if (initial.percent() >= 0) {
            updateProgress(RUN_BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        boolean slot = false;
        try {
            slot = awaitBuildSlot();
            return unloaded ? ProcessLauncher.message("Plugin Java descarregado.") : launcher.get();
        } finally {
            if (slot) {
                buildRunning.set(false);
            }
            runBuildProgress.compareAndSet(progress, null);
            hideProgress(RUN_BUILD_PROGRESS_ID);
        }
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

    private boolean awaitBuildSlot() {
        if (buildRunning.compareAndSet(false, true)) {
            return true;
        }
        updateProgress(RUN_BUILD_PROGRESS_ID, text("progress.waitingBuild",
                "Aguardando o build em andamento"), -1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUILD_SLOT_WAIT_MS);
        while (System.nanoTime() < deadline) {
            if (buildRunning.compareAndSet(false, true)) {
                return true;
            }
            try {
                Thread.sleep(BUILD_SLOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.info("Build anterior nao terminou em {} ms; seguindo com o Run", BUILD_SLOT_WAIT_MS);
        return false;
    }

    private RunChainHost chainHost() {
        return new RunChainHost() {

            @Override
            public List<RunConfigurationData> configurations() {
                return requestRunConfigurations();
            }

            @Override
            public RunProcessHandle execute(String configurationId, boolean debug) {
                return requestRunConfigurationExecution(configurationId, debug);
            }
        };
    }

    private JavaModule resolveDebugModule(RunConfigurationData configuration) {
        JavaProjectDescriptor current = descriptor;
        if (current == null) {
            return null;
        }
        Object configured = configuration == null || configuration.getProperties() == null
                ? null : configuration.getProperties().get(JavaRunSupport.PROPERTY_MODULE);
        String name = configured == null ? "" : configured.toString().trim();
        return current.modules().stream().filter(module -> module.name().equals(name))
                .findFirst().orElse(current.rootModule());
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
            JavaTestExplorerPanel panel = new JavaTestExplorerPanel(new TestExplorerHost(), background);
            testPanel = panel;
            Icon icon = JavaIcons.test(JavaIcons.SMALL);
            testPanelId = icon == null
                    ? registerToolPanel(DockRegion.BOTTOM, text("panel.tests", "Testes"),
                            ToolIconType.PLAY, panel)
                    : registerToolPanel(DockRegion.BOTTOM, text("panel.tests", "Testes"),
                            icon, panel);
        }
    }

    private void ensureTodoPanel() {
        if (todoPanel != null) {
            return;
        }
        JavaTodoPanel panel = new JavaTodoPanel(new TodoHost());
        todoPanel = panel;
        panel.setProjectRoot(projectRoot);
        Icon icon = JavaIcons.todo(JavaIcons.SMALL);
        todoPanelId = icon == null
                ? registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"),
                        ToolIconType.INFO, panel)
                : registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"), icon, panel);
        rescanTodos();
    }

    private void openTodoPanel() {
        ensureTodoPanel();
        if (todoPanelId != null) {
            requestOpenToolPanel(todoPanelId);
        }
    }

    private void rescanTodos() {
        JavaTodoPanel panel = todoPanel;
        JavaProjectDescriptor current = descriptor;
        if (panel == null || current == null) {
            return;
        }
        panel.beginScan();
        todoScanner.setMarkers(settings().getTodoMarkers());
        Path root = projectRoot;
        background.submit(() -> {
            List<TodoItem> found = todoScanner.scan(current);
            panel.setItems(found, root);
        });
    }

    private void refreshTodosFor(Path filePath, String content) {
        JavaTodoPanel panel = todoPanel;
        if (panel == null || filePath == null || !JavaProjectConventions.isJava(filePath)) {
            return;
        }
        long ticket = todoRefreshTicket.incrementAndGet();
        background.schedule(() -> {
            if (ticket != todoRefreshTicket.get()) {
                return;
            }
            String source = content != null ? content
                    : JavaProjectConventions.readOrEmpty(filePath);
            if (todoScanner.refreshFile(filePath, source)) {
                panel.setItems(todoScanner.items(), projectRoot);
            }
        }, TODO_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private final class TodoHost implements JavaTodoPanel.Host {
        @Override
        public void rescan() {
            rescanTodos();
        }

        @Override
        public void open(TodoItem item) {
            if (item != null) {
                openAt(item.file(), item.line(), item.column());
            }
        }
    }

    private void openProblemsPanel() {
        refreshProblemsPanel();
        requestOpenProblemsPanel();
    }

    private void refreshProblemsPanel() {
        long ticket = problemsRefreshTicket.incrementAndGet();
        background.schedule(() -> {
            if (ticket != problemsRefreshTicket.get()) {
                return;
            }
            List<IdeProblem> build = toIdeProblems(problems.buildProblems());
            List<IdeProblem> live = toIdeProblems(problems.liveProblems());
            SwingUtilities.invokeLater(() -> {
                if (ticket != problemsRefreshTicket.get()) {
                    return;
                }
                publishProblems(BUILD_PROBLEMS_OWNER, build);
                publishProblems(LSP_PROBLEMS_OWNER, live);
                ProblemsActionHandle action = ensureClearBuildAction();
                if (action != null) {
                    action.setEnabled(!build.isEmpty());
                }
            });
        }, PROBLEMS_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private ProblemsActionHandle ensureClearBuildAction() {
        ProblemsActionHandle handle = clearBuildAction;
        if (handle != null) {
            return handle;
        }
        handle = registerProblemsAction(
                BUILD_PROBLEMS_OWNER,
                text("action.clearBuild", "Limpar build"),
                text("action.clearBuild.tip", "Limpar os problemas do ultimo build"),
                JavaIcons.error(JavaIcons.SMALL),
                this::clearBuildProblems);
        clearBuildAction = handle;
        return handle;
    }

    private static List<IdeProblem> toIdeProblems(List<BuildDiagnostic> problems) {
        if (problems == null || problems.isEmpty()) {
            return List.of();
        }
        List<IdeProblem> converted = new ArrayList<>(problems.size());
        for (BuildDiagnostic problem : problems) {
            if (problem != null) {
                converted.add(problem.toIdeProblem());
            }
        }
        return converted;
    }

    private void clearBuildProblems() {
        Set<Path> affected = problems.clearBuild();
        affected.forEach(this::requestRefreshDiagnostics);
        refreshProblemsPanel();
    }

    private void syncWithDisk() {
        Path root = projectRoot;
        JdtLsService lsp = jdtLs;
        if (root == null || lsp == null) {
            setStatusBarText(text("status.noProject", "Java: nenhum projeto aberto"));
            return;
        }
        setStatusBarText(text("status.syncingWithDisk",
                "Java: relendo as mudancas feitas fora do editor..."));
        background.submit(() -> {
            JavaFileChangeRouter router = fileChangeRouter;
            if (router != null) {
                router.acceptDirectory(root);
            }
            lsp.resynchronizeWithDisk();
            javaEditors.keySet().forEach(path -> {
                requestRefreshDiagnostics(path);
                requestRefreshCodeLenses(path);
                requestRefreshInlayHints(path);
                requestRefreshSemanticTokens(path);
            });
        });
    }

    private void reanalyzeDiagnostics() {
        Path root = projectRoot;
        if (root == null) {
            setStatusBarText(text("status.noProject", "Java: nenhum projeto aberto"));
            return;
        }
        if (!diagnosticReanalysisRunning.compareAndSet(false, true)) {
            setStatusBarText(text("status.diagnosticsReanalysisRunning",
                    "Java: a reanalise de diagnosticos ja esta em andamento"));
            return;
        }

        long ticket = lifecycle.incrementAndGet();
        Set<Path> affected = new LinkedHashSet<>(javaEditors.keySet());
        affected.addAll(problems.paths());

        problems.clearAll();
        JdtLsService lsp = jdtLs;
        if (lsp != null) {
            lsp.clearDiagnostics();
        }
        affected.forEach(this::requestRefreshDiagnostics);
        refreshProblemsPanel();
        setStatusBarText(text("status.reanalyzingDiagnostics",
                "Java: limpando diagnosticos e reiniciando a analise..."));

        background.submit(() -> {
            if (lsp != null) {
                lsp.stop();
            }
            if (!current(ticket, root)) {
                diagnosticReanalysisRunning.set(false);
                return;
            }

            problems.clearLive();
            languageServerReadyHandled.set(false);
            SwingUtilities.invokeLater(() -> {
                if (current(ticket, root)) {
                    javaEditors.keySet().forEach(this::requestRefreshDiagnostics);
                    refreshProblemsPanel();
                }
            });
            setupSpring(ticket, root);

            if (settings().getLanguageServerMode().startsServer()) {
                resolveProjectJdk(ticket, root);
            } else {
                finishDiagnosticReanalysis(ticket, root, true);
            }
        });
    }

    private void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
        if (!current(ticket, root)) {
            diagnosticReanalysisRunning.set(false);
            return;
        }
        diagnosticReanalysisRunning.set(false);
        if (successful) {
            javaEditors.keySet().forEach(this::requestRefreshDiagnostics);
            refreshProblemsPanel();
            setStatusBarText(text("status.diagnosticsReanalyzed",
                    "Java: diagnosticos atualizados"));
        }
    }

    private void ensureBuildToolsPanel() {
        if (buildToolsPanel != null) {
            buildToolsPanel.reload();
            return;
        }
        JavaBuildToolsPanel panel = new JavaBuildToolsPanel(new BuildToolsHost(), background);
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

    private final class BuildToolsHost implements JavaBuildToolsPanel.Host {
        @Override
        public BuildToolModel load() {
            pluginGoals.clearCache();
            return BuildToolModel.load(descriptor);
        }

        @Override
        public void sync() {
            pluginRepositoryPath = null;
            syncProject();
        }

        @Override
        public void profilesChanged(java.util.Set<String> profiles) {
            new BuildRunConfigurations(projectRoot).saveActiveProfiles(profiles);
            BuildSystem build = buildSystem;
            if (build != null) {
                build.invalidateClasspathCache();
            }
        }

        @Override
        public java.util.Set<String> activeProfiles() {
            return new BuildRunConfigurations(projectRoot).activeProfiles();
        }

        @Override
        public BuildRunConfigurations.ToolOptions toolOptions() {
            return new BuildRunConfigurations(projectRoot).toolOptions();
        }

        @Override
        public void toolOptionsChanged(BuildRunConfigurations.ToolOptions options) {
            new BuildRunConfigurations(projectRoot).saveToolOptions(options);
        }

        @Override
        public List<BuildRunConfigurations.Entry> runConfigurations() {
            migrateLegacyBuildRunConfigurations();
            return BuildRunConfigurationBridge.entries(requestRunConfigurations(), buildToolIsGradle());
        }

        @Override
        public void saveRunConfiguration(BuildRunConfigurations.Entry entry) {
            if (entry == null || !entry.isValid()) {
                return;
            }
            boolean gradle = buildToolIsGradle();
            String existingId = BuildRunConfigurationBridge.idOf(requestRunConfigurations(), entry.name(), gradle)
                    .orElse(null);
            requestSaveRunConfiguration(BuildRunConfigurationBridge.toRunConfiguration(entry, gradle, existingId));
        }

        @Override
        public void removeRunConfiguration(String name) {
            BuildRunConfigurationBridge.idOf(requestRunConfigurations(), name, buildToolIsGradle())
                    .ifPresent(JavaIdeAdapter.this::requestRemoveRunConfiguration);
        }

        private boolean buildToolIsGradle() {
            JavaProjectDescriptor current = descriptor;
            return current != null && current.isGradle();
        }

        private void migrateLegacyBuildRunConfigurations() {
            Path root = projectRoot;
            if (root == null || !migratedBuildRunConfigurations.add(root.toAbsolutePath().normalize())) {
                return;
            }
            BuildRunConfigurations legacy = new BuildRunConfigurations(root);
            List<RunConfigurationData> current = requestRunConfigurations();
            boolean gradle = buildToolIsGradle();
            for (BuildRunConfigurations.Entry entry : legacy.all()) {
                String existingId = BuildRunConfigurationBridge.idOf(current, entry.name(), gradle).orElse(null);
                RunConfigurationData saved = existingId != null
                        ? RunConfigurationData.builder().id(existingId).build()
                        : requestSaveRunConfiguration(BuildRunConfigurationBridge.toRunConfiguration(entry, gradle, null));
                if (BuildRunConfigurationBridge.saved(saved)) {
                    legacy.remove(entry.name());
                } else {
                    migratedBuildRunConfigurations.remove(root.toAbsolutePath().normalize());
                }
            }
        }

        @Override
        public boolean supportsDebug() {
            return true;
        }

        @Override
        public boolean showPrompt(BuildPromptPanel prompt, String title) {
            showPopup(PlatformPopupBuilder.builder()
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
                    : pluginGoals.goalsOf(coordinate.groupId(), coordinate.artifactId(),
                            coordinate.version());
        }

        @Override
        public void cancel() {
            BuildSystem build = buildSystem;
            if (build != null) {
                build.cancel();
            }
        }

        private void reject(String message) {
            JavaBuildToolsPanel panel = buildToolsPanel;
            if (panel != null) {
                panel.warning(message);
            }
        }

        private void runToolGoals(JavaModule module, List<String> goals, boolean debug) {
            BuildSystem build = ensureBuildSystem();
            if (build == null || goals == null || goals.isEmpty()) {
                reject(text("status.buildToolUnavailable", "Nenhum build tool disponivel"));
                return;
            }
            if (build.isRunning()) {
                reject(text("status.buildRunning", "Ja existe um build em andamento"));
                return;
            }
            JavaProjectDescriptor current = descriptor;
            boolean gradle = current != null && current.isGradle();
            BuildRunConfigurations.ToolOptions toolOptions =
                    new BuildRunConfigurations(projectRoot).toolOptions();
            List<String> arguments = new ArrayList<>();
            if (toolOptions.skipTests()) {
                arguments.addAll(gradle ? List.of("-x", "test") : List.of("-DskipTests"));
            }
            Map<String, String> environment = new LinkedHashMap<>();
            BuildToolDebugListener listener = null;
            if (debug) {
                try {
                    listener = openBuildDebugListener(module, build::cancel);
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
            OutputPanelHandle output = requestOutputPanel("Build", OutputPanelOptions.interactive(null));
            if (output != null) {
                output.clear();
                output.show();
            }
            BuildToolDebugListener debugListener = listener;
            background.submit(() -> {
                BuildResult result;
                try {
                    result = build.executeToolCommand(module, goals, options,
                            line -> writeOutput(output, line));
                } catch (RuntimeException error) {
                    writeOutput(output, rootMessage(error));
                    result = BuildResult.failed(build.name(), rootMessage(error));
                } finally {
                    if (debugListener != null) {
                        debugListener.close();
                    }
                }
                publishBuildDiagnostics(result, true);
                if (!result.summary().isEmpty()) {
                    writeOutput(output, result.summary());
                }
                JavaBuildToolsPanel panel = buildToolsPanel;
                if (panel != null) {
                    panel.finished(result.summary(), result.successful());
                }
            });
        }
    }

    private final class TestExplorerHost implements JavaTestExplorerPanel.Host {

        @Override
        public List<JavaTest> discover() {
            return JUnitTestDiscovery.discover(descriptor);
        }

        @Override
        public void discoverSemantic(List<JavaTest> provisional,
                                     java.util.function.Consumer<List<JavaTest>> onFinished) {
            JavaProjectDescriptor current = descriptor;
            JdtLsService lsp = jdtLs;
            if (current == null || lsp == null || !lsp.isTestRunnerAvailable()) {
                onFinished.accept(provisional);
                return;
            }
            background.submit(() -> onFinished.accept(
                    JavaSemanticTestDiscovery.enrich(current, lsp, provisional)));
        }

        @Override
        public void run(List<JavaTest> tests,
                        java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
            JavaProjectDescriptor current = descriptor;
            BuildSystem build = ensureBuildSystem();
            if (current == null || build == null) {
                onFinished.accept(null);
                return;
            }
            OutputPanelHandle panel = requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
            if (panel != null) {
                panel.clear();
                panel.show();
            }
            requestShowRunOutput();

            background.submit(() -> {
                JavaTestRunner runner = newTestRunner(current, build);
                JavaTestRunner.TestRun run = runner.run(tests, current.rootModule(),
                        line -> writeOutput(panel, line));
                publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
                setStatusBarText("Java: " + run.summary());
                onFinished.accept(run);
            });
        }

        @Override
        public void clearCoverage() {
            JavaIdeAdapter.this.clearCoverage();
            Path root = projectRoot;
            if (root != null) {
                requestRefreshCodeLenses(root);
            }
        }

        @Override
        public boolean supportsCoverage() {
            JavaProjectDescriptor current = descriptor;
            return current != null && (current.isMaven() || current.isGradle());
        }

        @Override
        public void runWithCoverage(List<JavaTest> tests,
                                    java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
            JavaProjectDescriptor current = descriptor;
            BuildSystem build = ensureBuildSystem();
            CoverageProvisioner provisioner = coverageProvisioner();
            if (current == null || build == null || provisioner == null) {
                onFinished.accept(null);
                return;
            }
            if (!current.isMaven() && !current.isGradle()) {
                setStatusBarText(text("coverage.unsupportedProject",
                        "Java: cobertura requer Maven ou Gradle"));
                onFinished.accept(null);
                return;
            }
            OutputPanelHandle panel = requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
            if (panel != null) {
                panel.clear();
                panel.show();
            }
            requestShowRunOutput();

            background.submit(() -> {
                Path agent = provisioner.ensureAgent().orElse(null);
                if (agent == null) {
                    setStatusBarText(text("coverage.agentMissing",
                            "Java: nao foi possivel preparar o agente de cobertura"));
                    onFinished.accept(null);
                    return;
                }
                JavaTestRunner runner = newTestRunner(current, build);
                JavaTestRunner.CoverageRun coverageRun = runner.runWithCoverage(
                        tests, moduleOf(tests, current), agent, line -> writeOutput(panel, line));
                JavaTestRunner.TestRun run = coverageRun.testRun();
                publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
                setStatusBarText("Java: " + run.summary());
                onFinished.accept(run);
                readCoverage(coverageRun.execFile(), current);
            });
        }

        @Override
        public void debug(List<JavaTest> tests,
                          java.util.function.Consumer<JavaTestRunner.TestRun> onFinished) {
            JavaProjectDescriptor current = descriptor;
            BuildSystem build = ensureBuildSystem();
            if (current == null || build == null) {
                onFinished.accept(null);
                return;
            }
            OutputPanelHandle panel = requestOutputPanel("Tests", OutputPanelOptions.interactive(null));
            if (panel != null) {
                panel.clear();
                panel.show();
            }
            requestShowRunOutput();
            Runnable previous = pendingTestDebug.getAndSet(null);
            if (previous != null) {
                previous.run();
            }
            JavaModule targetModule = moduleOf(tests, current);
            JavaModule sourceModule = tests == null || tests.isEmpty() ? null
                    : mostSpecificModule(current.modules(), tests.getFirst().file());
            JavaTestRunner runner = newTestRunner(current, build);
            BuildToolDebugListener listener;
            try {
                listener = openBuildDebugListener(
                        sourceModule == null ? targetModule : sourceModule, runner::cancel,
                        () -> hideProgress(TEST_DEBUG_PROGRESS_ID));
            } catch (IOException error) {
                String message = text("error.buildDebugListen",
                        "Nao foi possivel abrir a porta de debug:") + " " + rootMessage(error);
                writeOutput(panel, message);
                setStatusBarText("Java Debug: " + message);
                onFinished.accept(null);
                return;
            }
            Runnable abort = () -> {
                listener.close();
                runner.cancel();
            };
            pendingTestDebug.set(abort);
            showProgress(TEST_DEBUG_PROGRESS_ID, text("progress.testDebugBuild",
                    "Compilando testes para depurar"), true, abort);
            requestSetRunButtonLoading(true);
            warmUpDebugAdapter();
            background.submit(() -> {
                try {
                    JavaTestRunner.TestRun run = runner.debug(tests, targetModule,
                            listener.listenPort(), line -> writeOutput(panel, line));
                    if (runner.isCancelled()) {
                        setStatusBarText(text("status.testDebugStopped",
                                "Java: depuracao de teste interrompida"));
                    } else {
                        publishTestDiagnostics(JavaTestProblems.withTestFailures(run, tests));
                        setStatusBarText("Java: " + run.summary());
                    }
                    onFinished.accept(run);
                } finally {
                    listener.close();
                    pendingTestDebug.compareAndSet(abort, null);
                    hideProgress(TEST_DEBUG_PROGRESS_ID);
                    requestSetRunButtonRunning(hasRunningProcess() || debugActive.get());
                }
            });
        }

        private JavaModule moduleOf(List<JavaTest> tests, JavaProjectDescriptor current) {
            if (tests == null || tests.isEmpty()) {
                return current.rootModule();
            }
            return current.modules().stream()
                    .filter(module -> module.contains(tests.getFirst().file()))
                    .findFirst().orElse(current.rootModule());
        }

        @Override
        public void cancel() {
            JavaTestRunner running = activeTestRunner.get();
            if (running != null) {
                running.cancel();
            }
            BuildSystem build = buildSystem;
            if (build != null) {
                build.cancel();
            }
        }

        @Override
        public void openFile(Path file, int line) {
            if (file == null) {
                return;
            }
            openAt(file, Math.max(0, line - 1), 0);
        }
    }

    private void setupSpring(long ticket, Path root) {
        setupSpring(ticket, root, null);
    }

    private void setupSpring(long ticket, Path root, JavaProjectSources sources) {
        JavaProjectDescriptor current = descriptor;
        if (current == null || !current.spring() || !settings().isSpringSupport()) {
            return;
        }
        springIndex.rebuild(current, sources).thenAccept(snapshot -> {
            if (!current(ticket, root)) {
                return;
            }
            springMetadata = springMetadata.withProjectProperties(projectConfigProperties());
            snapshot.beans().stream().map(SpringBean::file).distinct()
                    .forEach(this::requestRefreshCodeLenses);
            javaEditors.keySet().forEach(this::requestRefreshDiagnostics);
            SwingUtilities.invokeLater(() -> {
                if (!current(ticket, root)) {
                    return;
                }
                SpringExplorerPanel panel = springPanel;
                if (panel != null) {
                    panel.reload();
                }
                setStatusBarText("Spring: " + snapshot.beans().size() + " bean(s), "
                        + snapshot.endpoints().size() + " endpoint(s)");
            });
        });
        if (!settings().getLanguageServerMode().startsServer()) {
            loadSpringMetadata(ticket, root);
        }
        loadSpringConfigIndex(ticket, root);
        SwingUtilities.invokeLater(() -> {
            if (current(ticket, root)) {
                setupSpringPanel();
            }
        });
    }

    private void loadSpringMetadata(long ticket, Path root) {
        background.submit(() -> {
            BuildSystem build = ensureBuildSystem();
            JavaProjectDescriptor current = descriptor;
            if (build == null || current == null) {
                return;
            }
            List<Path> classpath = new ArrayList<>();
            for (JavaModule module : springConfigModules(current)) {
                runtimeClasspathOf(build, module).ifPresent(entries -> {
                    for (String entry : entries.split(java.io.File.pathSeparator)) {
                        if (!entry.isBlank()) {
                            classpath.add(Path.of(entry));
                        }
                    }
                });
                if (!current(ticket, root)) {
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
            springMetadata = metadata.withProjectProperties(projectConfigProperties());
            log.info("Catalogo de configuracao do Spring carregado de {} entrada(s): {} chave(s)",
                    classpath.size(), metadata.size());
        });
    }

    private List<SpringConfigProperty> projectConfigProperties() {
        List<SpringConfigProperty> properties = new ArrayList<>();
        for (SpringPropertyUsage usage : springIndex.snapshot().propertyUsages()) {
            if (usage.isPrefix() && !usage.key().isBlank()) {
                properties.add(SpringConfigProperty.of(usage.key(), "",
                        SpringBean.simpleNameOf(usage.ownerType())));
            }
        }
        return properties;
    }

    private java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module) {
        JdtLsService lsp = jdtLs;
        if (lsp != null && lsp.isReady()) {
            java.util.Optional<String> fromServer = lsp.runtimeClasspath(module.root());
            if (fromServer.isPresent() && !fromServer.get().isBlank()) {
                if (!ClasspathValidation.hasMissingJar(fromServer.get())) {
                    return fromServer;
                }
                requestJdtLsProjectConfigurationRefresh(lsp);
            }
        }
        return build.resolveRuntimeClasspath(module);
    }

    private void loadSpringConfigIndex(long ticket, Path root) {
        background.submit(() -> {
            JavaProjectDescriptor current = descriptor;
            if (current == null || !current(ticket, root)) {
                return;
            }
            List<Path> resourceRoots = new ArrayList<>();
            for (JavaModule module : springConfigModules(current)) {
                resourceRoots.add(module.root().resolve("src").resolve("main").resolve("resources"));
                resourceRoots.add(module.root().resolve("src").resolve("test").resolve("resources"));
            }
            SpringConfigIndex index = SpringConfigIndex.scan(resourceRoots);
            if (!current(ticket, root)) {
                return;
            }
            springConfigIndex = index;
            log.info("Indice de configuracao do Spring: {} chave(s) em {} raiz(es)",
                    index.entries().size(), resourceRoots.size());
        });
    }

    private List<JavaModule> springConfigModules(JavaProjectDescriptor current) {
        List<JavaModule> withConfig = current.buildableModules().stream()
                .filter(JavaIdeAdapter::hasSpringConfigFile)
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

    private void setupSpringPanel() {
        if (springPanelId != null) {
            return;
        }
        SpringExplorerPanel panel = new SpringExplorerPanel(new SpringExplorerHost());
        springPanel = panel;
        springPanelId = registerToolPanel(DockRegion.BOTTOM, "Spring",
                JavaIcons.springExplorer(JavaIcons.SMALL), panel);
    }

    private final class SpringExplorerHost implements SpringExplorerPanel.Host {

        @Override
        public SpringIndexSnapshot snapshot() {
            return springIndex.snapshot();
        }

        @Override
        public void openFile(Path file, int line) {
            if (file == null) {
                return;
            }
            openAt(file, Math.max(0, line - 1), 0);
        }

        @Override
        public void openInBrowser(String url) {
            openWebBrowser(url);
        }

        @Override
        public String applicationBaseUrl() {
            return springBaseUrl;
        }

        @Override
        public void loadLive(java.util.function.Consumer<SpringExplorerPanel.LiveData> onResult) {
            background.submit(() -> {
                SpringExplorerPanel.LiveData data = SpringExplorerPanel.LiveData.unavailable();
                try {
                    String baseUrl = springBaseUrl;
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
            JavaProjectDescriptor current = descriptor;
            springIndex.rebuild(current).thenRun(() -> SwingUtilities.invokeLater(onDone));
        }
    }

    private void adoptRuntimeBeans(List<SpringActuatorClient.LiveBean> liveBeans) {
        if (!settings().isSpringRuntimeBeans()) {
            return;
        }
        List<SpringBean> runtime = SpringRuntimeBeans.from(liveBeans, springIndex.snapshot());
        springIndex.applyRuntimeBeans(runtime);
        log.info("Beans de runtime adotados do Actuator: {}", runtime.size());
    }

    public void openSpringExplorer() {
        JavaProjectDescriptor current = descriptor;
        if (current == null || !current.spring()) {
            setStatusBarText(text("status.noSpring", "Java: este projeto nao usa Spring"));
            return;
        }
        setupSpringPanel();
        if (springPanelId != null) {
            requestOpenToolPanel(springPanelId);
        }
    }

    public void runBuild(BuildSystem.BuildAction action, String title) {
        runBuild(action, title, null);
    }

    private void runBuild(BuildSystem.BuildAction action, String title, JavaModule requestedModule) {
        Path root = projectRoot;
        BuildSystem build = ensureBuildSystem();
        if (root == null || build == null) {
            return;
        }
        if (build.isRunning() || !buildRunning.compareAndSet(false, true)) {
            setStatusBarText(text("status.buildRunning", "Java: ja existe um build em andamento"));
            return;
        }

        long ticket = lifecycle.get();
        JavaModule module = requestedModule != null && requestedModule.isAggregator()
                ? null : requestedModule;
        BuildProgressTracker progress = new BuildProgressTracker(
                buildProgressAction(action), descriptor, module,
                update -> updateProgress(BUILD_PROGRESS_ID, update.label(), update.percent()));
        BuildProgressTracker.Update initial = progress.initial();
        showProgress(BUILD_PROGRESS_ID, initial.label());
        if (initial.percent() >= 0) {
            updateProgress(BUILD_PROGRESS_ID, initial.label(), initial.percent());
        }
        background.submit(() -> {
            try {
                BuildResult result = executeBuild(action, build, module, progress);
                if (!current(ticket, root)) {
                    return;
                }
                publishBuildDiagnostics(result, true);
                setStatusBarText(result.summary().isEmpty() ? "" : "Java: " + result.summary());
            } finally {
                BuildProgressTracker.Update completed = progress.completed();
                updateProgress(BUILD_PROGRESS_ID, completed.label(), completed.percent());
                hideProgress(BUILD_PROGRESS_ID);
                buildRunning.set(false);
            }
        });
    }

    private BuildResult executeBuild(BuildSystem.BuildAction action, BuildSystem build,
                                     JavaModule module, Consumer<String> output) {
        JavaPluginSettings preferences = settings();
        if (action == BuildSystem.BuildAction.REBUILD || action == BuildSystem.BuildAction.CLEAN) {
            discardIncrementalState();
        }
        JavaProjectDescriptor current = descriptor;
        if ((action == BuildSystem.BuildAction.COMPILE || action == BuildSystem.BuildAction.TEST_COMPILE)
                && preferences.isIncrementalBuild() && current != null) {
            JavaModule target = module == null ? current.rootModule() : module;
            IncrementalJavaBuilder builder =
                    new IncrementalJavaBuilder(current, () -> build, this::getProjectJdk);
            if (target != null && builder.isApplicable(target)) {
                return builder.build(target, action == BuildSystem.BuildAction.TEST_COMPILE, output);
            }
        }
        BuildRequest request = BuildRequest.of(action, module)
                .withOffline(preferences.isBuildOffline());
        return build.execute(request, output);
    }

    private void discardIncrementalState() {
        if (descriptor == null || descriptor.root() == null) {
            return;
        }
        ModuleBuildState.discard(IncrementalJavaBuilder.stateDirectory(descriptor.root()));
    }

    private String buildProgressAction(BuildSystem.BuildAction action) {
        return switch (action) {
            case COMPILE -> text("progress.compiling", "Compilando");
            case TEST_COMPILE -> text("progress.testCompiling", "Compilando testes");
            case REBUILD -> text("progress.rebuilding", "Recompilando");
            case CLEAN -> text("progress.cleaning", "Limpando");
            case TEST -> text("progress.testing", "Executando testes");
            case PACKAGE -> text("progress.packaging", "Empacotando");
            case INSTALL -> text("progress.installing", "Instalando");
        };
    }

    private void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure) {
        publishBuildDiagnostics(result, revealOnFailure, BuildProblemsCoordinator.Channel.BUILD);
    }

    private void publishTestDiagnostics(BuildResult result) {
        publishBuildDiagnostics(result, true, BuildProblemsCoordinator.Channel.TEST);
    }

    private void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure,
                                         BuildProblemsCoordinator.Channel channel) {
        if (result == null) {
            return;
        }
        List<BuildDiagnostic> reported = new ArrayList<>(result.diagnostics());
        reported.addAll(StaticAnalysisReportParser.discover(descriptor));
        List<BuildDiagnostic> published = !reported.isEmpty() || result.successful()
                ? List.copyOf(reported)
                : List.of(new BuildDiagnostic(null, 0, 0, DiagnosticSeverity.ERROR,
                        result.summary(), "build"));
        Set<Path> affected = problems.replace(channel, published);
        affected.forEach(this::requestRefreshDiagnostics);

        refreshProblemsPanel();
        if (revealOnFailure && !result.successful()) {
            SwingUtilities.invokeLater(this::requestOpenProblemsPanel);
        }
    }

    private void writeOutput(OutputPanelHandle panel, String line) {
        if (panel == null) {
            return;
        }
        try {
            panel.getOutputStream().write((line + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
            panel.getOutputStream().flush();
        } catch (Exception e) {
            log.debug("Falha ao escrever no painel de saida: {}", e.getMessage());
        }
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
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        long ticket = lifecycle.get();
        long generation = syncGeneration.incrementAndGet();
        buildToolsSyncPending.set(false);
        String label = text("status.syncing", "Java: sincronizando o projeto...");
        setStatusBarText(label);
        showProgress(SYNC_PROGRESS_ID, label);
        JavaBuildToolsPanel panel = buildToolsPanel;
        if (panel != null) {
            panel.setSyncing(true);
        }
        background.submit(() -> {
            boolean waiting = false;
            try {
                BuildSystem build = buildSystem;
                if (build != null) {
                    build.invalidateClasspathCache();
                }
                JavaProjectDescriptor reloaded = timed("describe(syncProject)",
                        () -> JavaProjectConventions.describe(root));
                if (!current(ticket, root)) {
                    return;
                }
                if (reloaded != null) {
                    descriptor = reloaded;
                }
                JdtLsService lsp = jdtLs;
                applyLombokAgent(lsp, descriptor);
                boolean agentChanged = lsp != null && lsp.needsRestartForLombokAgent();
                if (agentChanged || lsp == null) {
                    clearCaches();
                    return;
                }
                SyncWork work = new SyncWork();
                syncWork.set(work);
                if (!lsp.updateProjectConfiguration(root)) {
                    syncWork.compareAndSet(work, null);
                    clearCaches();
                    return;
                }
                waiting = true;
                work.completion().whenComplete((ignored, error) -> {
                    syncWork.compareAndSet(work, null);
                    finishSync(generation, ticket, root, true);
                });
            } finally {
                if (!waiting) {
                    finishSync(generation, ticket, root, false);
                }
            }
        });
    }

    private void finishSync(long generation, long ticket, Path root, boolean synced) {
        if (syncGeneration.get() != generation) {
            return;
        }
        hideProgress(SYNC_PROGRESS_ID);
        JavaBuildToolsPanel panel = buildToolsPanel;
        if (panel != null) {
            panel.setSyncing(false);
        }
        if (!synced || !current(ticket, root)) {
            return;
        }
        requestProjectTreeViewRefresh();
        SwingUtilities.invokeLater(() -> {
            if (!current(ticket, root)) {
                return;
            }
            refreshBuildToolsPanel();
            setStatusBarText(text("status.synced", "Java: projeto sincronizado"));
        });
    }

    private static final class SyncWork {
        private final CompletableFuture<Boolean> started = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        void observe(boolean active) {
            if (active) {
                started.complete(true);
            } else if (started.isDone()) {
                finished.complete(null);
            }
        }

        CompletableFuture<Void> completion() {
            return started.completeOnTimeout(false, SYNC_WORK_START_GRACE_MS, TimeUnit.MILLISECONDS)
                    .thenCompose(active -> active
                            ? finished.completeOnTimeout(null, SYNC_WORK_MAX_MS, TimeUnit.MILLISECONDS)
                            : CompletableFuture.completedFuture(null));
        }
    }

    private void refreshBuildToolsPanel() {
        JavaBuildToolsPanel panel = buildToolsPanel;
        if (panel != null) {
            panel.reload();
        }
    }

    private void onBuildFileChanged(Path filePath) {
        if (!buildToolsSyncPending.compareAndSet(false, true)) {
            return;
        }
        JavaBuildToolsPanel panel = buildToolsPanel;
        if (panel != null) {
            panel.setSyncPending(true);
        }
        setStatusBarText(text("status.syncPending",
                "Java: o arquivo de build mudou - sincronize o projeto"));
        Path root = projectRoot;
        JdtLsService lsp = jdtLs;
        if (root != null && lsp != null) {
            background.submit(() -> restartWhenLombokAgentChanged(lsp,
                    JavaProjectConventions.describe(root)));
        }
        createNotification(NotificationContext.builder()
                .title(text("notification.syncTitle", "Build alterado"))
                .message(text("notification.syncMessage",
                        "O arquivo de build mudou. Sincronizar o projeto agora?"))
                .icon(JavaIcons.sync(JavaIcons.SMALL))
                .action(this::syncProject)
                .build());
    }

    public void openProjectStructure() {
        JavaProjectStructurePanel panel = structurePanel;
        if (panel == null) {
            panel = new JavaProjectStructurePanel(new ProjectStructureHost());
            structurePanel = panel;
        } else {
            panel.reload();
        }
        openCenterTab(STRUCTURE_TAB_ID, text("tab.projectStructure", "Estrutura do projeto"),
                panel, true);
        switchToCenterTab(STRUCTURE_TAB_ID);
    }

    private final class ProjectStructureHost implements JavaProjectStructurePanel.Host {

        @Override
        public List<JavaModule> modules() {
            JavaProjectDescriptor current = descriptor;
            return current == null ? List.of() : current.modules();
        }

        @Override
        public List<JdkInstallation> installations() {
            return ensureJdkService().available();
        }

        @Override
        public JdkInstallation projectJdk() {
            return projectJdk;
        }

        @Override
        public Integer languageLevel() {
            JavaProjectDescriptor current = descriptor;
            if (current == null) {
                return null;
            }
            JavaModule root = current.rootModule();
            return root == null ? current.jdkMajor().orElse(null)
                    : LanguageLevelEditor.read(root.root())
                            .orElseGet(() -> current.jdkMajor().orElse(null));
        }

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public JComponent librariesView() {
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
        public List<JavaProjectStructurePanel.FolderRole> folders() {
            JavaProjectDescriptor current = descriptor;
            Path root = projectRoot;
            if (current == null || root == null) {
                return List.of();
            }
            ProjectLayout layout = ProjectLayout.of(root);
            Map<Path, ProjectLayout.Role> byFolder = new LinkedHashMap<>();
            for (JavaModule module : current.modules()) {
                module.sourceRoots().forEach(folder ->
                        byFolder.putIfAbsent(folder, ProjectLayout.Role.SOURCE));
                module.testRoots().forEach(folder ->
                        byFolder.putIfAbsent(folder, ProjectLayout.Role.TEST));
            }
            for (ProjectLayout.Role role : ProjectLayout.Role.values()) {
                layout.foldersWith(role).forEach(folder -> byFolder.put(folder, role));
            }
            return byFolder.entrySet().stream()
                    .map(entry -> new JavaProjectStructurePanel.FolderRole(
                            entry.getKey(), entry.getValue()))
                    .toList();
        }

        @Override
        public void apply(JdkInstallation jdk, Integer level,
                          JavaProjectStructurePanel.ProjectLayoutChange change) {
            Path root = projectRoot;
            if (root == null) {
                return;
            }
            background.submit(() -> {
                if (jdk != null) {
                    ensureJdkService().selectHomeForProject(root, jdk.home());
                    projectJdk = jdk;
                }
                if (level != null) {
                    JavaProjectDescriptor current = descriptor;
                    JavaModule rootModule = current == null ? null : current.rootModule();
                    if (rootModule != null) {
                        LanguageLevelEditor.write(rootModule.root(), level);
                    }
                }
                if (change != null) {
                    ProjectLayout layout = ProjectLayout.of(root);
                    layout.clearRoles();
                    change.folders().forEach(folder ->
                            layout.setRole(folder.folder(), folder.role()));
                    layout.save();
                }
                syncProject();
                JavaProjectStructurePanel panel = structurePanel;
                if (panel != null) {
                    panel.reload();
                }
            });
        }
    }

    public void openJdkManager() {
        JdkManagerPanel panel = jdkManagerPanel;
        if (panel == null) {
            panel = new JdkManagerPanel(new JdkManagerHost(), this::createModernDialogBuilder);
            jdkManagerPanel = panel;
        } else {
            panel.reload();
        }
        openCenterTab(JDK_TAB_ID, text("tab.jdkManager", "JDKs"), panel, true);
        switchToCenterTab(JDK_TAB_ID);
    }

    private final class JdkManagerHost implements JdkManagerPanel.Host {

        @Override
        public List<JdkInstallation> installations() {
            return ensureJdkService().available();
        }

        @Override
        public JdkInstallation projectJdk() {
            return projectJdk;
        }

        @Override
        public void refreshInstallations() {
            ensureJdkService().refresh();
        }

        @Override
        public void download(int major, java.util.function.Consumer<String> onDone) {
            background.submit(() -> {
                try {
                    JdkInstallation installed = ensureJdkService().install(major, progressListener());
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
            background.submit(() -> {
                try {
                    Optional<JdkInstallation> inspected = ensureJdkService().inspectExisting(home);
                    if (inspected.isEmpty()) {
                        onDone.accept(text("status.notAJdk",
                                "O diretorio selecionado nao contem uma JDK utilizavel:") + " " + home);
                        return;
                    }
                    JdkInstallation installation = inspected.get();
                    ensureJdkService().refresh();
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
            JavaProjectDescriptor current = descriptor;
            if (current == null) {
                return settings().getDefaultJdkVersion();
            }
            return current.jdkMajor().orElseGet(() -> settings().getDefaultJdkVersion());
        }

        private void adoptIfRequired(JdkInstallation installed) {
            Integer required = requiredMajor();
            if (installed == null || projectRoot == null) {
                return;
            }
            if (projectJdk == null || (required != null && required == installed.major())) {
                useForProject(installed);
            }
        }

        @Override
        public void useForProject(JdkInstallation installation) {
            Path root = projectRoot;
            if (root == null || installation == null) {
                return;
            }
            long ticket = lifecycle.get();
            background.submit(() -> {
                try {
                    ensureJdkService().selectHomeForProject(root, installation.home());
                    JavaProjectDescriptor described = timed("describe(useForProject)",
                            () -> JavaProjectConventions.describe(root));
                    if (!current(ticket, root)) {
                        return;
                    }
                    projectJdk = installation;
                    descriptor = described;
                    if (described != null) {
                        lexicalIndex.rebuild(described);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (!current(ticket, root)) {
                            return;
                        }
                        refreshRunButtonsForCurrentFile();
                        if (buildToolsPanel != null) {
                            buildToolsPanel.reload();
                        }
                        setStatusBarText("Java: " + installation.displayName());
                    });
                } catch (Exception e) {
                    log.warn("Falha ao selecionar JDK para {}", root, e);
                    SwingUtilities.invokeLater(() -> {
                        if (current(ticket, root)) {
                            setStatusBarText("Java: " + rootMessage(e));
                        }
                    });
                }
            });
        }

        @Override
        public boolean remove(JdkInstallation installation) {
            boolean removed = ensureJdkService().remove(installation);
            if (removed && installation.equals(projectJdk)) {
                projectJdk = null;
                Path root = projectRoot;
                if (root != null) {
                    resolveProjectJdk(lifecycle.incrementAndGet(), root);
                }
            }
            return removed;
        }
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
        springBaseUrl = current.getSpringBaseUrl();
        applyDependencySearchSettings(current);
        Path root = projectRoot;
        if (root != null) {
            requestRefreshCodeLenses(root);
        }
        SwingUtilities.invokeLater(this::refreshCoverageGutters);
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
        if (mode == appliedBuildMode) {
            return false;
        }
        appliedBuildMode = mode;
        JdtLsService lsp = jdtLs;
        if (root == null || lsp == null) {
            return false;
        }
        background.submit(() -> {
            lsp.stop();
            hideProgress(LSP_PROGRESS_ID);
            resolveProjectJdk(lifecycle.incrementAndGet(), root);
        });
        return true;
    }

    private void applyLombokSettingChange(Path root) {
        JdtLsService lsp = jdtLs;
        if (root == null || lsp == null) {
            return;
        }
        background.submit(() -> restartWhenLombokAgentChanged(lsp, descriptor));
    }

    private void restartWhenLombokAgentChanged(JdtLsService lsp, JavaProjectDescriptor current) {
        if (current == null || lsp == null) {
            return;
        }
        applyLombokAgent(lsp, current);
        if (!lsp.needsRestartForLombokAgent()) {
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
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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
