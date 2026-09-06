package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRunSupportTest {

    @TempDir
    Path root;

    private JavaProjectDescriptor descriptor;
    private JdkInstallation jdk;
    private JavaRunSupport runSupport;

    @BeforeEach
    void setUp() {
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
        descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(module),
                true, true, 21, null);
        jdk = new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.MANAGED);
        runSupport = new JavaRunSupport(() -> descriptor, () -> jdk, FakeBuildSystem::new,
                line -> {
                });
    }

    @Test
    void buildsTheJvmCommandInTheRightOrder() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 0);

        List<String> parts = launch.command();
        assertTrue(parts.getFirst().contains("java"));
        assertEquals("-cp", parts.get(parts.size() - 3));
        assertEquals("com.example.Main", parts.getLast(),
                "a classe principal vem depois do classpath");
    }

    @Test
    void programArgumentsComeAfterTheMainClass() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN, Map.of(
                JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main",
                JavaRunSupport.PROPERTY_PROGRAM_ARGUMENTS, "--modo rapido")), 0);

        List<String> parts = launch.command();
        int mainIndex = parts.indexOf("com.example.Main");

        assertEquals("--modo", parts.get(mainIndex + 1));
        assertEquals("rapido", parts.get(mainIndex + 2));
    }

    @Test
    void vmOptionsComeBeforeTheMainClass() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN, Map.of(
                JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main",
                JavaRunSupport.PROPERTY_VM_OPTIONS, "-Xmx512m -Dfoo=bar")), 0);

        List<String> parts = launch.command();
        assertTrue(parts.indexOf("-Xmx512m") < parts.indexOf("com.example.Main"));
        assertTrue(parts.indexOf("-Dfoo=bar") < parts.indexOf("-cp"));
    }

    @Test
    void springProfilesBecomeASystemProperty() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_SPRING_BOOT,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Aplicacao",
                        JavaRunSupport.PROPERTY_PROFILES, "dev, local")), 0);

        assertTrue(launch.command().contains("-Dspring.profiles.active=dev,local"));
    }

    @Test
    void springBootRunsExposeTheActuatorEndpointsThePanelNeeds() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_SPRING_BOOT,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Aplicacao")), 0);

        assertTrue(launch.command().stream()
                .anyMatch(part -> part.startsWith("-Dmanagement.endpoints.web.exposure.include=")
                        && part.contains("beans") && part.contains("mappings")));
    }

    @Test
    void onlySpringBootRunsForceAnsiOutput() {
        JavaRunSupport.LaunchCommand spring = command(configuration(
                JavaRunSupport.TYPE_SPRING_BOOT,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Aplicacao")), 0);
        JavaRunSupport.LaunchCommand plain = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 0);

        assertTrue(spring.command().contains("-Dspring.output.ansi.enabled=always"));
        assertFalse(plain.command().contains("-Dspring.output.ansi.enabled=always"));
    }

    @Test
    void plainRunsDoNotTouchTheActuator() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 0);

        assertFalse(launch.command().stream()
                .anyMatch(part -> part.contains("management.endpoints")));
    }

    @Test
    void serverPortBecomesASystemProperty() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_SPRING_BOOT,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Aplicacao",
                        JavaRunSupport.PROPERTY_PORT, "9090")), 0);

        assertTrue(launch.command().contains("-Dserver.port=9090"));
    }

    @Test
    void debugRunsWaitForTheDebuggerToAttach() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 5005);

        String agent = launch.command().stream()
                .filter(part -> part.startsWith("-agentlib:jdwp"))
                .findFirst()
                .orElseThrow();

        assertTrue(agent.contains("suspend=y"),
                "sem suspend=y, breakpoints no startup nunca seriam atingidos");
        assertTrue(agent.contains("address=*:5005"));
        assertTrue(launch.command().indexOf(agent) < launch.command().indexOf("-cp"));
    }

    @Test
    void requiresAMainClass() {
        assertThrows(IllegalStateException.class,
                () -> command(configuration(JavaRunSupport.TYPE_RUN, Map.of()), 0));
    }

    @Test
    void recognizesTheCurrentFileConfiguration() {
        assertTrue(JavaRunSupport.isCurrentFileType(
                configuration(JavaRunSupport.TYPE_CURRENT_FILE, Map.of())));
        assertFalse(JavaRunSupport.isCurrentFileType(
                configuration(JavaRunSupport.TYPE_RUN, Map.of())));
    }

    @Test
    void applicationRunReportsItsInternalCompile() {
        assertEquals(Optional.of(BuildSystem.BuildAction.COMPILE),
                JavaRunSupport.buildBeforeRunAction(configuration(
                        JavaRunSupport.TYPE_RUN, Map.of())));
    }

    @Test
    void buildToolRunDoesNotReportAnInternalBuild() {
        assertTrue(JavaRunSupport.buildBeforeRunAction(configuration(
                JavaRunSupport.TYPE_MAVEN, Map.of(JavaRunTypes.GOALS, "spring-boot:run")))
                .isEmpty());
        assertTrue(JavaRunSupport.buildBeforeRunAction(configuration(
                JavaRunSupport.TYPE_GRADLE, Map.of(JavaRunTypes.TASKS, "bootRun")))
                .isEmpty());
    }

    @Test
    void disablingBuildBeforeRunDoesNotStartTheLoader() {
        assertTrue(JavaRunSupport.buildBeforeRunAction(configuration(
                JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunTypes.BUILD_BEFORE_RUN, Boolean.FALSE.toString())))
                .isEmpty());
    }

    @Test
    void jarRunReportsPackagingAsItsInternalBuild() {
        assertEquals(Optional.of(BuildSystem.BuildAction.PACKAGE),
                JavaRunSupport.buildBeforeRunAction(configuration(
                        JavaRunSupport.TYPE_JAR,
                        Map.of(JavaRunTypes.BUILD_BEFORE_RUN, Boolean.TRUE.toString()))));
    }

    @Test
    void javaHomePointsToTheSelectedJdk() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 0);

        assertEquals(jdk.home().toString(), launch.environment().get("JAVA_HOME"));
    }

    @Test
    void customEnvironmentEntriesAreAdded() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN, Map.of(
                JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main",
                JavaRunSupport.PROPERTY_ENVIRONMENT, "APP_ENV=dev, DB_HOST=localhost")), 0);

        assertEquals("dev", launch.environment().get("APP_ENV"));
        assertEquals("localhost", launch.environment().get("DB_HOST"));
    }

    @Test
    void theWorkingDirectoryDefaultsToTheModuleRoot() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), 0);

        assertEquals(root, launch.workingDirectory());
    }

    @Test
    void anExplicitWorkingDirectoryWins() {
        JavaRunSupport.LaunchCommand launch = command(configuration(JavaRunSupport.TYPE_RUN, Map.of(
                JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main",
                JavaRunSupport.PROPERTY_WORKING_DIRECTORY, root.resolve("outro").toString())), 0);

        assertEquals(root.resolve("outro"), launch.workingDirectory());
    }

    @Test
    void fallsBackToCompiledClassesWhenTheClasspathCannotBeResolved() {
        JavaRunSupport withoutClasspath = new JavaRunSupport(() -> descriptor, () -> jdk,
                () -> new FakeBuildSystem(true, false), line -> {
        });

        JavaRunSupport.LaunchCommand launch = withoutClasspath.buildCommand(
                configuration(JavaRunSupport.TYPE_RUN,
                        Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")),
                descriptor, jdk, 0);

        int classpathIndex = launch.command().indexOf("-cp");
        assertEquals(root.resolve("target/classes").toString(),
                launch.command().get(classpathIndex + 1));
    }

    @Test
    void argumentsRespectQuotes() {
        List<String> arguments = JavaRunSupport.splitArguments("--nome=\"Maria Silva\" --idade 30");

        assertEquals(3, arguments.size());
        assertEquals("--nome=Maria Silva", arguments.getFirst());
        assertEquals("--idade", arguments.get(1));
        assertEquals("30", arguments.get(2));
    }

    @Test
    void emptyArgumentsYieldNothing() {
        assertTrue(JavaRunSupport.splitArguments("").isEmpty());
        assertTrue(JavaRunSupport.splitArguments(null).isEmpty());
        assertTrue(JavaRunSupport.splitArguments("   ").isEmpty());
    }

    @Test
    void listsSplitOnCommasAndNewlines() {
        assertEquals(List.of("dev", "local"), JavaRunSupport.splitList("dev, local"));
        assertEquals(List.of("a", "b"), JavaRunSupport.splitList("a\nb"));
        assertTrue(JavaRunSupport.splitList("  ").isEmpty());
    }

    @Test
    void aFailedBuildCancelsTheRun() {
        JavaRunSupport failing = new JavaRunSupport(() -> descriptor, () -> jdk,
                () -> new FakeBuildSystem(false, true), line -> {
        });

        var handle = failing.launch(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), null);

        assertFalse(handle.isAlive(), "nada deve ser executado quando o build falha");
        assertTrue(handle.isReadonly());
    }

    @Test
    void missingJdkIsReportedInsteadOfCrashing() {
        JavaRunSupport withoutJdk = new JavaRunSupport(() -> descriptor, () -> null,
                FakeBuildSystem::new, line -> {
        });

        assertFalse(withoutJdk.launch(configuration(JavaRunSupport.TYPE_RUN,
                Map.of(JavaRunSupport.PROPERTY_MAIN_CLASS, "com.example.Main")), null).isAlive());
    }

    private JavaRunSupport.LaunchCommand command(RunConfigurationData configuration, int debugPort) {
        return runSupport.buildCommand(configuration, descriptor, jdk, debugPort);
    }

    private static RunConfigurationData configuration(String type, Map<String, String> properties) {
        Map<String, Object> values = new LinkedHashMap<>(properties);
        return RunConfigurationData.builder()
                .type(type)
                .title("teste")
                .properties(values)
                .build();
    }

    private final class FakeBuildSystem implements BuildSystem {

        private final boolean succeeds;
        private final boolean resolvesClasspath;

        FakeBuildSystem() {
            this(true, true);
        }

        FakeBuildSystem(boolean succeeds, boolean resolvesClasspath) {
            this.succeeds = succeeds;
            this.resolvesClasspath = resolvesClasspath;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public BuildResult execute(BuildRequest request, Consumer<String> output) {
            return new BuildResult(succeeds ? 0 : 1, List.of(), java.time.Duration.ZERO, "fake");
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
            return resolvesClasspath
                    ? Optional.of(root.resolve("target/classes") + java.io.File.pathSeparator
                        + root.resolve("lib/dep.jar"))
                    : Optional.empty();
        }

        @Override
        public void invalidateClasspathCache() {
        }
    }
}
