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
    void reactorClasspathRunsTheCompilePhaseWithoutCompiling() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.reactorClasspathCommand(api, "runtime");

        assertTrue(command.indexOf("compile") < command.indexOf("dependency:build-classpath"));
        assertTrue(command.contains("-Dmaven.main.skip=true"));
        assertTrue(command.contains("-Dmaven.resources.skip=true"));
        assertFalse(command.contains("-Dmaven.test.skip=true"));
        assertTrue(command.contains("-Dmdep.includeScope=runtime"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void reactorTestClasspathRunsTheTestCompilePhaseWithoutCompiling() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.reactorClasspathCommand(api, "test");

        assertTrue(command.contains("test-compile"));
        assertFalse(command.contains("compile"));
        assertTrue(command.contains("-Dmaven.main.skip=true"));
        assertTrue(command.contains("-Dmaven.test.skip=true"));
        assertTrue(command.contains("-Dmdep.includeScope=test"));
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
    @Test
    void aPersistedClasspathIsReusedUntilAPomChanges() throws Exception {
        JavaModule module = rootModule();
        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path jar = java.nio.file.Files.writeString(root.resolve("dependency.jar"), "jar");
        MavenBuildService service = service(descriptor(module));
        Path persisted = root.resolve(".orion/classpath/" + module.artifactId() + "-runtime.classpath");
        java.nio.file.Files.createDirectories(persisted.getParent());
        java.nio.file.Files.writeString(persisted,
                service.persistentFingerprint(module, "runtime") + "\n" + jar + "\n");

        assertEquals(java.util.Optional.of(module.outputDir() + java.io.File.pathSeparator + jar),
                service.resolveRuntimeClasspath(module));

        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project><!-- nova --></project>");
        MavenBuildService afterPomChange = service(descriptor(module));

        assertTrue(afterPomChange.resolveRuntimeClasspath(module).isEmpty());
    }

    @Test
    void toolCommandOptionsReachTheMavenProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        java.nio.file.Files.writeString(executable(),
                "@echo ARGS %*\r\n@echo OPTS %MAVEN_OPTS%\r\n");
        MavenBuildService service = service(descriptor(rootModule()));
        List<String> output = new java.util.ArrayList<>();

        BuildResult result = service.executeToolCommand(null, List.of("exec:java"),
                new BuildCommand.Options(List.of(), List.of("-DskipTests"), true,
                        java.util.Map.of("MAVEN_OPTS", "-Dorion.debug=on")), output::add);

        assertTrue(result.successful(), String.join("\n", output));
        String args = output.stream().filter(line -> line.startsWith("ARGS")).findFirst().orElse("");
        assertTrue(args.contains("exec:java"), args);
        assertTrue(args.contains("-o"), args);
        assertTrue(args.contains("-DskipTests"), args);
        assertTrue(output.contains("OPTS -Dorion.debug=on"), String.join("\n", output));
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
