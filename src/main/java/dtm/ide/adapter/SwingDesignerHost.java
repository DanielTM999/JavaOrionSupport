package dtm.ide.adapter;

import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.output.OutputPanelOptions;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.incremental.IncrementalJavaBuilder;
import dtm.ide.editor.TextOffsets;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.swingdesigner.SwingDesignerEnvironment;
import dtm.ide.swingdesigner.SwingDesignerSupport;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class SwingDesignerHost implements SwingDesignerEnvironment {

    private final AdapterHost host;
    private volatile SwingDesignerSupport swingDesigner;
    private volatile OutputPanelHandle swingDesignerOutput;

    public SwingDesignerHost(AdapterHost host) {
        this.host = host;
    }

    @Override
    public Path cacheDirectory() {
        Resource resource = host.resource();
        Path root = resource == null ? null : resource.getResourcePath();
        return root == null ? SwingDesignerEnvironment.super.cacheDirectory() : root.resolve("swing-designer");
    }

    public SwingDesignerSupport currentSwingDesigner() {
        return swingDesigner;
    }

    public SwingDesignerSupport ensureSwingDesigner() {
        synchronized (host.monitor()) {
            if (host.isUnloaded() || host.descriptor() == null) {
                return null;
            }
            SwingDesignerSupport current = swingDesigner;
            if (current == null) {
                current = new SwingDesignerSupport(this);
                swingDesigner = current;
            }
            return current;
        }
    }

    public void closeSwingDesigner() {
        SwingDesignerSupport current;
        synchronized (host.monitor()) {
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

    void writeSwingDesignerOutput(String line) {
        OutputPanelHandle panel = swingDesignerOutput;
        if (panel == null) {
            try {
                panel = host.requestOutputPanel("Swing Designer", OutputPanelOptions.output());
                swingDesignerOutput = panel;
            } catch (RuntimeException e) {
                log.debug("Painel de saida do Swing Designer indisponivel: {}", e.getMessage());
                return;
            }
        }
        host.writeOutput(panel, line);
    }

    @Override
    public JavaProjectDescriptor descriptor() {
        return host.descriptor();
    }

    @Override
    public JdkInstallation projectJdk() {
        return host.projectJdk();
    }

    @Override
    public java.util.Optional<String> runtimeClasspath(JavaModule module) {
        BuildSystem build = host.buildSystem();
        return build == null ? java.util.Optional.empty() : host.runtimeClasspathOf(build, module);
    }

    @Override
    public BuildResult compile(JavaModule module, Consumer<String> output) {
        JavaProjectDescriptor current = host.descriptor();
        BuildSystem build = host.buildSystem();
        if (current == null || build == null) {
            return BuildResult.failed("compile", text("swing.viewer.noBuild",
                    "O projeto ainda nao foi carregado."));
        }
        if (host.settings().isIncrementalBuild()) {
            IncrementalJavaBuilder builder = new IncrementalJavaBuilder(current,
                    host::buildSystem, host::projectJdk);
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
        javax.swing.SwingUtilities.invokeLater(() -> host.openAt(file, Math.max(0, line - 1), 0));
    }

    @Override
    public void openSourceAt(Path file, int line, int column) {
        javax.swing.SwingUtilities.invokeLater(() -> host.openAt(file, Math.max(0, line - 1), Math.max(0, column)));
    }

    @Override
    public String sourceText(Path file) {
        return host.readCurrentText(file);
    }

    @Override
    public boolean applySource(Path file, String expected, String updated) {
        return Boolean.TRUE.equals(UiThreads.onUi(() -> {
            IdeEditorContext editor = host.getEditor(file, true);
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
            JavaLanguageServer lsp = host.languageServer();
            if (lsp != null) {
                lsp.changeDocument(file, editor.getText());
            }
            editor.refreshDiagnostics();
            return true;
        }));
    }

    @Override
    public boolean renameSymbol(Path file, String text, int offset, String newName) {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null || text == null) {
            return false;
        }
        Position position = TextOffsets.position(text, offset);
        IdeWorkspaceEdit edit = lsp.renameWorkspace(file, text, position.line(), position.col(), newName);
        if (edit == null || edit.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(UiThreads.onUi(() -> host.sourceActions().applyWorkspaceEdit(lsp, edit)));
    }

    @Override
    public BuildResult compileShadow(JavaModule module, Path source, Path outputDir, Consumer<String> output) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return BuildResult.failed("javac", text("swing.viewer.noBuild", "O projeto ainda nao foi carregado."));
        }
        return new IncrementalJavaBuilder(current, host::buildSystem,
                host::projectJdk).compileDetached(module, List.of(source), outputDir, output);
    }
}
