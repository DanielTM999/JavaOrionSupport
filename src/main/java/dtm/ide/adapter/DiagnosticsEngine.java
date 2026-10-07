package dtm.ide.adapter;

import dtm.ide.BuildProblemsCoordinator;
import dtm.ide.JavaIdeAdapter;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWordCaretContext;
import dtm.ide.inspection.DiagnosticRanges;
import dtm.ide.inspection.InspectionSuppressionStore;
import dtm.ide.inspection.InspectionSuppressions;
import dtm.ide.inspection.JavaDiagnosticEdits;
import dtm.ide.inspection.JavaInspection;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.PomDiagnostics;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.spring.SpringDiagnostics;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringValueDiagnostics;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.ide.spring.infra.SpringInfraDiagnostics;
import dtm.ide.spring.jpa.JpaDiagnostics;
import dtm.ide.spring.jpa.JpqlDiagnostics;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;
import static dtm.ide.adapter.DiagnosticsSupport.unusedFieldDiagnostics;
import static dtm.ide.adapter.DiagnosticsSupport.unusedMethodDiagnostics;

@Slf4j
public final class DiagnosticsEngine {

    public static final String DISABLE_INSPECTION_COMMAND = "java.orion.disableInspection";
    public static final String HIDE_OCCURRENCE_COMMAND = "java.orion.hideInspectionOccurrence";

    private final AdapterHost host;
    private final AtomicLong wordCaretTicket = new AtomicLong();
    private final AtomicReference<CodeActionPrefetch> codeActionPrefetch = new AtomicReference<>();
    private volatile InspectionSuppressionStore suppressionStore;
    private volatile Object codeActionLampHandle;
    private volatile IdeEditorContext codeActionLampContext;

    public DiagnosticsEngine(AdapterHost host) {
        this.host = host;
    }

    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context, boolean incremental,
                                                 Collection<Diagnostic> diagnostics) {
        if (context == null) {
            return null;
        }
        Path filePath = context.getFilePath();
        if (JavaProjectConventions.isMavenPom(filePath)) {
            return PomDiagnostics.validate(context.getText());
        }
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

        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null && lsp.isInteractive()) {
            merged.addAll(lsp.diagnostics(filePath));
        }
        merged.addAll(host.problems().diagnostics(filePath));

        merged.addAll(pluginDiagnostics(filePath, context.getText()));
        merged.addAll(unusedMethodDiagnostics(context.getText(), merged,
                names -> host.lexicalIndex().unusedMethods(names, filePath, context.getText())));
        merged.addAll(unusedFieldDiagnostics(context.getText(), merged,
                names -> host.lexicalIndex().unusedFields(names, filePath, context.getText())));
        List<Diagnostic> visible = InspectionSuppressions.filter(merged, context.getText(),
                host.settings().getDisabledInspections(), occurrenceFilterFor(filePath));
        return visible.isEmpty() && (lsp == null || !lsp.isInteractive()) ? null : visible;
    }

    private List<Diagnostic> pluginDiagnostics(Path filePath, String text) {
        if (SpringConfigSupport.isConfigFile(filePath)) {
            return DiagnosticRanges.clamp(InspectionSuppressions.filter(
                    SpringConfigSupport.validate(host.spring().metadata(), filePath, text),
                    text, host.settings().getDisabledInspections(), occurrenceFilterFor(filePath)), text);
        }
        JavaProjectDescriptor current = host.descriptor();
        if (!JavaProjectConventions.isJava(filePath) || current == null || !current.spring()
                || !host.settings().isSpringSupport()) {
            return List.of();
        }
        SpringIndexSnapshot snapshot = host.spring().index().snapshot();
        List<Diagnostic> plugin = new ArrayList<>(
                SpringDiagnostics.analyze(snapshot, filePath, text));
        if (host.settings().isSpringJpa()) {
            plugin.addAll(JpaDiagnostics.analyze(snapshot, filePath));
            plugin.addAll(JpqlDiagnostics.analyze(snapshot, filePath));
        }
        if (host.settings().isSpringConfigNavigation()) {
            plugin.addAll(SpringValueDiagnostics.analyze(snapshot, host.spring().configIndex(),
                    host.spring().metadata(), filePath));
        }
        if (host.settings().isSpringInfra()) {
            plugin.addAll(SpringInfraDiagnostics.analyze(snapshot.infra(), filePath));
        }
        return DiagnosticRanges.clamp(InspectionSuppressions.filter(plugin, text,
                host.settings().getDisabledInspections(), occurrenceFilterFor(filePath)), text);
    }

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
        JavaLanguageServer lsp = host.interactiveServerFor(context.filePath());
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

    public InspectionSuppressionStore suppressions() {
        InspectionSuppressionStore existing = suppressionStore;
        if (existing != null) {
            return existing;
        }
        Path directory = null;
        try {
            directory = host.resource().getResourcePath();
        } catch (Exception e) {
            log.debug("Diretorio de recursos indisponivel para as supressoes: {}", e.getMessage());
        }
        InspectionSuppressionStore created = new InspectionSuppressionStore(directory);
        suppressionStore = created;
        return created;
    }

    private InspectionSuppressions.OccurrenceFilter occurrenceFilterFor(Path filePath) {
        InspectionSuppressionStore store = suppressions();
        Path root = host.projectRoot();
        return (inspectionId, anchor) ->
                store.isSuppressed(root, filePath, inspectionId, anchor);
    }

    public void hideInspectionOccurrence(String inspectionId, String file, String anchor) {
        if (inspectionId == null || file == null || anchor == null) {
            return;
        }
        Path target = Path.of(file);
        suppressions().suppress(host.projectRoot(), target, inspectionId, anchor);
        host.setStatusBarText(text("status.occurrenceHidden", "Java: aviso ocultado nesta ocorrencia"));
        host.requestRefreshDiagnostics(target);
    }

    public void disableInspection(String inspectionId) {
        JavaPluginSettings current = host.settings();
        current.setInspectionDisabled(inspectionId, true);
        current.save();
        host.setStatusBarText(text("status.inspectionDisabled", "Java: inspecao desativada")
                + " - " + inspectionId);
        host.refreshDiagnosticsOfOpenJavaEditors();
    }

    public void onWordCaretChange(IdeWordCaretContext context) {
        long ticket = wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (context == null || context.filePath() == null || context.editorContext() == null
                || !supportsCodeActionLamp(context.filePath())) {
            return;
        }
        host.background().schedule(
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
        JavaLanguageServer lsp = host.languageServer();
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
                host.requestShowCodeActions(context.filePath());
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

    public static Rectangle codeActionLampBounds(Rectangle editorBounds, int anchorY, Dimension size) {
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

    public void hideCodeActionLamp() {
        Object handle = codeActionLampHandle;
        IdeEditorContext context = codeActionLampContext;
        codeActionLampHandle = null;
        codeActionLampContext = null;
        if (handle != null && context != null) {
            context.removeEditorOverlay(handle);
        }
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
}
