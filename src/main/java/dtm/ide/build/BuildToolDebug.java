package dtm.ide.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BuildToolDebug {

    public enum Target {
        MAVEN_JVM, SPRING_BOOT, QUARKUS, TESTS, JAVA_EXEC, NONE
    }

    public record Plan(Target target, List<String> arguments, Map<String, String> environment) {

        public Plan {
            target = target == null ? Target.NONE : target;
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            environment = environment == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(environment));
        }

        public boolean debuggable() {
            return target != Target.NONE;
        }
    }

    private static final Set<String> MAVEN_TEST_PHASES = Set.of(
            "test", "prepare-package", "package", "pre-integration-test", "integration-test",
            "post-integration-test", "verify", "install", "deploy");

    private static final Set<String> GRADLE_EXEC_TASKS = Set.of("run", "bootrun", "boottestrun");

    private static final Set<String> GRADLE_TEST_TASKS = Set.of("test", "check", "build");

    private BuildToolDebug() {
    }

    public static String listenAgent(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:" + port;
    }

    public static Plan maven(List<String> goals, List<String> extraArguments, int listenPort,
                             Map<String, String> environment) {
        List<String> tokens = new ArrayList<>(goals == null ? List.of() : goals);
        tokens.addAll(extraArguments == null ? List.of() : extraArguments);
        String agent = listenAgent(listenPort);

        if (tokens.stream().anyMatch(BuildToolDebug::isSpringBootRun)) {
            return new Plan(Target.SPRING_BOOT,
                    List.of("-Dspring-boot.run.jvmArguments=" + agent), Map.of());
        }
        if (tokens.stream().anyMatch(BuildToolDebug::isQuarkusDev)) {
            return new Plan(Target.QUARKUS, List.of("-Ddebug=false", "-Djvm.args=" + agent),
                    Map.of());
        }
        if (reachesTests(tokens) && !skipsTests(tokens)) {
            return new Plan(Target.TESTS,
                    List.of("-DforkCount=1", "-Dmaven.surefire.debug=" + agent), Map.of());
        }
        String current = environment == null ? "" : environment.getOrDefault("MAVEN_OPTS", "");
        String options = current == null || current.isBlank() ? agent : current.trim() + " " + agent;
        return new Plan(Target.MAVEN_JVM, List.of(), Map.of("MAVEN_OPTS", options));
    }

    public static Plan gradle(List<String> tasks, List<String> extraArguments, int listenPort) {
        List<String> tokens = new ArrayList<>(tasks == null ? List.of() : tasks);
        tokens.addAll(extraArguments == null ? List.of() : extraArguments);
        List<String> names = gradleTaskNames(tokens);
        String agent = listenAgent(listenPort);
        if (names.stream().anyMatch(GRADLE_EXEC_TASKS::contains)) {
            return new Plan(Target.JAVA_EXEC, initScript("orion-gradle-exec-debug", """
                    allprojects {
                        tasks.withType(org.gradle.api.tasks.JavaExec).configureEach {
                            jvmArgs '%s'
                        }
                    }
                    """.formatted(agent)), Map.of());
        }
        boolean testTask = names.stream()
                .anyMatch(name -> GRADLE_TEST_TASKS.contains(name) || name.endsWith("test"));
        if (testTask && !gradleSkipsTests(tokens)) {
            return new Plan(Target.TESTS, gradleTestDebugArguments(agent), Map.of());
        }
        return new Plan(Target.NONE, List.of(), Map.of());
    }

    public static List<String> gradleTestDebugArguments(String agent) {
        return initScript("orion-gradle-test-debug", """
                allprojects {
                    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                        maxParallelForks = 1
                        forkEvery = 0
                        jvmArgs '%s'
                    }
                }
                """.formatted(agent));
    }

    private static List<String> initScript(String prefix, String content) {
        try {
            Path script = Files.createTempFile(prefix, ".gradle");
            script.toFile().deleteOnExit();
            Files.writeString(script, content);
            return List.of("--init-script", script.toString());
        } catch (Exception error) {
            throw new IllegalStateException("Nao foi possivel preparar o script de debug do Gradle.",
                    error);
        }
    }

    private static boolean isSpringBootRun(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("spring-boot:run") || value.equals("spring-boot:test-run")
                || (value.contains("spring-boot-maven-plugin")
                && (value.endsWith(":run") || value.endsWith(":test-run")));
    }

    private static boolean isQuarkusDev(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("quarkus:dev")
                || (value.contains("quarkus-maven-plugin") && value.endsWith(":dev"));
    }

    private static boolean reachesTests(List<String> tokens) {
        for (String token : tokens) {
            String value = token.toLowerCase(Locale.ROOT);
            if (MAVEN_TEST_PHASES.contains(value) || value.equals("surefire:test")
                    || (value.contains("maven-surefire-plugin") && value.endsWith(":test"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean skipsTests(List<String> tokens) {
        for (String token : tokens) {
            String value = token.trim();
            if (value.equals("-DskipTests") || value.equals("-DskipTests=true")
                    || value.equals("-Dmaven.test.skip") || value.equals("-Dmaven.test.skip=true")) {
                return true;
            }
        }
        return false;
    }

    private static boolean gradleSkipsTests(List<String> arguments) {
        if (arguments == null) {
            return false;
        }
        for (int i = 0; i < arguments.size() - 1; i++) {
            String flag = arguments.get(i);
            if ((flag.equals("-x") || flag.equals("--exclude-task"))
                    && gradleTaskName(arguments.get(i + 1)).equals("test")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gradleTaskNames(List<String> tokens) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).trim();
            if (token.equals("-x") || token.equals("--exclude-task")) {
                i++;
            } else if (!token.isEmpty() && !token.startsWith("-")) {
                names.add(gradleTaskName(token));
            }
        }
        return names;
    }

    private static String gradleTaskName(String task) {
        String value = task == null ? "" : task.trim();
        int colon = value.lastIndexOf(':');
        return (colon >= 0 ? value.substring(colon + 1) : value).toLowerCase(Locale.ROOT);
    }
}
