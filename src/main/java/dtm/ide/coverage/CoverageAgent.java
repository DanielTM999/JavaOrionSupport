package dtm.ide.coverage;

import java.nio.file.Path;
import java.util.List;

public final class CoverageAgent {

    public static final String COVERAGE_DIR = ".orion/coverage";
    public static final String EXEC_FILE_NAME = "jacoco.exec";

    private CoverageAgent() {
    }

    public static Path execFileFor(Path moduleRoot) {
        if (moduleRoot == null) {
            return null;
        }
        return moduleRoot.toAbsolutePath().normalize()
                .resolve(".orion").resolve("coverage").resolve(EXEC_FILE_NAME);
    }

    public static List<String> mavenArguments(Path agentJar, Path execFile) {
        if (agentJar == null || execFile == null) {
            return List.of();
        }
        return List.of(
                "-DargLine=" + agentArgument(agentJar, execFile, true),
                "-Djacoco.skip=true");
    }

    public static String gradleInitScript(Path agentJar, Path execFile) {
        if (agentJar == null || execFile == null) {
            return "";
        }
        return """
                allprojects {
                    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                        jvmArgs '%s'
                        extensions.findByName('jacoco')?.enabled = false
                    }
                }
                """.formatted(agentArgument(agentJar, execFile, false));
    }

    public static String agentArgument(Path agentJar, Path execFile, boolean quotePaths) {
        String agent = pathOf(agentJar, quotePaths);
        String destination = pathOf(execFile, quotePaths);
        if (quotePaths) {
            agent = "\"" + agent + "\"";
            destination = "\"" + destination + "\"";
        }
        return "-javaagent:" + agent + "=destfile=" + destination + ",append=true";
    }

    static String pathOf(Path path, boolean nativeSeparators) {
        String value = path.toAbsolutePath().normalize().toString();
        return nativeSeparators ? value : value.replace(java.io.File.separatorChar, '/');
    }
}
