package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolCommandTest {

    @TempDir
    Path root;

    @Test
    void mavenReusesTheWrapperFlagsAndAppendsTheGoals() {
        BuildCommand command = maven(rootModule(), List.of("clean", "install"),
                BuildCommand.Options.none());

        assertEquals(List.of(root.resolve("mvnw").toString(), "-B", "-Dstyle.color=always",
                "clean", "install"), command.command());
        assertEquals(root, command.workingDirectory());
    }

    @Test
    void mavenSelectsTheModuleWithProjectListAndAlsoMake() {
        JavaModule api = module(root.resolve("api"));
        BuildCommand command = maven(api, List.of("test"), BuildCommand.Options.none());

        int projectList = command.command().indexOf("-pl");
        assertTrue(projectList > 0);
        assertEquals("api", command.command().get(projectList + 1));
        assertEquals("-am", command.command().get(projectList + 2));
    }

    @Test
    void mavenAppliesProfilesOfflineAndExtraArguments() {
        BuildCommand command = maven(rootModule(), List.of("verify"),
                new BuildCommand.Options(List.of("dev", "ci"), List.of("-DskipTests"), true,
                        Map.of()));

        assertTrue(command.command().contains("-Pdev,ci"));
        assertTrue(command.command().contains("-o"));
        assertEquals("-DskipTests", command.command().getLast());
    }

    @Test
    void mavenExportsJavaHomeFromTheSelectedJdk() {
        BuildCommand command = maven(rootModule(), List.of("test"), BuildCommand.Options.none());

        assertEquals(root.resolve("jdk").toString(), command.environment().get("JAVA_HOME"));
    }

    @Test
    void anExplicitJavaHomeIsNotOverwritten() {
        BuildCommand command = maven(rootModule(), List.of("test"),
                new BuildCommand.Options(List.of(), List.of(), false,
                        Map.of("JAVA_HOME", root.resolve("outra-jdk").toString())));

        assertEquals(root.resolve("outra-jdk").toString(), command.environment().get("JAVA_HOME"));
    }

    @Test
    void gradlePrefixesTasksWithTheModulePath() {
        BuildCommand command = gradle(module(root.resolve("api")), List.of("test"),
                BuildCommand.Options.none());

        assertTrue(command.command().contains(":api:test"));
    }

    @Test
    void gradleTasksOfTheRootProjectKeepTheirPlainName() {
        BuildCommand command = gradle(rootModule(), List.of("bootRun"),
                BuildCommand.Options.none());

        assertEquals(List.of(root.resolve("gradlew").toString(), "--console=plain", "bootRun"),
                command.command());
    }

    @Test
    void gradleAppliesOfflineAndExtraArguments() {
        BuildCommand command = gradle(rootModule(), List.of("build"),
                new BuildCommand.Options(List.of("prop=1"), List.of("--stacktrace"), true,
                        Map.of()));

        assertTrue(command.command().contains("--offline"));
        assertTrue(command.command().contains("-Pprop=1"));
        assertEquals("--stacktrace", command.command().getLast());
    }

    @Test
    void javacProjectsHaveNoToolCommand() {
        JavacBuildService service = new JavacBuildService(descriptor(JavaProjectKind.GRADLE),
                () -> jdk());

        assertFalse(service.toolCommand(rootModule(), List.of("test"),
                BuildCommand.Options.none()).isPresent());
    }

    private BuildCommand maven(JavaModule module, List<String> goals,
                               BuildCommand.Options options) {
        return new MavenBuildService(descriptor(JavaProjectKind.MAVEN),
                provisioner("mvnw"), this::jdk, null)
                .toolCommand(module, goals, options)
                .orElseThrow();
    }

    private BuildCommand gradle(JavaModule module, List<String> tasks,
                                BuildCommand.Options options) {
        return new GradleBuildService(descriptor(JavaProjectKind.GRADLE),
                provisioner("gradlew"), this::jdk, null)
                .toolCommand(module, tasks, options)
                .orElseThrow();
    }

    private JavaProjectDescriptor descriptor(JavaProjectKind kind) {
        return new JavaProjectDescriptor(root, kind,
                List.of(rootModule(), module(root.resolve("api"))), false, false, 21, null);
    }

    private JavaModule rootModule() {
        return module(root);
    }

    private JavaModule module(Path moduleRoot) {
        return new JavaModule(moduleRoot, moduleRoot.getFileName().toString(), "com.example",
                "demo", "jar", List.of(), List.of(), moduleRoot.resolve("target/classes"));
    }

    private JdkInstallation jdk() {
        return new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.MANAGED);
    }

    private BuildToolProvisioner provisioner(String executable) {
        Path tool = root.resolve(executable);
        return new BuildToolProvisioner(null, null) {
            @Override
            public BuildTool ensureMaven(JavaProjectDescriptor ignored,
                                         DownloadProgressListener listener) {
                return new BuildTool(tool, ToolOrigin.PATH);
            }

            @Override
            public BuildTool ensureGradle(JavaProjectDescriptor ignored,
                                          DownloadProgressListener listener) {
                return new BuildTool(tool, ToolOrigin.PATH);
            }
        };
    }
}
