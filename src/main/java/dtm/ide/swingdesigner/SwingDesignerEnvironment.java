package dtm.ide.swingdesigner;

import dtm.ide.build.BuildResult;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public interface SwingDesignerEnvironment {

    JavaProjectDescriptor descriptor();

    JdkInstallation projectJdk();

    Optional<String> runtimeClasspath(JavaModule module);

    BuildResult compile(JavaModule module, Consumer<String> output);

    void output(String line);

    default void openSource(Path file, int line) {
    }

    default void openSourceAt(Path file, int line, int column) {
        openSource(file, line);
    }

    default String sourceText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    default boolean applySource(Path file, String expected, String updated) {
        return false;
    }

    default boolean renameSymbol(Path file, String text, int offset, String newName) {
        return false;
    }

    default BuildResult compileShadow(JavaModule module, Path source, Path outputDir, Consumer<String> output) {
        return BuildResult.failed("javac", "Compilacao do designer indisponivel");
    }

    default Path cacheDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir"), "orion-swing-designer");
    }
}
