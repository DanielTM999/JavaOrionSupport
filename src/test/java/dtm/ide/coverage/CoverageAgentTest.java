package dtm.ide.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageAgentTest {

    @Test
    void execFileGoesUnderTheOrionCoverageDirectory(@TempDir Path root) {
        Path exec = CoverageAgent.execFileFor(root);

        assertEquals(CoverageAgent.EXEC_FILE_NAME, exec.getFileName().toString());
        assertEquals("coverage", exec.getParent().getFileName().toString());
        assertEquals(".orion", exec.getParent().getParent().getFileName().toString());
        assertTrue(exec.startsWith(root.toAbsolutePath().normalize()));
    }

    @Test
    void execFileIsNullWithoutModuleRoot() {
        assertNull(CoverageAgent.execFileFor(null));
    }

    @Test
    void mavenArgumentsCarryTheAgentAndSkipTheProjectPlugin(@TempDir Path root) {
        Path agent = root.resolve("jacocoagent.jar");
        Path exec = CoverageAgent.execFileFor(root);

        List<String> arguments = CoverageAgent.mavenArguments(agent, exec);

        assertEquals(2, arguments.size());
        assertTrue(arguments.get(0).startsWith("-DargLine=-javaagent:"));
        assertTrue(arguments.get(0).contains("destfile="));
        assertTrue(arguments.get(0).endsWith(",append=true"));
        assertEquals("-Djacoco.skip=true", arguments.get(1));
    }

    @Test
    void mavenArgumentsQuotePathsSoSurefireDoesNotSplitOnSpaces(@TempDir Path root) {
        Path agent = root.resolve("with space").resolve("jacocoagent.jar");
        Path exec = root.resolve("with space").resolve("jacoco.exec");

        String argLine = CoverageAgent.mavenArguments(agent, exec).get(0);

        assertTrue(argLine.contains("-javaagent:\"" + agent.toAbsolutePath().normalize() + "\""));
        assertTrue(argLine.contains("destfile=\"" + exec.toAbsolutePath().normalize() + "\""));
    }

    @Test
    void mavenArgumentsAreEmptyWithoutAgentOrDestination(@TempDir Path root) {
        assertTrue(CoverageAgent.mavenArguments(null, CoverageAgent.execFileFor(root)).isEmpty());
        assertTrue(CoverageAgent.mavenArguments(root.resolve("a.jar"), null).isEmpty());
    }

    @Test
    void gradleInitScriptDisablesTheProjectJacocoExtension(@TempDir Path root) {
        Path agent = root.resolve("jacocoagent.jar");
        Path exec = CoverageAgent.execFileFor(root);

        String script = CoverageAgent.gradleInitScript(agent, exec);

        assertTrue(script.contains("tasks.withType(org.gradle.api.tasks.testing.Test)"));
        assertTrue(script.contains("jvmArgs '-javaagent:"));
        assertTrue(script.contains("extensions.findByName('jacoco')?.enabled = false"));
        assertTrue(script.contains(",append=true'"));
    }

    @Test
    void gradleInitScriptUsesForwardSlashesSoGroovyDoesNotEscapeThem(@TempDir Path root) {
        Path agent = root.resolve("nested").resolve("jacocoagent.jar");

        String argument = CoverageAgent.agentArgument(agent, CoverageAgent.execFileFor(root), false);

        assertFalse(argument.indexOf('\\') >= 0);
        assertTrue(argument.contains("/nested/jacocoagent.jar"));
    }

    @Test
    void gradleInitScriptIsEmptyWithoutAgentOrDestination(@TempDir Path root) {
        assertTrue(CoverageAgent.gradleInitScript(null, CoverageAgent.execFileFor(root)).isEmpty());
        assertTrue(CoverageAgent.gradleInitScript(root.resolve("a.jar"), null).isEmpty());
    }
}
