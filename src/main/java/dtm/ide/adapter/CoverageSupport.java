package dtm.ide.adapter;

import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.coverage.CoverageDisplay;
import dtm.ide.coverage.CoverageGutter;
import dtm.ide.coverage.CoverageGutterLayer;
import dtm.ide.coverage.CoverageProvisioner;
import dtm.ide.coverage.CoverageReadResult;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.CoverageStore;
import dtm.ide.coverage.JacocoExecReader;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkService;
import dtm.ide.ui.JavaCoveragePanel;
import dtm.ide.ui.JavaTestExplorerPanel;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class CoverageSupport {

    private static final long COVERAGE_POLL_INTERVAL_MS = 400L;
    private static final long COVERAGE_SETTLE_TIMEOUT_MS = 5000L;

    private final AdapterHost host;
    private final CoverageStore store = new CoverageStore();
    private volatile CoverageProvisioner provisioner;

    public CoverageSupport(AdapterHost host) {
        this.host = host;
    }

    public CoverageStore store() {
        return store;
    }

    public CoverageProvisioner provisioner() {
        CoverageProvisioner existing = provisioner;
        if (existing != null) {
            return existing;
        }
        JdkService jdks = host.currentJdkService();
        if (jdks == null) {
            return null;
        }
        CoverageProvisioner created = new CoverageProvisioner(jdks);
        provisioner = created;
        return created;
    }

    public void readCoverage(Path execFile, JavaProjectDescriptor current) {
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
            host.setStatusBarText(coverageFailureText(result));
            return;
        }
        store.set(result.report());
        JavaTestExplorerPanel panel = host.testPanel();
        if (panel != null) {
            panel.setCoverage(result.report());
        }
        Path root = host.projectRoot();
        if (root != null) {
            host.requestRefreshCodeLenses(root);
        }
        SwingUtilities.invokeLater(this::refreshGutters);
        host.setStatusBarText(coverageSummaryText(result.report()));
        showCoveragePopup(result.report());
    }

    private void showCoveragePopup(CoverageReport report) {
        if (report == null || report.totals().isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> host.showPopup(PlatformPopupBuilder.builder()
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
        host.requestOpenFile(row.file());
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

    public void clear() {
        store.clear();
        JavaTestExplorerPanel panel = host.testPanel();
        if (panel != null) {
            panel.setCoverage(null);
        }
        SwingUtilities.invokeLater(this::refreshGutters);
    }

    public void detachGutter(Path filePath) {
        IdeEditorContext context = host.javaEditors().get(JavaProjectConventions.normalize(filePath));
        if (context != null) {
            CoverageGutter.detach(context);
        }
    }

    public void detachAllGutters() {
        host.javaEditors().values().forEach(CoverageGutter::detach);
    }

    public void installGutter(IdeEditorContext context) {
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
        if (host.settings().isCoverageGutter()) {
            CoverageGutter.apply(layer, store.forFile(file).orElse(null));
        } else {
            CoverageGutter.clear(layer);
        }
        context.repaintGutter();
    }

    public void refreshGutters() {
        host.javaEditors().values().forEach(this::installGutter);
    }

    public void awaitRun(RunProcessHandle handle, Path execFile,
                                  JavaProjectDescriptor current) {
        if (handle == null) {
            return;
        }
        host.background().submit(() -> {
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

    public boolean supportedForProject() {
        JavaProjectDescriptor current = host.descriptor();
        return current != null && (current.isMaven() || current.isGradle());
    }
}
