package dtm.ide.adapter;

import dtm.ide.api.project.diagnostics.IdeProblem;
import dtm.ide.api.project.diagnostics.ProblemsActionHandle;
import dtm.ide.build.BuildDiagnostic;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.ui.JavaIcons;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

public final class ProblemsSupport {

    private static final long PROBLEMS_REFRESH_DELAY_MS = 200;
    private static final String BUILD_PROBLEMS_OWNER = "java.build";
    private static final String LSP_PROBLEMS_OWNER = "java.lsp";

    private final AdapterHost host;
    private final AtomicLong problemsRefreshTicket = new AtomicLong();
    private final AtomicBoolean diagnosticReanalysisRunning = new AtomicBoolean();
    private volatile ProblemsActionHandle clearBuildAction;

    public ProblemsSupport(AdapterHost host) {
        this.host = host;
    }

    public ProblemsActionHandle takeClearBuildAction() {
        ProblemsActionHandle action = clearBuildAction;
        clearBuildAction = null;
        return action;
    }

    public AtomicBoolean reanalysisRunning() {
        return diagnosticReanalysisRunning;
    }

    public void openProblemsPanel() {
        refreshProblemsPanel();
        host.requestOpenProblemsPanel();
    }

    public void refreshProblemsPanel() {
        long ticket = problemsRefreshTicket.incrementAndGet();
        host.background().schedule(() -> {
            if (ticket != problemsRefreshTicket.get()) {
                return;
            }
            List<IdeProblem> build = toIdeProblems(host.problems().buildProblems());
            List<IdeProblem> live = toIdeProblems(host.problems().liveProblems());
            SwingUtilities.invokeLater(() -> {
                if (ticket != problemsRefreshTicket.get()) {
                    return;
                }
                host.publishProblems(BUILD_PROBLEMS_OWNER, build);
                host.publishProblems(LSP_PROBLEMS_OWNER, live);
                ProblemsActionHandle action = ensureClearBuildAction();
                if (action != null) {
                    action.setEnabled(!build.isEmpty());
                }
            });
        }, PROBLEMS_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    ProblemsActionHandle ensureClearBuildAction() {
        ProblemsActionHandle handle = clearBuildAction;
        if (handle != null) {
            return handle;
        }
        handle = host.registerProblemsAction(
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

    void clearBuildProblems() {
        Set<Path> affected = host.problems().clearBuild();
        affected.forEach(host::requestRefreshDiagnostics);
        refreshProblemsPanel();
    }

    public void syncWithDisk() {
        Path root = host.projectRoot();
        JavaLanguageServer lsp = host.languageServer();
        if (root == null || lsp == null) {
            host.setStatusBarText(text("status.noProject", "Java: nenhum projeto aberto"));
            return;
        }
        host.setStatusBarText(text("status.syncingWithDisk",
                "Java: relendo as mudancas feitas fora do editor..."));
        host.background().submit(() -> {
            JavaFileChangeRouter router = host.fileChangeRouter();
            if (router != null) {
                router.acceptDirectory(root);
            }
            lsp.resynchronizeWithDisk();
            host.javaEditors().keySet().forEach(path -> {
                host.requestRefreshDiagnostics(path);
                host.requestRefreshCodeLenses(path);
                host.requestRefreshInlayHints(path);
                host.requestRefreshSemanticTokens(path);
            });
        });
    }

    public void reanalyzeDiagnostics() {
        Path root = host.projectRoot();
        if (root == null) {
            host.setStatusBarText(text("status.noProject", "Java: nenhum projeto aberto"));
            return;
        }
        if (!diagnosticReanalysisRunning.compareAndSet(false, true)) {
            host.setStatusBarText(text("status.diagnosticsReanalysisRunning",
                    "Java: a reanalise de diagnosticos ja esta em andamento"));
            return;
        }

        long ticket = host.nextLifecycleTicket();
        Set<Path> affected = new LinkedHashSet<>(host.javaEditors().keySet());
        affected.addAll(host.problems().paths());

        host.problems().clearAll();
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.clearDiagnostics();
        }
        affected.forEach(host::requestRefreshDiagnostics);
        refreshProblemsPanel();
        host.setStatusBarText(text("status.reanalyzingDiagnostics",
                "Java: limpando diagnosticos e reiniciando a analise..."));

        host.background().submit(() -> {
            if (lsp != null) {
                lsp.stop();
            }
            if (!host.isCurrent(ticket, root)) {
                diagnosticReanalysisRunning.set(false);
                return;
            }

            host.problems().clearLive();
            host.languageServerManager().readyHandled().set(false);
            SwingUtilities.invokeLater(() -> {
                if (host.isCurrent(ticket, root)) {
                    host.javaEditors().keySet().forEach(host::requestRefreshDiagnostics);
                    refreshProblemsPanel();
                }
            });
            host.spring().setup(ticket, root);

            if (host.settings().getLanguageServerMode().startsServer()) {
                host.resolveProjectJdk(ticket, root);
            } else {
                finishDiagnosticReanalysis(ticket, root, true);
            }
        });
    }

    public void finishDiagnosticReanalysis(long ticket, Path root, boolean successful) {
        if (!host.isCurrent(ticket, root)) {
            diagnosticReanalysisRunning.set(false);
            return;
        }
        diagnosticReanalysisRunning.set(false);
        if (successful) {
            host.javaEditors().keySet().forEach(host::requestRefreshDiagnostics);
            refreshProblemsPanel();
            host.setStatusBarText(text("status.diagnosticsReanalyzed",
                    "Java: diagnosticos atualizados"));
        }
    }
}
