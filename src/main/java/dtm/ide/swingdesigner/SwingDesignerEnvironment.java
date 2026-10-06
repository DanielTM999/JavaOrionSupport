package dtm.ide.swingdesigner;

import dtm.ide.build.BuildResult;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public interface SwingDesignerEnvironment {

    JavaProjectDescriptor descriptor();

    JdkInstallation projectJdk();

    Optional<String> runtimeClasspath(JavaModule module);

    BuildResult compile(JavaModule module, Consumer<String> output);

    void output(String line);

    default Path cacheDirectory() {
        return Path.of(System.getProperty("user.home"), ".orion", "swing-designer");
    }
}
