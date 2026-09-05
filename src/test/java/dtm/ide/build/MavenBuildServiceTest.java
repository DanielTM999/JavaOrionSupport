package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenBuildServiceTest {

    @TempDir
    Path root;

    @Test
    void commandForcesColorAfterBatchMode() {
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN,
                List.of(module), true, false, 21, null);
        Path executable = root.resolve("mvn.cmd");
        BuildToolProvisioner provisioner = new BuildToolProvisioner(null, null) {
            @Override
            public BuildTool ensureMaven(JavaProjectDescriptor ignored,
                                         DownloadProgressListener listener) {
                return new BuildTool(executable, ToolOrigin.PATH);
            }
        };
        MavenBuildService service = new MavenBuildService(descriptor, provisioner, null, null);

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, module));

        assertEquals(List.of(executable.toString(), "-B", "-Dstyle.color=always", "compile"),
                command);
    }
}
