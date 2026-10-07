package dtm.ide.adapter;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.project.editor.IdeCodeLensContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.coverage.CoverageDisplay;
import dtm.ide.coverage.FileCoverage;
import dtm.ide.lsp.api.JavaCodeLens;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.navigation.JavaNavigation.Kind;
import dtm.ide.navigation.JavaNavigation.Status;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.JavaRunSupport;
import dtm.ide.run.MainClassScanner;
import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringEndpoint;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringInjection;
import dtm.ide.spring.SpringNavigation;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import dtm.ide.test.JUnitTestDiscovery;
import dtm.ide.test.JavaTest;
import dtm.ide.ui.JavaIcons;
import dtm.stools.component.menu.popup.ActionMenu;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.codelens.CodeLensClickEvent;
import dtm.stools.component.panels.editor.code.codelens.CodeLensItem;
import lombok.extern.slf4j.Slf4j;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class CodeLensSupport {

    private static final int CODE_LENS_TOOLTIP_TARGETS = 8;

    private final AdapterHost host;

    public CodeLensSupport(AdapterHost host) {
        this.host = host;
    }

    public List<CodeLens> getCodeLenses(IdeCodeLensContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        List<CodeLens> lenses = new ArrayList<>();
        JavaLanguageServer lsp = host.runningServerFor(context.filePath());
        if (lsp != null) {
            for (JavaCodeLens lens : lsp.codeLenses(
                    context.filePath(), context.text())) {
                List<Location> targets = host.uniqueLocations(lens.locations());
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

        JavaProjectDescriptor current = host.descriptor();
        if (current == null || !current.spring() || !host.settings().isSpringCodeLens()) {
            return lenses;
        }
        SpringIndexSnapshot snapshot = host.spring().index().snapshot();

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

    void addInjectionLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                                    IdeCodeLensContext context) {
        if (!host.settings().isSpringNavigation()) {
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

    void addEndpointLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                                   IdeCodeLensContext context) {
        String baseUrl = host.spring().baseUrl();
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
                    .onClick(event -> host.openWebBrowser(url))
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

    void copyToClipboard(String value, String status) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(value), null);
            host.setStatusBarText(status);
        } catch (Exception e) {
            log.debug("Falha ao copiar para a area de transferencia: {}", e.getMessage());
        }
    }

    void addCoverageLens(List<CodeLens> lenses, IdeCodeLensContext context) {
        FileCoverage coverage = host.coverage().store().forFile(context.filePath()).orElse(null);
        if (coverage == null || coverage.isEmpty()) {
            return;
        }
        String branches = CoverageDisplay.branchSummary(coverage);
        String tooltip = text("lens.coverageTooltip", "Cobertura da ultima execucao de testes");
        if (!branches.isBlank()) {
            tooltip = tooltip + " - " + text("lens.coverageBranches", "branches") + ": " + branches;
        }
        int line = CoverageDisplay.lensLineOf(host.lexicalIndex().outline(context.text()));
        lenses.add(CodeLens.inline(line, CodeLensItem.builder()
                .text(text("lens.coverage", "Cobertura") + ": " + CoverageDisplay.summary(coverage))
                .tooltip(tooltip)
                .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                .onClick(event -> host.requestOpenToolPanel(host.testPanelId()))
                .build()));
    }

    void addJpaLenses(List<CodeLens> lenses, SpringIndexSnapshot snapshot,
                              IdeCodeLensContext context) {
        if (!host.settings().isSpringJpa()) {
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

    CodeLensItem lensItem(Kind kind, Status status, List<Location> targets,
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
                    .onClick(event -> host.requestRefreshCodeLenses(context.filePath()))
                    .build();
        }
        return null;
    }

    public String countLabel(Kind kind, int count) {
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

    void openLensTargets(Kind kind, IdeCodeLensContext context, List<Location> targets,
                                 CodeLensClickEvent event) {
        if (targets.isEmpty()) return;
        if (targets.size() == 1 && kind != Kind.REFERENCES) {
            Location target = targets.getFirst();
            host.navigateToLocation(target, JavaNavigation.path(target));
            return;
        }
        IdeEditorContext editor = host.editorContextFor(context.filePath());
        MouseEvent mouse = event == null ? null : event.mouseEvent();
        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
        host.showUsagesPopup(targets, context.filePath(),
                editor == null ? context.text() : editor.getText(), editor, screen, kind);
    }

    void openSpringTargets(List<Location> targets, IdeCodeLensContext context,
                                   CodeLensClickEvent event) {
        if (targets.isEmpty()) {
            host.openSpringExplorer();
            return;
        }
        MouseEvent mouse = event == null ? null : event.mouseEvent();
        Point screen = mouse == null ? null : mouse.getLocationOnScreen();
        host.showUsagesPopup(targets, context.filePath(), context.text(),
                host.editorContextFor(context.filePath()), screen, Kind.REFERENCES);
    }

    void addRunLens(List<CodeLens> lenses, IdeCodeLensContext context) {
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

    void showRunLensMenu(CodeLensClickEvent event) {
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

    void launchCurrentFile(boolean debug) {
        if (host.currentMainClass().isEmpty()) {
            host.setStatusBarText("Java: " + text("error.currentFileMain",
                    "O arquivo atual nao possui um metodo main Java valido."));
            return;
        }
        host.background().submit(() -> {
            try {
                String configurationId = currentFileConfigurationId();
                if (configurationId != null) {
                    host.requestRunConfigurationExecution(configurationId, debug);
                    return;
                }
                RunConfigurationData configuration = RunConfigurationData.builder()
                        .type(JavaRunSupport.TYPE_CURRENT_FILE)
                        .build();
                RunExecutionContext context = RunExecutionContext.builder()
                        .projectPath(host.projectRoot())
                        .debug(debug)
                        .build();
                if (debug) {
                    host.launchDebug(configuration, context);
                } else {
                    host.launch(configuration, context);
                }
            } catch (Exception error) {
                log.warn("Falha ao executar o arquivo atual pelo code lens.", error);
                host.setStatusBarText("Java: " + text("error.runLensFailed",
                        "Falha ao executar o arquivo atual") + " - " + error.getMessage());
            }
        });
    }

    String currentFileConfigurationId() {
        List<RunConfigurationData> configurations = host.requestRunConfigurations();
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

    Optional<MainClassScanner.MainClass> mainClassAt(Path filePath, String source) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null || filePath == null || !MainClassScanner.hasValidMain(source)) {
            return Optional.empty();
        }
        Path file = JavaProjectConventions.normalize(filePath);
        JavaModule module = host.moduleContaining(current, file, false);
        boolean test = false;
        if (module == null) {
            module = host.moduleContaining(current, file, true);
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

    public void runTestFromLens(JavaTest test, boolean debug) {
        SwingUtilities.invokeLater(() -> {
            host.ensureTestPanel();
            if (host.testPanelId() != null && !debug) {
                host.requestOpenToolPanel(host.testPanelId());
            }
            if (host.testPanel() != null) {
                if (debug) {
                    host.testPanel().debugTests(List.of(test));
                } else {
                    host.testPanel().runTests(List.of(test));
                }
            }
        });
    }

    public static List<Location> springLocations(SpringNavigation.Target target) {
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
}
