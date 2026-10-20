package dtm.ide.lsp.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ProjectModelSupport {

    boolean updateProjectConfiguration(Path projectRoot);

    void resynchronizeAfterProjectUpdate();

    void projectConfigurationUpdate();

    default void setConfigurationRoots(List<Path> roots) {
    }

    String buildWorkspace(boolean fullBuild);

    String buildWorkspace(boolean fullBuild, StatusListener progress);

    Optional<String> runtimeClasspath(Path projectOrSource);
}
