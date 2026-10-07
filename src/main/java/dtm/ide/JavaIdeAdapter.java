package dtm.ide;

import dtm.ide.lsp.api.ClassFileSupport;
import dtm.ide.lsp.api.DebugAdapterSupport;
import dtm.ide.lsp.api.ImportCandidateSupport;
import dtm.ide.lsp.api.ImportLookup;
import dtm.ide.lsp.api.JavaAgentSupport;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.ProjectModelSupport;
import dtm.ide.lsp.api.SourceGenerationSupport;
import dtm.ide.lsp.api.TestDiscoverySupport;
import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.lsp.api.JavaCodeLens;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.lsp.api.PrepareRenameResult;
import dtm.ide.lsp.api.ResolvedCodeAction;
import dtm.ide.lsp.api.TypeSymbol;
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
import dtm.ide.adapter.AdapterFailures;
import dtm.ide.adapter.AdapterHost;
import dtm.ide.adapter.BuildToolsSupport;
import dtm.ide.adapter.CompletionEngine;
import dtm.ide.adapter.DiagnosticsEngine;
import dtm.ide.adapter.CoverageSupport;
import dtm.ide.adapter.GhostTextSupport;
import dtm.ide.adapter.JdkManagerSupport;
import dtm.ide.adapter.ProjectStructureSupport;
import dtm.ide.adapter.SpringSupport;
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
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeWordCaretContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.ide.api.project.editor.EditorShortcutScope;
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
import dtm.ide.swingdesigner.SwingDesignerEnvironment;
import dtm.ide.swingdesigner.SwingDesignerSupport;
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
import dtm.ide.lsp.LanguageServers;
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
import dtm.ide.project.PomDiagnostics;
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
import dtm.ide.api.project.editor.IdeSelectionRangeContext;
import dtm.ide.api.project.editor.SelectionRangeContext;
import dtm.ide.editor.PostfixCompletionProvider;
import dtm.ide.editor.TextOffsets;
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
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRange;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dtm.ide.adapter.AdapterText.text;
import static dtm.ide.adapter.DiagnosticsSupport.unusedFieldDiagnostics;
import static dtm.ide.adapter.DiagnosticsSupport.unusedMethodDiagnostics;
import static dtm.ide.adapter.CompletionSupport.completionTriggerCharacter;
import static dtm.ide.adapter.CompletionSupport.filterCompletionSuggestions;
import static dtm.ide.adapter.CompletionSupport.markUnusedMethods;
import static dtm.ide.adapter.CompletionSupport.mergeCompletionSuggestions;

@Slf4j
@Singleton
@PluginReference(id = "java-ide-adapter")
public class JavaIdeAdapter extends IdeAdapter {



    private static final long SLOW_OPERATION_THRESHOLD_MS = 100;
    private static final long RUN_BUTTONS_REFRESH_DELAY_MS = 300;
    private static final long SELECTION_RANGE_TIMEOUT_MS = 1_000;
    private static final long DELETE_REFERENCES_TIMEOUT_MS = 30_000;
    private static final int DELETE_SEARCH_PARALLELISM = 4;
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
                    ? registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"),
                            ToolIconType.INFO, panel)
                    : registerToolPanel(DockRegion.BOTTOM, text("panel.todo", "TODO"), icon, panel);
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
            JavaIdeAdapter.this.refreshRunButtonsForCurrentFile();
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
            return registerToolPanel(DockRegion.BOTTOM, title, icon, panel);
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
            return JavaIdeAdapter.this.openBuildDebugListener(module, cancelProcess);
        }

        @Override
        public OutputPanelHandle requestOutputPanel(String title, OutputPanelOptions options) {
            return JavaIdeAdapter.this.requestOutputPanel(title, options);
        }

        @Override
        public void writeOutput(OutputPanelHandle panel, String line) {
            JavaIdeAdapter.this.writeOutput(panel, line);
        }

        @Override
        public void publishBuildDiagnostics(BuildResult result, boolean revealOnFailure) {
            JavaIdeAdapter.this.publishBuildDiagnostics(result, revealOnFailure);
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
            JavaIdeAdapter.this.publishTestDiagnostics(result);
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
            return JavaIdeAdapter.this.openBuildDebugListener(module, cancelProcess, onAttach);
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
            JavaIdeAdapter.this.warmUpDebugAdapter();
        }

        @Override
        public boolean hasRunningProcess() {
            return JavaIdeAdapter.this.hasRunningProcess();
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
    private final GhostTextSupport ghostTextSupport = new GhostTextSupport(adapterHost);
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
    private final Map<RunConfigurationKey, AtomicBoolean> pendingLaunches =
            new ConcurrentHashMap<>();
    private final Set<Path> debugSteppedFiles = ConcurrentHashMap.newKeySet();
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

    private static final Color DELETE_ACCENT = new Color(220, 53, 69);
    
    private final AtomicLong runButtonsTicket = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final Object fileWatcherLock = new Object();
    private final Object debugSessionLock = new Object();
    private volatile Path fileWatcherRoot;
    private volatile MainClassMemo mainClassMemo;
    private volatile Path projectRoot;
    private volatile JavaProjectDescriptor descriptor;
    private final JavaPathTransferRefactoring pathTransfers = new JavaPathTransferRefactoring(new PathTransferHost());
    private volatile IdeProjectContext projectContext;
    private volatile JdkService jdkService;
    private volatile JdkInstallation projectJdk;
    private volatile SwingDesignerSupport swingDesigner;
    private volatile OutputPanelHandle swingDesignerOutput;
    private volatile JdtBuildMode appliedBuildMode;
    private volatile JdkManagerPanel jdkManagerPanel;
    private static final String STRUCTURE_TAB_ID = "javaProjectStructure";


    private static final String IDE_MENU_ID_NEW = "tree.new";

    private static final int IDE_NEW_MENU_INDEX = 4;

    private static final String MENU_ID_NEW_JAVA = "java.tree.new";
    private static final String MENU_ID_BUILD_MODULE = "java.tree.buildModule";
    private static final String MENU_ID_SYNC = "java.tree.sync";
    private static final String MENU_ID_ADD_DEPENDENCY = "java.tree.addDependency";
    private static final String MENU_ID_RELOAD = "java.tree.reload";
    private static final String MENU_ID_MARK_DIRECTORY = "java.tree.markDirectory";

    private volatile JavaLanguageServer jdtLs;
    private volatile ClassFileSupport classFileUris;
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
    private volatile String testPanelId;
    private final AtomicBoolean buildToolsSyncPending = new AtomicBoolean();
    private final Set<Path> pendingModuleDirectoryRenames = ConcurrentHashMap.newKeySet();
    private final AtomicLong syncGeneration = new AtomicLong();
    private final AtomicLong automaticSyncTicket = new AtomicLong();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger automaticSyncAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicReference<SyncWork> syncWork = new AtomicReference<>();
    private final MavenPluginGoals pluginGoals = new MavenPluginGoals(this::pluginRepository);
    private volatile Path pluginRepositoryRoot;
    private volatile Path pluginRepositoryPath;
    private final AtomicBoolean naturalDebugEnd = new AtomicBoolean();
    private final Map<Path, ConditionEditorSession> conditionSessions = new ConcurrentHashMap<>();
    private final AtomicReference<ConditionEditorSession> activeConditionSession = new AtomicReference<>();
    private final TodoPanelHost todoSupport = new TodoPanelHost(adapterHost);
    private final AtomicLong treeIconRefreshTicket = new AtomicLong();
    private final AtomicBoolean treeIconRefreshAll = new AtomicBoolean();
    private final Set<Path> treeIconRefreshPaths = ConcurrentHashMap.newKeySet();
    private final Set<Path> migratedBuildRunConfigurations = ConcurrentHashMap.newKeySet();
    private volatile JavaProjectStructurePanel structurePanel;
    private volatile JavaBuildToolsPanel buildToolsPanel;
    private volatile String buildToolsPanelId;
    private volatile JavaFileChangeRouter fileChangeRouter;
    private volatile IdeProjectFileWatcher projectFileWatcher;
    private volatile String fileWatcherListenerId;
    private volatile JavaPluginSettings settings;
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
        runSupport = null;
        closeSwingDesigner();
        runBuildProgress.set(null);
        cancelStartupBuild();
        hideProgress(STARTUP_BUILD_PROGRESS_ID);
        closeDebugSession();
        activeJavaEditor = null;
        selectedRunConfig = null;
        mainClassMemo = null;
        unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        if (coordinator != null) {
            coordinator.resetLocalRepository();
        }
        lexicalIndex.clear();
        spring.index().clear();
        todoSupport.reset();
        RunFormChoicesLoader choicesLoader = runFormChoicesLoader;
        if (choicesLoader != null) {
            choicesLoader.invalidate();
        }
        buildToolsSyncPending.set(false);
        spring.resetConfiguration();
        problems.clearAll();
        diagnosticReanalysisRunning.set(false);
        refreshProblemsPanel();
        coverageSupport.clear();
        coverageSupport.detachAllGutters();
        javaEditors.clear();
        diskBaseline.clear();
        lastEditorContents.clear();
        lspProgress.set(0);
        hideProgress(LSP_PROGRESS_ID);
        syncGeneration.incrementAndGet();
        syncRunning.set(false);
        automaticSyncTicket.incrementAndGet();
        syncWork.set(null);
        hideProgress(SYNC_PROGRESS_ID);
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
        cancelStartupBuild();
        background.close();
        closeSwingDesigner();
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
        unregisterFileWatcher();
        DependencyManagerCoordinator coordinator = dependencyCoordinator;
        dependencyCoordinator = null;
        if (coordinator != null) {
            coordinator.close();
        }
        autoCompleteIdle.cancel();
        for (ConditionEditorSession conditionSession : List.copyOf(conditionSessions.values())) {
            try {
                conditionSession.close();
            } catch (RuntimeException error) {
                log.debug("Falha ao fechar editor de condicao no unload: {}", error.getMessage());
            }
        }
        conditionSessions.clear();
        JavaDebugValuePopup popup = debugValuePopup;
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
                debugPanelId, testPanelId, todoSupport.panelId(), buildToolsPanelId, spring.panelId())) {
            if (panelId == null) {
                continue;
            }
            try {
                unregisterToolPanel(panelId);
            } catch (RuntimeException error) {
                log.debug("Falha ao remover painel {} no unload: {}", panelId, error.getMessage());
            }
        }
        debugPanelId = null;
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
            refreshRunButtonsForCurrentFile();
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
                if (!publishDescriptor(ticket, root, described)) {
                    return;
                }
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
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_BUILD_LSP_WAIT_MS);
        while (current(ticket, root) && System.nanoTime() < deadline) {
            LanguageServerState state = lsp.getState();
            if (state != LanguageServerState.STARTING && state != LanguageServerState.INDEXING) {
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
        JavaLanguageServer lsp = ensureLanguageServer();
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
                    spring.loadMetadata(ticket, root);
                }
            }
            finishDiagnosticReanalysis(ticket, root, error == null && lsp.isReady());
        });
    }

    private synchronized JavaLanguageServer ensureLanguageServer() {
        if (unloaded) {
            throw new IllegalStateException("plugin Java descarregado");
        }
        JavaLanguageServer existing = jdtLs;
        if (existing != null) {
            return existing;
        }
        JdkService jdks = ensureJdkService();
        SdkDownloader downloader = new SdkDownloader(resolveDownloadObserver());
        JavaLanguageServer created = LanguageServers.defaultProvider().create(jdks, downloader,
                this::onLspDiagnosticsPublished);
        created.setMaxHeap(settings().getLanguageServerMemory());
        created.setInlayHintsMode(settings().getInlayHints());
        created.setStatusListener(this::publishLanguageServerStatus);
        created.setWorkListener(this::publishLanguageServerWork);
        created.setCodeLensRefreshListener(path -> {
            if (path == null || !conditionSessions.containsKey(path.toAbsolutePath().normalize())) {
                requestRefreshCodeLenses(path);
            }
        });
        created.setWarmUpCompleteListener(this::refreshJavaEditorsAfterIndexing);
        created.setLateCompletionListener(completionEngine::onLateCompletion);
        created.setDocumentUpgradeListener(this::onLanguageServerDocumentUpgrade);
        languageServerReadyHandled.set(false);
        classFileUris = created.extension(ClassFileSupport.class);
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
        JavaLanguageServer lsp = jdtLs;
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

    private boolean applyLombokAgent(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        JavaAgentSupport agents = lsp == null ? null : lsp.extension(JavaAgentSupport.class);
        if (agents == null) {
            return false;
        }
        if (!settings().isLombokSupport()) {
            lombokSupport.update(LombokSupportStatus.DISABLED, "");
            return agents.setLombokAgentJar(null);
        }
        try {
            LombokAgentResolver.Agent agent = ensureLombokResolver()
                    .resolveAgent(current, resolvedClasspath(lsp, current));
            if (!agent.declared()) {
                lombokSupport.update(LombokSupportStatus.NOT_USED, "");
                return agents.setLombokAgentJar(null);
            }
            if (!agent.isUsable()) {
                lombokSupport.update(LombokSupportStatus.ERROR, agent.failure());
                return agents.setLombokAgentJar(null);
            }
            lombokSupport.update(LombokSupportStatus.STARTING,
                    agent.version() == null ? LombokAgentResolver.TESTED_VERSION : agent.version());
            return agents.setLombokAgentJar(agent.jar());
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

    private List<Path> resolvedClasspath(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        if (lsp == null || current == null || !lsp.isInteractive()) {
            return List.of();
        }
        ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
        if (model == null) {
            return List.of();
        }
        return model.runtimeClasspath(current.root())
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

    private void requestJdtLsProjectConfigurationRefresh(JavaLanguageServer lsp) {
        ProjectModelSupport model = lsp == null ? null : lsp.extension(ProjectModelSupport.class);
        if (model != null && lsp.isInteractive()) {
            model.projectConfigurationUpdate();
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

    private void refreshLombokStatusAfterServerState(LanguageServerState state) {
        LombokSupportStatus current = lombokSupport.status();
        if (current != LombokSupportStatus.STARTING && current != LombokSupportStatus.ACTIVE) {
            return;
        }
        if (state == LanguageServerState.READY) {
            lombokSupport.update(LombokSupportStatus.ACTIVE, lombokSupport.detail());
            return;
        }
        if (state == LanguageServerState.ERROR) {
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
        JavaLanguageServer lsp = jdtLs;
        LanguageServerState state = lsp == null ? LanguageServerState.NOT_STARTED : lsp.getState();
        refreshLombokStatusAfterServerState(state);
        if (state == LanguageServerState.STARTING || state == LanguageServerState.INDEXING) {
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
        if (state == LanguageServerState.READY) {
            lspProgress.set(100);
            updateProgress(LSP_PROGRESS_ID, message, 100);
            hideProgress(LSP_PROGRESS_ID);
            if (languageServerReadyHandled.compareAndSet(false, true)) {
                Path root = projectRoot;
                JavaProjectDescriptor current = descriptor;
                if (root != null && current != null && current.spring()
                        && settings().isSpringSupport()) {
                    spring.loadMetadata(lifecycle.get(), root);
                }
            }
            return;
        }
        hideProgress(LSP_PROGRESS_ID);
        publishLanguageServerWork(null, -1, false);
        if (state == LanguageServerState.ERROR) {
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
        JavaLanguageServer lsp = jdtLs;
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

    private boolean publishDescriptor(long ticket, Path root, JavaProjectDescriptor described) {
        List<RunConfigurationData> configurations = buildStaticRunConfigurations(described);
        synchronized (lifecycleLock) {
            if (!current(ticket, root)) {
                return false;
            }
            descriptor = described;
            staticRunConfigurations = configurations;
            mainClassMemo = null;
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
        configureGhostText(editorContext);
        coverageSupport.installGutter(editorContext);
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
        if (context == null || isDebugPaused() || SpringConfigSupport.isConfigFile(context.filePath())) {
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
        if (isDebugPaused()) {
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
        JavaLanguageServer lsp = jdtLs;
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

    private boolean semanticUsages(JavaLanguageServer lsp, List<Path> targets, Set<Path> deleted,
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
        synchronized (fileWatcherLock) {
            if (fileWatcherListenerId != null && Objects.equals(fileWatcherRoot, projectRoot)) {
                return;
            }
            unregisterFileWatcher();
            registerFileWatcherLocked();
        }
    }

    private void registerFileWatcherLocked() {
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
        fileWatcherRoot = projectRoot;
        fileWatcherListenerId = watcher.addFileWatcherListener(router::accept);
        log.info("Observador de arquivos do Java registrado: {}", fileWatcherListenerId);
    }

    private void unregisterFileWatcher() {
        String listenerId;
        IdeProjectFileWatcher watcher;
        synchronized (fileWatcherLock) {
            listenerId = fileWatcherListenerId;
            watcher = projectFileWatcher;
            fileWatcherListenerId = null;
            projectFileWatcher = null;
            fileWatcherRoot = null;
        }
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
            JavaLanguageServer lsp = jdtLs;
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

        JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = jdtLs;
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
            JavaLanguageServer lsp = jdtLs;
            ProjectModelSupport model = lsp == null ? null : lsp.extension(ProjectModelSupport.class);
            if (model != null) {
                model.projectConfigurationUpdate();
            }
        }, PROJECT_CONFIGURATION_REQUEST_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void onWatchedSpringConfigFile(Path file, JavaFileChangeRouter.Change change) {
        JavaProjectDescriptor current = descriptor;
        Path root = projectRoot;
        if (current == null || root == null || !current.spring()) {
            return;
        }
        spring.loadConfigIndex(lifecycle.get(), root);
    }

    private void onWatchedBuildFile(Path file, JavaFileChangeRouter.Change change) {
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null) {
            if (change == JavaFileChangeRouter.Change.DELETED) {
                lsp.pathDeleted(file);
            } else {
                lsp.pathChanged(file);
            }
            ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
            if (model != null) {
                model.projectConfigurationUpdate();
            }
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
        spring.index().refreshFile(file, content).thenAccept(snapshot -> {
            if (!current(ticket, root)) {
                return;
            }
            requestRefreshCodeLenses(file);
            SpringExplorerPanel panel = spring.panel();
            if (panel != null) {
                panel.reload();
            }
        });
    }

    private void forgetJavaFile(Path file) {
        lexicalIndex.refreshFile(file, "");
        todoSupport.forget(file);
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
        JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = jdtLs;
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
        public JavaLanguageServer readyServer() {
            JavaLanguageServer lsp = jdtLs;
            return lsp != null && lsp.isReady() ? lsp : null;
        }

        @Override
        public JavaMoveDialogPanel.Choice askMove(JavaPathTransferPlan plan) {
            CompletableFuture<JavaMoveDialogPanel.Choice> answer = new CompletableFuture<>();
            Runnable open = () -> {
                JavaMoveDialogPanel panel = new JavaMoveDialogPanel(plan, answer::complete);
                showPopup(PlatformPopupBuilder.builder()
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
        if (JavaProjectConventions.isMavenPom(filePath)
                || SpringConfigSupport.isConfigFile(filePath) || JavaProjectConventions.isJava(filePath)) {
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
        JavaLanguageServer lsp = runningServerFor(context.filePath());
        if (lsp != null) {
            for (JavaCodeLens lens : lsp.codeLenses(
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
        SpringIndexSnapshot snapshot = spring.index().snapshot();

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
        String baseUrl = spring.baseUrl();
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
        FileCoverage coverage = coverageSupport.store().forFile(context.filePath()).orElse(null);
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
                ClassFileSupport classFiles = classFileUris;
                if (classFiles != null && classFiles.isClassFileUri(location.uri())) {
                    String name = classFiles.classFileSourceName(location.uri());
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
        ClassFileSupport classFiles = classFileUris;
        if (path == null && classFiles != null && classFiles.isClassFileUri(location.uri())) {
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
        JavaLanguageServer lsp = jdtLs;
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (classFiles == null || !lsp.isInteractive()) return;
        String uri = location.uri();
        int line = location.range().start().line();
        int col = location.range().start().col();
        long ticket = navigationTicket.incrementAndGet();

        SwingUtilities.invokeLater(() -> showProgress(NAVIGATION_PROGRESS_ID,
                text("progress.decompiling", "Java: abrindo fonte da dependencia...")));
        background.submit(() -> {
            String source = classFiles.classFileContents(uri);
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
        ClassFileSupport classFiles = classFileUris;
        String fileName = classFiles.classFileSourceName(uri);
        String tabKey = classFiles.classFileTabKey(uri);
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
        JavaLanguageServer lsp = jdtLs;
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        return classFiles == null || !lsp.isInteractive()
                ? null : classFiles.hoverAtUri(uri, context.line(), context.col());
    }

    private void navigateFromClassFile(String uri, int line, int col) {
        JavaLanguageServer lsp = jdtLs;
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (classFiles == null || !lsp.isInteractive()) return;
        background.submit(() -> {
            List<Location> targets = classFiles.definitionsAtUri(uri, line, col);
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
    public CompletableFuture<SignatureHelp> provideSignatureHelpAsync(IdeSignatureHelpContext context) {
        if (debugActive.get()) {
            return CompletableFuture.completedFuture(null);
        }
        JavaLanguageServer lsp = interactiveServerFor(context == null ? null : context.filePath());
        return lsp == null ? CompletableFuture.completedFuture(null)
                : lsp.signatureHelpAsync(context.filePath(), context.text(),
                context.caretLine(), context.caretCol());
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        if (debugActive.get()) {
            return null;
        }
        JavaLanguageServer lsp = interactiveServerFor(context == null ? null : context.filePath());
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
                SpringSearchContributor.search(spring.index().snapshot(), query.term());
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
        for (SpringPropertyUsage usage : spring.index().snapshot().usagesOfProperty(key)) {
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
        for (SpringConfigIndex.Entry entry : spring.configIndex().definitionsOf(key)) {
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
        JavaLanguageServer lsp = interactiveServerFor(filePath);
        List<DocumentSymbol> precise = lsp == null ? List.of()
                : lsp.isReady()
                        ? lsp.documentSymbols(filePath, context.text())
                        : lsp.documentSymbolsInteractive(filePath, context.text());
        if (precise != null && !precise.isEmpty()) {
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
        JavaLanguageServer lsp = interactiveServerFor(filePath);
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
        JavaLanguageServer lsp = runningServerFor(filePath);
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
            JavaLanguageServer lsp = runningServerFor(filePath);
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

    private IdeWorkspaceEdit withLombokAccessors(JavaLanguageServer lsp, IdeRenameContext context, IdeWorkspaceEdit edit) {
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

    private static String renameContentOf(JavaLanguageServer lsp, Path file, Path current, String currentText) {
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
        JavaLanguageServer lsp = runningServerFor(filePath);
        if (lsp == null) {
            return IdeRenamePreparation.rejected(isServerStarting(filePath)
                    ? text("rename.serverLoading", "Aguarde o servidor Java terminar de carregar para renomear")
                    : text("rename.serverUnavailable", "O servidor Java não está disponível para renomear com segurança"));
        }
        PrepareRenameResult prepared = lsp.prepareRename(filePath, text, context.line(), context.col());
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
        JavaLanguageServer lsp = jdtLs;
        if (lsp == null || !JavaProjectConventions.isJava(filePath)) {
            return false;
        }
        LanguageServerState state = lsp.getState();
        return state == LanguageServerState.NOT_STARTED
                || state == LanguageServerState.STARTING
                || state == LanguageServerState.INDEXING;
    }

    private SymbolKind resolveRenameKind(JavaLanguageServer lsp, Path filePath, String text, int line, int col, String name) {
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

    private JavaLanguageServer awaitServerForRename(Path filePath) {
        if (!isServerStarting(filePath)) {
            return null;
        }
        JavaLanguageServer lsp = jdtLs;
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
                if (lsp.getState() == LanguageServerState.ERROR
                        || lsp.getState() == LanguageServerState.STOPPED) {
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
                JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = jdtLs;
        return lsp != null && lsp.isInteractive() && lsp.supportsCallHierarchy();
    }

    @Override
    public boolean isTypeHierarchyEnabled() {
        JavaLanguageServer lsp = jdtLs;
        return lsp != null && lsp.isInteractive() && lsp.supportsTypeHierarchy();
    }

    @Override
    public List<TypeHierarchyItem> prepareTypeHierarchy(IdeCallHierarchyContext context) {
        JavaLanguageServer lsp = context == null ? null : interactiveServerFor(context.filePath());
        return lsp == null ? List.of()
                : lsp.prepareTypeHierarchy(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<TypeHierarchyItem> getSupertypes(TypeHierarchyItem item) {
        JavaLanguageServer lsp = jdtLs;
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.supertypes(item);
    }

    @Override
    public List<TypeHierarchyItem> getSubtypes(TypeHierarchyItem item) {
        JavaLanguageServer lsp = jdtLs;
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.subtypes(item);
    }

    @Override
    public List<CallHierarchyItem> prepareCallHierarchy(IdeCallHierarchyContext context) {
        if (context == null) {
            return List.of();
        }
        JavaLanguageServer lsp = interactiveServerFor(context.filePath());
        if (lsp == null) {
            return List.of();
        }
        return lsp.prepareCallHierarchy(context.filePath(), context.text(),
                context.line(), context.col());
    }

    @Override
    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        JavaLanguageServer lsp = jdtLs;
        return lsp == null || !lsp.isInteractive() ? List.of() : lsp.incomingCalls(item);
    }

    @Override
    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        JavaLanguageServer lsp = jdtLs;
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
        diagnosticsEngine.onWordCaretChange(context);
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
        if (coverageSupport.supportedForProject()) {
            menu.item(text("action.runCoverage", "Rodar com cobertura"),
                    JavaIcons.test(JavaIcons.SMALL), action -> runTestWithCoverage(test));
        }
        menu.getMenu().getPopupMenu().show(event.getComponent(), event.getX(), event.getY());
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




    @Override
    public void onEditorOpen(IdeEditorContext editorContext) {
        configureGhostText(editorContext);
        coverageSupport.installGutter(editorContext);
        installTestGutter(editorContext);
        installCodeActionCommandHandler(editorContext);
        installJavaShortcuts(editorContext);
        JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = interactiveServerFor(pending.file());
        ImportCandidateSupport imports = lsp == null ? null : lsp.extension(ImportCandidateSupport.class);
        if (imports == null || !pasteImportsResolving.add(key)) {
            return;
        }
        background.execute(() -> {
            boolean retry = false;
            try {
                String text = lsp.documentContent(pending.file());
                if (text == null || !text.startsWith(pending.pasted(), pending.offset())) {
                    return;
                }
                ImportLookup lookup = imports.importCandidates(pending.file(), text, pending.range(),
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
        JavaLanguageServer lsp = jdtLs;
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
        if (DiagnosticsEngine.DISABLE_INSPECTION_COMMAND.equals(command.id())) {
            diagnosticsEngine.disableInspection(String.valueOf(command.arguments().getFirst()));
            return;
        }
        if (DiagnosticsEngine.HIDE_OCCURRENCE_COMMAND.equals(command.id())) {
            List<Object> arguments = command.arguments();
            if (arguments.size() >= 3) {
                diagnosticsEngine.hideInspectionOccurrence(String.valueOf(arguments.get(0)),
                        String.valueOf(arguments.get(1)), String.valueOf(arguments.get(2)));
            }
            return;
        }
        if (!JavaLanguageServer.APPLY_CODE_ACTION_COMMAND.equals(command.id())) {
            return;
        }
        String rawAction = String.valueOf(command.arguments().getFirst());
        String sourcePrompt = sourcePromptId(rawAction);
        if (sourcePrompt != null) {
            runSourceAction(sourcePrompt, activeJavaEditor);
            return;
        }
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null) {
            background.submit(() -> applyResolvedCodeAction(lsp, rawAction));
        }
    }

    private void applyResolvedCodeAction(JavaLanguageServer lsp, String rawAction) {
        ResolvedCodeAction resolved = lsp.resolveCodeAction(rawAction);
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

    private Boolean applyWorkspaceEdit(JavaLanguageServer lsp, IdeWorkspaceEdit edit) {
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
        for (String id : List.of(SourceGenerationSupport.OVERRIDE_METHODS_PROMPT,
                SourceGenerationSupport.HASHCODE_EQUALS_PROMPT,
                SourceGenerationSupport.GENERATE_TOSTRING_PROMPT,
                SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT,
                SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT,
                SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT)) {
            if (rawAction.contains(id)) return id;
        }
        return null;
    }

    private void showGenerateActions(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        List<JavaSourceActionDialogs.Choice<String>> choices = List.of(
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT,
                        text("generate.constructor", "Constructor..."), "",
                        JavaSourceActionDialogs.Kind.CONSTRUCTOR),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT,
                        text("generate.accessors", "Getter and Setter..."), "",
                        JavaSourceActionDialogs.Kind.ACCESSOR),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.HASHCODE_EQUALS_PROMPT,
                        "equals() and hashCode()...", "", JavaSourceActionDialogs.Kind.EQUALS_HASH),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_TOSTRING_PROMPT,
                        "toString()...", "", JavaSourceActionDialogs.Kind.TO_STRING),
                new JavaSourceActionDialogs.Choice<>("override",
                        text("generate.override", "Override Methods..."), "",
                        JavaSourceActionDialogs.Kind.OVERRIDE, "Ctrl+Insert"),
                new JavaSourceActionDialogs.Choice<>("implement",
                        text("generate.implement", "Implement Methods..."), "",
                        JavaSourceActionDialogs.Kind.IMPLEMENT, "Ctrl+I"),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT,
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

    private void runSourceAction(String command, IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        switch (command) {
            case SourceGenerationSupport.OVERRIDE_METHODS_PROMPT -> showOverrideMethods(context, false);
            case SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT -> showConstructors(context);
            case SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT -> showAccessors(context);
            case SourceGenerationSupport.HASHCODE_EQUALS_PROMPT -> showHashCodeEquals(context);
            case SourceGenerationSupport.GENERATE_TOSTRING_PROMPT -> showToString(context);
            case SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT -> showDelegateMethods(context);
            default -> { }
        }
    }

    private SourceGenerationSupport sourceGenerationFor(Path file) {
        JavaLanguageServer lsp = interactiveServerFor(file);
        return lsp == null ? null : lsp.extension(SourceGenerationSupport.class);
    }

    private void showOverrideMethods(IdeEditorContext context, boolean implementOnly) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            SourceGenerationSupport.OverrideStatus status = generator.overridableMethods(
                    context.filePath(), source, line, col);
            List<SourceGenerationSupport.SourceItem> methods = status.methods().stream()
                    .filter(item -> item.selected() == implementOnly).toList();
            SwingUtilities.invokeLater(() -> {
                if (methods.isEmpty()) {
                    sourceActionUnavailable(implementOnly ? "Implement Methods" : "Override Methods");
                    return;
                }
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(),
                        implementOnly ? text("generate.implement", "Implement Methods")
                                : text("generate.override", "Override Methods"),
                        status.type(), sourceChoices(methods, implementOnly
                                ? JavaSourceActionDialogs.Kind.IMPLEMENT
                                : JavaSourceActionDialogs.Kind.OVERRIDE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateOverridableMethods(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showConstructors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            SourceGenerationSupport.ConstructorsStatus status = generator.constructorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.constructors().isEmpty()) {
                    sourceActionUnavailable(text("generate.constructor", "Constructor"));
                    return;
                }
                List<SourceGenerationSupport.SourceItem> constructors = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.constructor", "Constructor"),
                        text("generate.chooseConstructors", "Selecione os construtores da superclasse"),
                        sourceChoices(status.constructors(), JavaSourceActionDialogs.Kind.CONSTRUCTOR), item -> true);
                if (constructors == null || constructors.isEmpty()) return;
                List<SourceGenerationSupport.SourceItem> fields = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(createModernComponentDialogBuilder(),
                        text("generate.constructor", "Constructor"),
                        text("generate.chooseFields", "Selecione os campos que serao inicializados"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        SourceGenerationSupport.SourceItem::selected);
                if (fields == null) return;
                submitGeneration(context, source, () -> generator.generateConstructors(
                        context.filePath(), source, line, col, constructors, fields));
            });
        });
    }

    private void showAccessors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            List<SourceGenerationSupport.SourceItem> available = generator.accessorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (available.isEmpty()) {
                    sourceActionUnavailable(text("generate.accessors", "Getter and Setter"));
                    return;
                }
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.accessors", "Getter and Setter"),
                        text("generate.chooseAccessors", "Selecione os campos"),
                        sourceChoices(available, JavaSourceActionDialogs.Kind.ACCESSOR), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateAccessors(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showHashCodeEquals(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            SourceGenerationSupport.FieldsStatus status = generator.hashCodeEqualsStatus(
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
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), "equals() and hashCode()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateHashCodeEquals(
                        context.filePath(), source, line, col, selected, status.exists()));
            });
        });
    }

    private void showToString(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            SourceGenerationSupport.FieldsStatus status = generator.toStringStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.exists() && !JavaSourceActionDialogs.confirm(createModernComponentDialogBuilder(Boolean.class),
                        "toString()", text("generate.replaceToString",
                                "toString() ja existe. Deseja substituir a implementacao?"),
                        text("generate.replace", "Substituir"))) return;
                List<SourceGenerationSupport.SourceItem> selected = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(createModernComponentDialogBuilder(), "toString()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        SourceGenerationSupport.SourceItem::selected);
                if (selected == null) return;
                submitGeneration(context, source, () -> generator.generateToString(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    private void showDelegateMethods(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        background.submit(() -> {
            List<SourceGenerationSupport.DelegateTarget> targets = generator.delegateTargets(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (targets.isEmpty()) {
                    sourceActionUnavailable(text("generate.delegate", "Delegate Methods"));
                    return;
                }
                List<JavaSourceActionDialogs.Choice<SourceGenerationSupport.DelegateTarget>> choices = targets.stream()
                        .map(target -> new JavaSourceActionDialogs.Choice<>(target, target.label(), "",
                                JavaSourceActionDialogs.Kind.FIELD))
                        .toList();
                SourceGenerationSupport.DelegateTarget target = JavaSourceActionDialogs.chooseOne(
                        createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateTarget", "Selecione o campo delegado"), choices);
                if (target == null) return;
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateMethods", "Selecione os metodos delegados"),
                        sourceChoices(target.methods(), JavaSourceActionDialogs.Kind.DELEGATE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateDelegateMethods(
                        context.filePath(), source, line, col, target, selected));
            });
        });
    }

    private static List<JavaSourceActionDialogs.Choice<SourceGenerationSupport.SourceItem>> sourceChoices(
            List<SourceGenerationSupport.SourceItem> items, JavaSourceActionDialogs.Kind kind) {
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
        JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = jdtLs;
        if (editorContext != null && JavaProjectConventions.isJava(editorContext.filePath())) {
            Path edited = editorContext.filePath().toAbsolutePath().normalize();
            String currentText = Objects.toString(editorContext.getText(), "");
            String previousText = lastEditorContents.put(edited, currentText);
            JavaProjectTreeIcons.updateOpenSource(edited, currentText);
            requestJavaTreeIconRefresh(edited);
            if (lsp != null) {
                lsp.changeDocument(editorContext.filePath(), currentText);
            }
            if (problems.move(edited, previousText, currentText)) refreshProblemsPanel();
            scheduleRunButtonsRefresh();
        }
    }

    @Override
    public void onEditorClose(Path filePath) {
        JavaProjectTreeIcons.closeSource(filePath);
        requestJavaTreeIconRefresh(filePath);
        autoCompleteIdle.cancel();
        IdeEditorContext active = activeJavaEditor;
        if (active != null && Objects.equals(JavaProjectConventions.normalize(active.filePath()),
                JavaProjectConventions.normalize(filePath))) {
            activeJavaEditor = null;
            refreshRunButtonsForCurrentFile();
        }
        JavaLanguageServer lsp = jdtLs;
        if (JavaProjectConventions.isJava(filePath)) {
            coverageSupport.detachGutter(filePath);
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
        JavaLanguageServer lsp = runningServerFor(filePath);
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
        JavaLanguageServer lsp = jdtLs;
        if (JavaProjectConventions.isJava(filePath)) {
            diskBaseline.put(JavaProjectConventions.normalize(filePath), content);
            JavaProjectTreeIcons.invalidate(filePath);
            requestJavaTreeIconRefresh(filePath);
        }
        if (lsp != null && JavaProjectConventions.isJava(filePath)) {
            lsp.saveDocument(filePath, content);
        }
        SwingDesignerSupport designer = swingDesigner;
        if (designer != null && JavaProjectConventions.isJava(filePath)) {
            designer.onJavaFileSaved(filePath);
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

    private List<Location> resolveDefinitions(Path filePath, String text, int line, int col) {
        return resolveNavigation(filePath, text, line, col, Kind.DEFINITION).locations();
    }

    Result resolveNavigation(Path filePath, String source, int line, int col, Kind kind) {
        if (!JavaProjectConventions.isJava(filePath)) return Result.of(Status.UNAVAILABLE);
        long springStart = System.nanoTime();
        SpringNavigation.Target spring = springTargetAt(filePath, source, line, col);
        long springMs = elapsedMs(springStart);
        if (kind == Kind.DEFINITION && spring != null && spring.kind() == SpringNavigation.Kind.CONFIG_KEY) {
            List<Location> keys = configKeyDefinitions(spring.token());
            if (!keys.isEmpty()) return new Result(Status.LOCAL, keys);
        }
        JavaLanguageServer lsp = interactiveServerFor(filePath);
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

    private SpringNavigation.Target springTargetAt(Path filePath, String text, int line, int col) {
        if (!isSpringNavigationEnabled()) {
            return null;
        }
        return SpringNavigation.definitions(spring.index().snapshot(), filePath, text, line, col)
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
        if (descriptor != null && descriptor.kind().hasBuildTool()) {
            target.item(text("new.module", "Modulo..."), JavaIcons.module(JavaIcons.SMALL), event -> createJavaModule(directory));
        }
        for (JavaFileTemplates.Kind kind : kinds) {
            target.item(kind.displayName(), iconFor(kind),
                    event -> createJavaFile(kind, directory));
        }
    }

    private void createJavaModule(Path directory) {
        JavaProjectDescriptor project = descriptor;
        if (project == null) return;
        String name = createModernInputDialogBuilder().title("Novo modulo")
                .message("Nome do modulo em " + directory + ":").show();
        if (name == null || name.isBlank()) return;
        background.submit(() -> {
            try {
                var plan = dtm.ide.wizard.JavaModuleScaffolder.prepare(project, directory, name.trim());
                String openParent = readCurrentText(plan.parentBuild());
                if (openParent != null && !Objects.equals(openParent.replace("\r\n", "\n"),
                        plan.previousParent() == null ? null : plan.previousParent().replace("\r\n", "\n")))
                    throw new IllegalStateException("Salve as alteracoes do build pai antes de criar o modulo.");
                boolean accepted = !plan.convertsPackaging() || onUi(() -> JavaSourceActionDialogs.confirm(
                        createModernComponentDialogBuilder(Boolean.class), "Converter projeto em agregador",
                        "O POM pai sera convertido para packaging pom. Seus fontes deixarao de ser compilados neste modulo. Continuar?", "Converter e criar"));
                if (!accepted) return;
                dtm.ide.wizard.JavaModuleScaffolder.create(plan);
                SwingUtilities.invokeLater(() -> {
                    IdeEditorContext editor = editorContextFor(plan.parentBuild());
                    if (editor != null) editor.setText(plan.updatedParent());
                    requestProjectTreeViewRefresh();
                    onBuildFileChanged(plan.parentBuild());
                    openAt(plan.files().keySet().iterator().next(), 0, 0);
                });
            } catch (Exception error) {
                log.warn("Falha ao criar modulo", error);
                setStatusBarText("Java: " + error.getMessage());
            }
        });
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
        spring.index().snapshot().types().stream()
                .filter(type -> type.kind() == JavaType.Kind.CLASS
                        || type.kind() == JavaType.Kind.INTERFACE)
                .filter(type -> type.simpleName().toLowerCase(Locale.ROOT).contains(lower))
                .sorted(Comparator.comparing((JavaType type) ->
                                !type.simpleName().toLowerCase(Locale.ROOT).startsWith(lower))
                        .thenComparing(JavaType::simpleName))
                .forEach(type -> found.putIfAbsent(type.qualifiedName(),
                        new JavaTypeCreationPanel.TypeCandidate(type.qualifiedName(),
                                type.kind() == JavaType.Kind.INTERFACE)));
        JavaLanguageServer lsp = jdtLs;
        if (lsp != null && lsp.isInteractive()) {
            for (TypeSymbol symbol : lsp.workspaceTypes(term)) {
                found.putIfAbsent(symbol.qualifiedName(), new JavaTypeCreationPanel.TypeCandidate(
                        symbol.qualifiedName(), symbol.isInterface()));
            }
        }
        return found.values().stream().limit(80).toList();
    }

    private String withInheritedMembers(Path file, String skeleton, boolean hasSuperclass) {
        JavaLanguageServer lsp = interactiveServerFor(file);
        SourceGenerationSupport generator = lsp == null ? null : lsp.extension(SourceGenerationSupport.class);
        if (generator == null) {
            return null;
        }
        try {
            String source = skeleton;
            if (hasSuperclass) {
                int line = JavaFileTemplates.closingBraceLine(source);
                SourceGenerationSupport.ConstructorsStatus constructors =
                        generator.constructorsStatus(file, source, line, 0);
                boolean needsConstructor = !constructors.constructors().isEmpty()
                        && constructors.constructors().stream()
                        .noneMatch(item -> item.label().endsWith("()"));
                if (needsConstructor) {
                    var edits = generator.generateConstructors(file, source, line, 0,
                            constructors.constructors(), List.of());
                    if (edits != null && !edits.isEmpty()) {
                        source = lsp.applyTextEdits(source, edits);
                    }
                }
            }
            int line = JavaFileTemplates.closingBraceLine(source);
            SourceGenerationSupport.OverrideStatus status = generator.overridableMethods(file, source, line, 0);
            if (status.type().isBlank()) {
                return null;
            }
            List<SourceGenerationSupport.SourceItem> abstracts = status.methods().stream()
                    .filter(SourceGenerationSupport.SourceItem::selected).toList();
            if (!abstracts.isEmpty()) {
                var edits = generator.generateOverridableMethods(file, source, line, 0, abstracts);
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
        List<JpaEntity> entities = spring.index().snapshot().entities().stream()
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

    private synchronized SwingDesignerSupport ensureSwingDesigner() {
        if (unloaded || descriptor == null) {
            return null;
        }
        SwingDesignerSupport current = swingDesigner;
        if (current == null) {
            current = new SwingDesignerSupport(new AdapterSwingDesignerEnvironment());
            swingDesigner = current;
        }
        return current;
    }

    private void closeSwingDesigner() {
        SwingDesignerSupport current;
        synchronized (this) {
            current = swingDesigner;
            swingDesigner = null;
        }
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.debug("Falha ao encerrar o Swing Designer: {}", e.getMessage());
            }
        }
        swingDesignerOutput = null;
    }

    private void writeSwingDesignerOutput(String line) {
        OutputPanelHandle panel = swingDesignerOutput;
        if (panel == null) {
            try {
                panel = requestOutputPanel("Swing Designer", OutputPanelOptions.output());
                swingDesignerOutput = panel;
            } catch (RuntimeException e) {
                log.debug("Painel de saida do Swing Designer indisponivel: {}", e.getMessage());
                return;
            }
        }
        writeOutput(panel, line);
    }

    private final class AdapterSwingDesignerEnvironment implements SwingDesignerEnvironment {

        @Override
        public JavaProjectDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public JdkInstallation projectJdk() {
            return getProjectJdk();
        }

        @Override
        public java.util.Optional<String> runtimeClasspath(JavaModule module) {
            BuildSystem build = ensureBuildSystem();
            return build == null ? java.util.Optional.empty() : runtimeClasspathOf(build, module);
        }

        @Override
        public BuildResult compile(JavaModule module, Consumer<String> output) {
            JavaProjectDescriptor current = descriptor;
            BuildSystem build = ensureBuildSystem();
            if (current == null || build == null) {
                return BuildResult.failed("compile", text("swing.viewer.noBuild",
                        "O projeto ainda nao foi carregado."));
            }
            if (settings().isIncrementalBuild()) {
                IncrementalJavaBuilder builder = new IncrementalJavaBuilder(current,
                        JavaIdeAdapter.this::ensureBuildSystem, JavaIdeAdapter.this::getProjectJdk);
                if (builder.isApplicable(module)) {
                    return builder.build(module, false, output);
                }
            }
            return build.execute(BuildRequest.of(BuildSystem.BuildAction.COMPILE, module)
                    .withSkipTests(true), output);
        }

        @Override
        public void output(String line) {
            writeSwingDesignerOutput(line);
        }

        @Override
        public void openSource(Path file, int line) {
            javax.swing.SwingUtilities.invokeLater(() -> openAt(file, Math.max(0, line - 1), 0));
        }

        @Override
        public void openSourceAt(Path file, int line, int column) {
            javax.swing.SwingUtilities.invokeLater(() -> openAt(file, Math.max(0, line - 1), Math.max(0, column)));
        }

        @Override
        public String sourceText(Path file) {
            return readCurrentText(file);
        }

        @Override
        public boolean applySource(Path file, String expected, String updated) {
            return Boolean.TRUE.equals(onUi(() -> {
                IdeEditorContext editor = getEditor(file, true);
                if (editor == null || editor.isReadOnly() || !Objects.equals(editor.getText(), expected)) {
                    return false;
                }
                int start = 0;
                int limit = Math.min(expected.length(), updated.length());
                while (start < limit && expected.charAt(start) == updated.charAt(start)) {
                    start++;
                }
                int endExpected = expected.length();
                int endUpdated = updated.length();
                while (endExpected > start && endUpdated > start
                        && expected.charAt(endExpected - 1) == updated.charAt(endUpdated - 1)) {
                    endExpected--;
                    endUpdated--;
                }
                TextEdit edit = new TextEdit(new Range(TextOffsets.position(expected, start),
                        TextOffsets.position(expected, endExpected)), updated.substring(start, endUpdated));
                if (!editor.applyEdits(List.of(edit)) || !Objects.equals(editor.getText(), updated)) {
                    int line = editor.getCaretLine();
                    int col = editor.getCaretCol();
                    editor.setText(updated);
                    editor.setCaretPosition(line, col);
                }
                JavaLanguageServer lsp = jdtLs;
                if (lsp != null) {
                    lsp.changeDocument(file, editor.getText());
                }
                editor.refreshDiagnostics();
                return true;
            }));
        }

        @Override
        public boolean renameSymbol(Path file, String text, int offset, String newName) {
            JavaLanguageServer lsp = jdtLs;
            if (lsp == null || text == null) {
                return false;
            }
            Position position = TextOffsets.position(text, offset);
            IdeWorkspaceEdit edit = lsp.renameWorkspace(file, text, position.line(), position.col(), newName);
            if (edit == null || edit.isEmpty()) {
                return false;
            }
            return Boolean.TRUE.equals(onUi(() -> applyWorkspaceEdit(lsp, edit)));
        }

        @Override
        public BuildResult compileShadow(JavaModule module, Path source, Path outputDir, Consumer<String> output) {
            JavaProjectDescriptor current = descriptor;
            if (current == null) {
                return BuildResult.failed("javac", text("swing.viewer.noBuild", "O projeto ainda nao foi carregado."));
            }
            return new IncrementalJavaBuilder(current, JavaIdeAdapter.this::ensureBuildSystem,
                    JavaIdeAdapter.this::getProjectJdk).compileDetached(module, List.of(source), outputDir, output);
        }
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
                cancelled -> ensureRunSupport().launch(resolved, context, 0, cancelled));
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
                cancelled -> ensureRunSupport().launchWithCoverage(resolved, context,
                        coverageArgument, cancelled));
        trackRunningProcess(configuration, handle);
        coverageSupport.awaitRun(handle, CoverageAgent.execFileFor(descriptor.root()), descriptor);
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
        CoverageProvisioner provisioner = coverageSupport.provisioner();
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
                cancelled -> ensureRunSupport().launch(resolved, debugContext, jdwpPort, cancelled));
        if (handle.isAlive()) {
            trackRunningProcess(configuration, handle);
            startDebugSession(jdwpPort, debugContext, handle::terminate, targetModule, handle);
            requestSetRunButtonRunning(true);
        }
        return handle;
    }

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
                cancelled -> ensureRunSupport().launch(configuration, context,
                        listener.listenPort(), cancelled));
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

    private void scheduleRunButtonsRefresh() {
        long ticket = runButtonsTicket.incrementAndGet();
        background.schedule(() -> {
            if (runButtonsTicket.get() == ticket) {
                refreshRunButtonsForCurrentFile();
            }
        }, RUN_BUTTONS_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
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
        boolean available = coverageSupport.supportedForProject() && coverageRunnableConfiguration();
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
        String source = Objects.toString(editor.getText(), "");
        MainClassMemo memo = mainClassMemo;
        if (memo != null && memo.matches(file, module, test, source)) {
            return memo.result();
        }
        Optional<MainClassScanner.MainClass> result = MainClassScanner.hasValidMain(source)
                ? MainClassScanner.inspect(file, source, module, test)
                : Optional.empty();
        mainClassMemo = new MainClassMemo(file, module, test, source, result);
        return result;
    }

    private record MainClassMemo(Path file, JavaModule module, boolean test, String source,
                                 Optional<MainClassScanner.MainClass> result) {

        boolean matches(Path otherFile, JavaModule otherModule, boolean otherTest, String otherSource) {
            return test == otherTest && file.equals(otherFile) && Objects.equals(module, otherModule)
                    && (source == otherSource || source.equals(otherSource));
        }
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

    private boolean awaitLanguageServerForDebug(JavaLanguageServer lsp, long generation)
            throws InterruptedException {
        boolean announced = false;
        while (!lsp.isWorkspaceSettled()) {
            if (generation != debugStartGeneration.get()) {
                return false;
            }
            if (lsp.getState() == LanguageServerState.ERROR) {
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
        JavaLanguageServer lsp = jdtLs;
        DebugAdapterSupport debugAdapter = lsp == null ? null : lsp.extension(DebugAdapterSupport.class);
        if (debugAdapter != null) {
            background.submit(debugAdapter::prepareDebugAdapter);
        }
    }

    private void startDebugSession(JavaAttachTarget attachTarget, RunExecutionContext context,
                                   Runnable terminateDebuggee, JavaModule targetModule,
                                   RunProcessHandle processHandle) {
        closeDebugSession();
        debugActive.set(true);
        requestSetRunButtonRunning(true);
        debugEdtWatchdog.start();
        setDebuggeeTerminator(terminateDebuggee);
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
                JavaLanguageServer lsp = ensureLanguageServer();
                if (!awaitLanguageServerForDebug(lsp, generation)) {
                    return;
                }
                DebugAdapterSupport debugAdapter = lsp.extension(DebugAdapterSupport.class);
                if (debugAdapter == null || !debugAdapter.isDebugAdapterAvailable()) {
                    throw new IllegalStateException("O servidor de debug Java nao esta disponivel. Reinicie o IntelliSense Java.");
                }
                int adapterPort = debugAdapter.startDebugSession();
                if (adapterPort <= 0) {
                    throw new IllegalStateException("O JDT LS nao abriu uma sessao de debug.");
                }
                JavaDebugSession session = new JavaDebugSession(adapterPort, attachTarget,
                        projectRoot, context.getBreakpoints(), this::publishDebugSnapshot,
                        background)
                        .projectName(debugProjectName(targetModule))
                        .breakOnCaughtExceptions(settings().isBreakOnCaughtExceptions());
                boolean installed;
                synchronized (debugSessionLock) {
                    installed = generation == debugStartGeneration.get();
                    if (installed) {
                        debugSession = session;
                    }
                }
                if (!installed) {
                    log.info("Sessao de debug descartada: o debug foi encerrado durante a inicializacao");
                    closeQuietly(session);
                    return;
                }
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
        JavaDebugSession session;
        synchronized (debugSessionLock) {
            debugStartGeneration.incrementAndGet();
            session = debugSession;
            debugSession = null;
        }
        closeDebugRelay();
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

    private static void closeQuietly(JavaDebugSession session) {
        try {
            session.close();
        } catch (RuntimeException error) {
            log.debug("Falha ao fechar sessao de debug descartada: {}", error.getMessage());
        }
    }

    private synchronized void setDebuggeeTerminator(Runnable terminator) {
        debuggeeTerminator = terminator;
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
        JavaLanguageServer lsp = jdtLs;
        ClassFileSupport classFiles = lsp == null ? null : lsp.extension(ClassFileSupport.class);
        if (uri == null || classFiles == null) {
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
            String source = classFiles.classFileContents(uri);
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
        boolean cancelledPreparation = process == null && cancelPendingLaunch(configuration);
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
        if (!cancelledPreparation) {
            requestSetRunButtonRunning(hasRunningProcess());
        }
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
                                                     Function<BooleanSupplier, RunProcessHandle> launcher) {
        if (unloaded) {
            return ProcessLauncher.message("Plugin Java descarregado.");
        }
        RunConfigurationKey key = RunConfigurationKey.of(configuration);
        AtomicBoolean cancelled = new AtomicBoolean();
        pendingLaunches.put(key, cancelled);
        try {
            return launchWithBuildProgress(configuration, () -> launcher.apply(cancelled::get));
        } finally {
            pendingLaunches.remove(key, cancelled);
        }
    }

    private boolean cancelPendingLaunch(RunConfigurationData configuration) {
        if (pendingLaunches.isEmpty()) {
            return false;
        }
        AtomicBoolean matching = configuration == null ? null
                : pendingLaunches.get(RunConfigurationKey.of(configuration));
        if (matching != null) {
            matching.set(true);
        } else {
            pendingLaunches.values().forEach(flag -> flag.set(true));
        }
        ensureRunSupport().cancelPreparation();
        return true;
    }

    private RunProcessHandle launchWithBuildProgress(RunConfigurationData configuration,
                                                     Supplier<RunProcessHandle> launcher) {
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
        JavaLanguageServer lsp = jdtLs;
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
        JavaLanguageServer lsp = jdtLs;
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
            spring.setup(ticket, root);

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
            current.onRepositoryInvalidated(() -> {
                BuildSystem build = buildSystem;
                if (build != null) build.invalidateClasspathCache();
                automaticSyncAttempts.set(0);
                scheduleAutomaticSync();
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
        Path root = projectRoot;
        if (root == null) {
            return;
        }
        if (!syncRunning.compareAndSet(false, true)) {
            buildToolsSyncPending.set(true);
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
                JavaProjectDescriptor previous = descriptor;
                JavaProjectDescriptor reloaded = timed("describe(syncProject)",
                        () -> JavaProjectConventions.describe(root));
                if (!current(ticket, root)) {
                    return;
                }
                if (reloaded != null) {
                    descriptor = reloaded;
                }
                if (jdkRequirementChanged(previous, reloaded)) {
                    log.info("JDK pedida pelo projeto mudou de {} para {}; reavaliando a JDK do projeto",
                            previous.jdkMajor().orElse(null), reloaded.jdkMajor().orElse(null));
                    setStatusBarText(text("status.jdkRequirementChanged",
                            "Java: o projeto pede outra JDK - recarregando"));
                    clearCaches();
                    return;
                }
                JavaLanguageServer lsp = jdtLs;
                applyLombokAgent(lsp, descriptor);
                boolean agentChanged = lsp != null && needsLombokAgentRestart(lsp);
                if (agentChanged || lsp == null) {
                    clearCaches();
                    return;
                }
                ProjectModelSupport model = lsp.extension(ProjectModelSupport.class);
                SyncWork work = new SyncWork();
                syncWork.set(work);
                if (model == null || !model.updateProjectConfiguration(root)) {
                    syncWork.compareAndSet(work, null);
                    clearCaches();
                    return;
                }
                waiting = true;
                work.completion().whenComplete((ignored, error) -> {
                    syncWork.compareAndSet(work, null);
                    boolean recovered = error == null;
                    try {
                        if (recovered && current(ticket, root) && syncGeneration.get() == generation) model.resynchronizeAfterProjectUpdate();
                    } catch (Exception failure) {
                        recovered = false;
                        log.warn("Falha ao sincronizar documentos apos atualizar o projeto", failure);
                    } finally {
                        finishSync(generation, ticket, root, recovered);
                    }
                });
            } finally {
                if (!waiting) {
                    finishSync(generation, ticket, root, false);
                }
            }
        });
    }

    static boolean jdkRequirementChanged(JavaProjectDescriptor previous, JavaProjectDescriptor reloaded) {
        return previous != null && reloaded != null
                && !previous.jdkMajor().equals(reloaded.jdkMajor());
    }

    private void finishSync(long generation, long ticket, Path root, boolean synced) {
        if (syncGeneration.get() != generation) {
            return;
        }
        syncRunning.set(false);
        if (buildToolsSyncPending.getAndSet(false)) scheduleAutomaticSync();
        else if (!synced && current(ticket, root) && automaticSyncAttempts.incrementAndGet() <= 3) scheduleAutomaticSync();
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
        buildToolsSyncPending.set(true);
        automaticSyncAttempts.set(0);
        JavaBuildToolsPanel panel = buildToolsPanel;
        if (panel != null) panel.setSyncPending(true);
        scheduleAutomaticSync();
    }

    private void scheduleAutomaticSync() {
        long ticket = automaticSyncTicket.incrementAndGet();
        long session = lifecycle.get();
        background.schedule(() -> {
            Path root = projectRoot;
            if (root == null || session != lifecycle.get() || ticket != automaticSyncTicket.get()) return;
            if (descriptor != null && descriptor.isMaven()) {
                if (!validMavenReactor(root.resolve("pom.xml"), new java.util.HashSet<>())) {
                    setStatusBarText("Java: corrija o POM; a sincronizacao sera retomada automaticamente");
                    return;
                }
            }
            SwingUtilities.invokeLater(this::syncProject);
        }, 1200, TimeUnit.MILLISECONDS);
    }

    static boolean validMavenReactor(Path file, Set<Path> visited) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!visited.add(normalized)) return true;
        dtm.ide.project.MavenPom pom = dtm.ide.project.MavenPom.parse(normalized);
        if (!pom.isValid()) return false;
        for (String module : pom.values("modules", "module")) {
            if (module.contains("${")) continue;
            Path child = normalized.getParent().resolve(module).normalize();
            if (!child.getFileName().toString().endsWith(".xml")) child = child.resolve("pom.xml");
            if (!validMavenReactor(child, visited)) return false;
        }
        return true;
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
        if (mode == appliedBuildMode) {
            return false;
        }
        appliedBuildMode = mode;
        JavaLanguageServer lsp = jdtLs;
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

    private static boolean needsLombokAgentRestart(JavaLanguageServer lsp) {
        JavaAgentSupport agents = lsp.extension(JavaAgentSupport.class);
        return agents != null && agents.needsRestartForLombokAgent();
    }

    private void restartWhenLombokAgentChanged(JavaLanguageServer lsp, JavaProjectDescriptor current) {
        if (current == null || lsp == null) {
            return;
        }
        applyLombokAgent(lsp, current);
        if (!needsLombokAgentRestart(lsp)) {
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
