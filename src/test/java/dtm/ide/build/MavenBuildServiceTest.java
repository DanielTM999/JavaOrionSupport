package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenBuildServiceTest {

    @TempDir
    Path root;

    @Test
    void commandForcesColorAfterBatchMode() {
        JavaModule module = rootModule();
        MavenBuildService service = service(descriptor(module));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, module));

        assertEquals(List.of(executable().toString(), "-B", "-Dstyle.color=always", "compile"),
                command);
    }

    @Test
    void aSubmoduleIsBuiltWithProjectListAndAlsoMake() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api));

        assertTrue(command.contains("-pl"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void alsoMakeCanBeTurnedOffForASingleModule() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withAlsoMake(false));

        assertTrue(command.contains("-pl"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertFalse(command.contains("-am"));
    }

    @Test
    void severalModulesAreSelectedInASingleReactor() {
        JavaModule core = module(root.resolve("core"));
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), core, api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withModules(List.of(core, api)));

        assertTrue(command.contains("-pl"));
        assertEquals("core,api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void theAggregatorIsDroppedFromTheModuleSelection() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withModules(List.of(rootModule(), api)));

        assertEquals("api", command.get(command.indexOf("-pl") + 1));
    }

    @Test
    void selectedDependencyRefreshUsesTargetedPurgeAndForcesResolution() {
        MavenBuildService service = service(descriptor(rootModule()));
        List<String> arguments = service.dependencyRefreshArguments(
                DependencyCoordinate.of("com.github.demo", "library", "1.0"));

        assertTrue(arguments.contains("-U"));
        assertTrue(arguments.contains("dependency:purge-local-repository"));
        assertTrue(arguments.contains("-DmanualInclude=com.github.demo:library"));
        assertTrue(arguments.contains("dependency:resolve"));
    }

    @Test
    void projectRefreshDoesNotRestrictThePurgeToOneArtifact() {
        List<String> arguments = service(descriptor(rootModule())).dependencyRefreshArguments(null);

        assertFalse(arguments.stream().anyMatch(argument -> argument.startsWith("-DmanualInclude=")));
    }
    private MavenBuildService service(JavaProjectDescriptor descriptor) {
        BuildToolProvisioner provisioner = new BuildToolProvisioner(null, null) {
            @Override
            public BuildTool ensureMaven(JavaProjectDescriptor ignored,
                                         DownloadProgressListener listener) {
                return new BuildTool(executable(), ToolOrigin.PATH);
            }
        };
        return new MavenBuildService(descriptor, provisioner, null, null);
    }

    private Path executable() {
        return root.resolve("mvn.cmd");
    }

    private JavaProjectDescriptor descriptor(JavaModule... modules) {
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(modules), true,
                false, 21, null);
    }

    private JavaModule rootModule() {
        return module(root);
    }

    private JavaModule module(Path moduleRoot) {
        return new JavaModule(moduleRoot, moduleRoot.getFileName().toString(), "com.example",
                moduleRoot.getFileName().toString(), "jar",
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                moduleRoot.resolve("target/classes"));
    }
}
