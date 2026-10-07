package dtm.ide.adapter;

import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.settings.JavaPluginSettings;
import dtm.ide.ui.JavaTodoPanel;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.function.Supplier;

public interface AdapterHost extends AdapterContext {
    String registerTodoPanel(JavaTodoPanel panel, Icon icon);
    void requestOpenToolPanel(String panelId);
    void openAt(Path file, int line, int column);
    JdkService jdkService();
    JdkInstallation projectJdk();
    void projectJdk(JdkInstallation jdk);
    void descriptor(JavaProjectDescriptor descriptor);
    long lifecycleTicket();
    long nextLifecycleTicket();
    boolean isCurrent(long ticket, Path root);
    <T> T timed(String label, Supplier<T> operation);
    void rebuildLexicalIndex(JavaProjectDescriptor descriptor);
    void refreshRunButtonsForCurrentFile();
    void reloadBuildToolsPanel();
    void reloadStructurePanel();
    void setStatusBarText(String text);
    DownloadProgressListener progressListener();
    void resolveProjectJdk(long ticket, Path root);
    JavaPluginSettings settings();
    JComponent dependencyLibrariesView();
    void syncProject();
    void requestRefreshCodeLenses(Path file);
    void refreshDiagnosticsOfOpenJavaEditors();
    BuildSystem buildSystem();
    java.util.Optional<String> runtimeClasspathOf(BuildSystem build, JavaModule module);
    String registerBottomPanel(String title, Icon icon, JComponent panel);
    void openWebBrowser(String url);
}
