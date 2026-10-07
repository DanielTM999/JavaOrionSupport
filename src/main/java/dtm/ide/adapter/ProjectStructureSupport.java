package dtm.ide.adapter;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.LanguageLevelEditor;
import dtm.ide.project.ProjectLayout;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.ui.JavaProjectStructurePanel;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectStructureSupport implements JavaProjectStructurePanel.Host {

    private final AdapterHost host;

    public ProjectStructureSupport(AdapterHost host) {
        this.host = host;
    }

    @Override
    public List<JavaModule> modules() {
        JavaProjectDescriptor current = host.descriptor();
        return current == null ? List.of() : current.modules();
    }

    @Override
    public List<JdkInstallation> installations() {
        return host.jdkService().available();
    }

    @Override
    public JdkInstallation projectJdk() {
        return host.projectJdk();
    }

    @Override
    public Integer languageLevel() {
        JavaProjectDescriptor current = host.descriptor();
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
        return host.projectRoot();
    }

    @Override
    public JComponent librariesView() {
        return host.dependencyLibrariesView();
    }

    @Override
    public List<JavaProjectStructurePanel.FolderRole> folders() {
        JavaProjectDescriptor current = host.descriptor();
        Path root = host.projectRoot();
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
        Path root = host.projectRoot();
        if (root == null) {
            return;
        }
        host.background().submit(() -> {
            if (jdk != null) {
                host.jdkService().selectHomeForProject(root, jdk.home());
                host.projectJdk(jdk);
            }
            if (level != null) {
                JavaProjectDescriptor current = host.descriptor();
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
            host.syncProject();
            host.reloadStructurePanel();
        });
    }
}
