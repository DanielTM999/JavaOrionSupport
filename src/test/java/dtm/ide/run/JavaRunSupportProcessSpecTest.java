package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Montagem dos comandos dos tipos introduzidos nas fases 2 e 3. */
class JavaRunSupportProcessSpecTest {

    @TempDir
    Path root;

    private JavaProjectDescriptor descriptor;
    private JdkInstallation jdk;
    private RecordingBuildSystem build;
    private JavaRunSupport runSupport;

    @BeforeEach
    void setUp() {
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
        descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(module),
                false, false, 21, null);
        jdk = new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.MANAGED);
        build = new RecordingBuildSystem();
        runSupport = new JavaRunSupport(() -> descriptor, () -> jdk, () -> build, line -> {
        });
    }

    // --- JAR -----------------------------------------------------------------

    @Test
    void aJarRunsWithTheJarFlagAndItsArguments() throws Exception {
        Path jar = jar("target/demo.jar");
        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_PATH, "target/demo.jar",
                JavaRunTypes.VM_OPTIONS, "-Xmx256m",
                JavaRunTypes.PROGRAM_ARGUMENTS, "--modo \"dois nomes\"")), 0);

        List<String> command = spec.command();
        int jarFlag = command.indexOf("-jar");

        assertTrue(command.getFirst().contains("java"));
        assertTrue(command.indexOf("-Xmx256m") < jarFlag, "opcoes da JVM vem antes de -jar");
        assertEquals(jar.toString(), command.get(jarFlag + 1));
        assertEquals(List.of("--modo", "dois nomes"),
                command.subList(jarFlag + 2, command.size()));
    }

    @Test
    void aDebuggedJarSuspendsUntilTheDebuggerAttaches() throws Exception {
        jar("target/demo.jar");
        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, "target/demo.jar")), 5005);

        String agent = spec.command().stream()
                .filter(part -> part.startsWith("-agentlib:jdwp"))
                .findFirst()
                .orElseThrow();

        assertTrue(agent.contains("suspend=y"));
        assertTrue(agent.contains("address=*:5005"));
        assertTrue(spec.command().indexOf(agent) < spec.command().indexOf("-jar"));
    }

    @Test
    void aJarPathIsResolvedAgainstTheModuleNotTheIdeDirectory() throws Exception {
        Path jar = jar("target/demo.jar");
        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, "target/demo.jar")), 0);

        assertTrue(spec.command().contains(jar.toString()));
        assertEquals(root, spec.workingDirectory());
    }

    @Test
    void anAbsolutePathToAJarOutsideTheProjectIsAccepted(@TempDir Path outside) throws Exception {
        Path external = outside.resolve("ferramenta.jar");
        Files.writeString(external, "");

        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, external.toString())), 0);

        assertEquals(external.toString(), spec.command().get(spec.command().indexOf("-jar") + 1),
                "um JAR de fora do projeto roda pelo caminho absoluto informado");
    }

    @Test
    void aProjectJarUsesTheModuleAsWorkingDirectory(@TempDir Path outside) throws Exception {
        Path external = outside.resolve("ferramenta.jar");
        Files.writeString(external, "");

        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, external.toString())), 0);

        assertEquals(root, spec.workingDirectory());
    }

    @Test
    void anExternalJarRunsFromItsOwnFolder(@TempDir Path outside) throws Exception {
        Path external = outside.resolve("ferramenta.jar");
        Files.writeString(external, "");

        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_SOURCE, JavaRunTypes.JAR_SOURCE_EXTERNAL,
                JavaRunTypes.JAR_PATH, external.toString())), 0);

        assertEquals(outside, spec.workingDirectory(),
                "um JAR de fora roda como se chamado pelo terminal, na pasta dele");
    }

    @Test
    void anExplicitWorkingDirectoryStillWinsForAnExternalJar(@TempDir Path outside)
            throws Exception {
        Path external = outside.resolve("ferramenta.jar");
        Files.writeString(external, "");

        ProcessSpec spec = spec(configuration(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_SOURCE, JavaRunTypes.JAR_SOURCE_EXTERNAL,
                JavaRunTypes.JAR_PATH, external.toString(),
                JavaRunTypes.WORKING_DIRECTORY, root.toString())), 0);

        assertEquals(root, spec.workingDirectory());
    }

    @Test
    void aMissingJarStopsTheLaunchWithAClearMessage() {
        assertThrows(IllegalStateException.class, () -> spec(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, "target/ausente.jar")), 0));
    }

    // --- Maven e Gradle ------------------------------------------------------

    @Test
    void mavenReusesTheBuildServiceCommandAssembly() {
        ProcessSpec spec = spec(configuration(JavaRunTypes.MAVEN, Map.of(
                JavaRunTypes.GOALS, "clean install",
                JavaRunTypes.PROFILES, "dev, ci",
                JavaRunTypes.OFFLINE, "true",
                JavaRunTypes.RUNNER_ARGUMENTS, "-DskipTests")), 0);

        assertEquals(List.of("clean", "install"), build.lastGoals);
        assertEquals(List.of("dev", "ci"), build.lastOptions.profiles());
        assertTrue(build.lastOptions.offline());
        assertEquals(List.of("-DskipTests"), build.lastOptions.extraArguments());
        assertEquals(jdk.home().toString(), spec.environment().get("JAVA_HOME"));
    }

    @Test
    void gradleSendsTasksInsteadOfGoals() {
        JavaProjectDescriptor gradleProject = new JavaProjectDescriptor(root,
                JavaProjectKind.GRADLE, descriptor.modules(), false, false, 21, null);
        JavaRunSupport gradleSupport = new JavaRunSupport(() -> gradleProject, () -> jdk,
                () -> build, line -> {
                });

        gradleSupport.processSpec(configuration(JavaRunTypes.GRADLE,
                Map.of(JavaRunTypes.TASKS, "clean bootRun")), gradleProject, 0);

        assertEquals(List.of("clean", "bootRun"), build.lastGoals);
    }

    @Test
    void debuggingAMavenConfigurationConnectsTheForkedApplicationBackToTheIde() {
        spec(configuration(JavaRunTypes.MAVEN, Map.of(
                JavaRunTypes.GOALS, "spring-boot:run")), 5123);

        assertEquals(List.of("-Dspring-boot.run.jvmArguments="
                        + "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:5123"),
                build.lastOptions.extraArguments());
    }

    @Test
    void debuggingAnInProcessMavenGoalUsesMavenOpts() {
        spec(configuration(JavaRunTypes.MAVEN, Map.of(
                JavaRunTypes.GOALS, "exec:java")), 5123);

        assertTrue(build.lastOptions.environment().get("MAVEN_OPTS")
                .endsWith("server=n,suspend=y,address=127.0.0.1:5123"));
    }

    @Test
    void debuggingGradleTasksWithoutAJvmFailsClearly() {
        JavaProjectDescriptor gradleProject = new JavaProjectDescriptor(root,
                JavaProjectKind.GRADLE, descriptor.modules(), false, false, 21, null);
        JavaRunSupport gradleSupport = new JavaRunSupport(() -> gradleProject, () -> jdk,
                () -> build, line -> {
                });

        assertThrows(IllegalStateException.class, () -> gradleSupport.processSpec(
                configuration(JavaRunTypes.GRADLE, Map.of(JavaRunTypes.TASKS, "dependencies")),
                gradleProject, 5123));
    }

    @Test
    void buildToolConfigurationsRequireAtLeastOneTarget() {
        assertThrows(IllegalStateException.class,
                () -> spec(configuration(JavaRunTypes.MAVEN, Map.of()), 0));
        assertThrows(IllegalStateException.class,
                () -> spec(configuration(JavaRunTypes.GRADLE, Map.of()), 0));
    }

    // --- Testes --------------------------------------------------------------

    @Test
    void aTestConfigurationRunsTheTestGoalWithTheScopeSelector() {
        spec(configuration(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "class",
                JavaRunTypes.TEST_TARGET, "com.exemplo.MinhaClasseTest")), 0);

        assertEquals(List.of("test"), build.lastGoals);
        assertTrue(build.lastOptions.extraArguments()
                .contains("-Dtest=com.exemplo.MinhaClasseTest"));
    }

    @Test
    void debuggingTestsForcesASingleSuspendedJvmOnMaven() {
        spec(configuration(JavaRunTypes.TEST, Map.of(JavaRunTypes.TEST_SCOPE, "all")), 5005);

        List<String> arguments = build.lastOptions.extraArguments();
        assertTrue(arguments.contains("-DforkCount=1"),
                "mais de um fork tornaria a porta de debug ambigua");
        assertTrue(arguments.stream().anyMatch(argument ->
                argument.startsWith("-Dmaven.surefire.debug=") && argument.contains("suspend=y")));
    }

    @Test
    void debuggingGradleTestsGoesThroughAnInitScript() {
        List<String> arguments = JavaRunSupport.testDebugArguments(true, 5005);

        assertEquals("--init-script", arguments.getFirst());
        Path script = Path.of(arguments.get(1));
        assertTrue(Files.isRegularFile(script));
    }

    @Test
    void runnerArgumentsComeAfterTheScopeSelectors() {
        spec(configuration(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "class",
                JavaRunTypes.TEST_TARGET, "com.exemplo.UmTest",
                JavaRunTypes.RUNNER_ARGUMENTS, "-Dgroups=lento")), 0);

        List<String> arguments = build.lastOptions.extraArguments();
        assertTrue(arguments.indexOf("-Dtest=com.exemplo.UmTest")
                < arguments.indexOf("-Dgroups=lento"));
    }

    // --- JDK e build antes de executar --------------------------------------

    @Test
    void anExplicitJdkThatDisappearedFailsInsteadOfFallingBack() {
        assertThrows(IllegalStateException.class, () -> spec(configuration(
                JavaRunTypes.APPLICATION, Map.of(
                        JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                        JavaRunTypes.JDK_HOME, root.resolve("jdk-removida").toString())), 0));
    }

    @Test
    void withoutAnExplicitJdkTheProjectJdkIsUsed() {
        ProcessSpec spec = spec(configuration(JavaRunTypes.APPLICATION,
                Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main")), 0);

        assertEquals(jdk.javaExecutable().toString(), spec.command().getFirst());
    }

    @Test
    void anExternalJarIsNotBuiltBeforeRunningByDefault() {
        runSupport.buildBeforeRun(configuration(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, "target/demo.jar")));

        assertTrue(build.actions.isEmpty(), "JAR externo nao dispara build por padrao");
    }

    @Test
    void anApplicationIsCompiledBeforeRunningByDefault() {
        runSupport.buildBeforeRun(configuration(JavaRunTypes.APPLICATION,
                Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main")));

        assertEquals(List.of(BuildSystem.BuildAction.COMPILE), build.actions);
    }

    @Test
    void aJarPackagesTheModuleWhenBuildBeforeRunIsEnabled() {
        runSupport.buildBeforeRun(configuration(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_PATH, "target/demo.jar",
                JavaRunTypes.BUILD_BEFORE_RUN, "true")));

        assertEquals(List.of(BuildSystem.BuildAction.PACKAGE), build.actions);
    }

    @Test
    void buildToolConfigurationsSkipThePreviousBuildStep() {
        runSupport.buildBeforeRun(configuration(JavaRunTypes.MAVEN,
                Map.of(JavaRunTypes.GOALS, "test")));
        runSupport.buildBeforeRun(configuration(JavaRunTypes.TEST, Map.of()));

        assertTrue(build.actions.isEmpty(), "o proprio lancamento ja passa pelo build tool");
    }

    @Test
    void disablingBuildBeforeRunSkipsTheCompilation() {
        runSupport.buildBeforeRun(configuration(JavaRunTypes.APPLICATION, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.BUILD_BEFORE_RUN, "false")));

        assertTrue(build.actions.isEmpty());
    }

    // --- Remote --------------------------------------------------------------

    @Test
    void remoteConfigurationsCannotBeStartedWithRun() {
        assertFalse(runSupport.launch(configuration(JavaRunTypes.REMOTE, Map.of(
                JavaRunTypes.REMOTE_HOST, "127.0.0.1",
                JavaRunTypes.REMOTE_PORT, "5005")), null).isAlive());
    }

    @Test
    void anInvalidConfigurationIsRefusedByTheLauncher() {
        assertFalse(runSupport.launch(configuration(JavaRunTypes.APPLICATION, Map.of()), null)
                .isAlive());
        assertTrue(build.actions.isEmpty(), "nada e compilado antes da validacao passar");
    }

    // --- Ambiente ------------------------------------------------------------

    @Test
    void theEnvironmentEditorAcceptsOneVariablePerLine() {
        ProcessSpec spec = spec(configuration(JavaRunTypes.APPLICATION, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.ENVIRONMENT, "APP_ENV=dev\nDB_HOST=localhost")), 0);

        assertEquals("dev", spec.environment().get("APP_ENV"));
        assertEquals("localhost", spec.environment().get("DB_HOST"));
    }

    private Path jar(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        return file;
    }

    private ProcessSpec spec(RunConfigurationData configuration, int debugPort) {
        return runSupport.processSpec(configuration, descriptor, debugPort);
    }

    private static RunConfigurationData configuration(String type, Map<String, String> properties) {
        return RunConfigurationData.builder()
                .type(type)
                .title("teste")
                .properties(new LinkedHashMap<>(properties))
                .build();
    }

    /** Build system que apenas registra o que foi pedido. */
    private final class RecordingBuildSystem implements BuildSystem {

        private final List<BuildAction> actions = new ArrayList<>();
        private List<String> lastGoals = List.of();
        private BuildCommand.Options lastOptions = BuildCommand.Options.none();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public BuildResult execute(BuildRequest request, Consumer<String> output) {
            actions.add(request.action());
            return new BuildResult(0, List.of(), Duration.ZERO, "fake");
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public Optional<String> resolveRuntimeClasspath(JavaModule module) {
            return Optional.of(root.resolve("target/classes").toString());
        }

        @Override
        public void invalidateClasspathCache() {
        }

        @Override
        public Optional<BuildCommand> toolCommand(JavaModule module, List<String> goals,
                                                  BuildCommand.Options options) {
            lastGoals = List.copyOf(goals);
            lastOptions = options;
            List<String> command = new ArrayList<>(List.of("mvn", "-B"));
            command.addAll(goals);
            command.addAll(options.extraArguments());
            return Optional.of(new BuildCommand(command, root, options.environment()));
        }
    }
}
