package dtm.ide.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolDebugTest {

    private static final String AGENT =
            "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:5123";

    @Test
    void theAgentConnectsBackToTheIde() {
        assertEquals(AGENT, BuildToolDebug.listenAgent(5123));
    }

    @Test
    void springBootRunDebugsTheForkedApplication() {
        BuildToolDebug.Plan plan = BuildToolDebug.maven(List.of("clean", "spring-boot:run"),
                List.of(), 5123, Map.of());

        assertEquals(BuildToolDebug.Target.SPRING_BOOT, plan.target());
        assertEquals(List.of("-Dspring-boot.run.jvmArguments=" + AGENT), plan.arguments());
        assertTrue(plan.environment().isEmpty());
    }

    @Test
    void aFullyQualifiedSpringBootGoalIsRecognized() {
        BuildToolDebug.Plan plan = BuildToolDebug.maven(
                List.of("org.springframework.boot:spring-boot-maven-plugin:3.3.0:run"),
                List.of(), 5123, Map.of());

        assertEquals(BuildToolDebug.Target.SPRING_BOOT, plan.target());
    }

    @Test
    void quarkusDevReplacesItsOwnDebugServer() {
        BuildToolDebug.Plan plan = BuildToolDebug.maven(List.of("quarkus:dev"), List.of(), 5123,
                Map.of());

        assertEquals(BuildToolDebug.Target.QUARKUS, plan.target());
        assertEquals(List.of("-Ddebug=false", "-Djvm.args=" + AGENT), plan.arguments());
    }

    @Test
    void phasesThatRunTestsDebugTheSurefireFork() {
        for (String phase : List.of("test", "package", "verify", "install", "surefire:test")) {
            BuildToolDebug.Plan plan = BuildToolDebug.maven(List.of("clean", phase), List.of(),
                    5123, Map.of());

            assertEquals(BuildToolDebug.Target.TESTS, plan.target(), phase);
            assertEquals(List.of("-DforkCount=1", "-Dmaven.surefire.debug=" + AGENT),
                    plan.arguments(), phase);
        }
    }

    @Test
    void skippingTestsFallsBackToTheMavenJvm() {
        BuildToolDebug.Plan fromArguments = BuildToolDebug.maven(List.of("install"),
                List.of("-DskipTests"), 5123, Map.of());
        BuildToolDebug.Plan fromGoals = BuildToolDebug.maven(
                List.of("install", "-Dmaven.test.skip=true"), List.of(), 5123, Map.of());

        assertEquals(BuildToolDebug.Target.MAVEN_JVM, fromArguments.target());
        assertEquals(BuildToolDebug.Target.MAVEN_JVM, fromGoals.target());
    }

    @Test
    void inProcessGoalsDebugTheMavenJvmThroughMavenOpts() {
        BuildToolDebug.Plan plan = BuildToolDebug.maven(List.of("exec:java"), List.of(), 5123,
                Map.of());

        assertEquals(BuildToolDebug.Target.MAVEN_JVM, plan.target());
        assertTrue(plan.arguments().isEmpty());
        assertEquals(Map.of("MAVEN_OPTS", AGENT), plan.environment());
    }

    @Test
    void existingMavenOptsAreKept() {
        BuildToolDebug.Plan plan = BuildToolDebug.maven(List.of("compile"), List.of(), 5123,
                Map.of("MAVEN_OPTS", " -Xmx2g "));

        assertEquals("-Xmx2g " + AGENT, plan.environment().get("MAVEN_OPTS"));
    }

    @Test
    void gradleRunTasksInjectTheAgentIntoJavaExec() throws Exception {
        BuildToolDebug.Plan plan = BuildToolDebug.gradle(List.of(":app:bootRun"), List.of(), 5123);

        assertEquals(BuildToolDebug.Target.JAVA_EXEC, plan.target());
        assertEquals("--init-script", plan.arguments().getFirst());
        String script = Files.readString(Path.of(plan.arguments().get(1)));
        assertTrue(script.contains("JavaExec"));
        assertTrue(script.contains(AGENT));
    }

    @Test
    void gradleTestTasksInjectTheAgentIntoTheTestFork() throws Exception {
        BuildToolDebug.Plan plan = BuildToolDebug.gradle(List.of("clean", "test"), List.of(), 5123);

        assertEquals(BuildToolDebug.Target.TESTS, plan.target());
        String script = Files.readString(Path.of(plan.arguments().get(1)));
        assertTrue(script.contains("org.gradle.api.tasks.testing.Test"));
        assertTrue(script.contains("maxParallelForks = 1"));
    }

    @Test
    void gradleTasksWithoutAForkedJvmAreNotDebuggable() {
        assertFalse(BuildToolDebug.gradle(List.of("clean", "dependencies"), List.of(), 5123)
                .debuggable());
        assertFalse(BuildToolDebug.gradle(List.of("build"), List.of("-x", "test"), 5123)
                .debuggable());
    }
}
