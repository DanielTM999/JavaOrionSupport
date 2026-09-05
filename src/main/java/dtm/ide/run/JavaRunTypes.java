package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;

import java.util.List;
import java.util.Set;

/**
 * Identificadores e chaves de propriedade das configuracoes de execucao Java.
 *
 * <p>Os identificadores e as chaves existentes sao preservados: {@code java.run},
 * {@code java.springBoot} e {@code java.test} continuam com o mesmo nome e as mesmas
 * propriedades gravadas pelas versoes anteriores do plugin.</p>
 */
public final class JavaRunTypes {

    private JavaRunTypes() {
    }

    public static final String APPLICATION = "java.run";
    public static final String SPRING_BOOT = "java.springBoot";
    public static final String JAR = "java.jar";
    public static final String MAVEN = "java.maven";
    public static final String GRADLE = "java.gradle";
    public static final String TEST = "java.test";
    public static final String REMOTE = "java.remote";

    /** Configuracao sintetica do Workbench para "arquivo atual". */
    public static final String CURRENT_FILE = "current_file";

    public static final Set<String> ALL = Set.of(
            APPLICATION, SPRING_BOOT, JAR, MAVEN, GRADLE, TEST, REMOTE);

    /** Tipos cujo lancamento acontece em uma JVM local iniciada pelo plugin. */
    public static final Set<String> LOCAL_JVM = Set.of(APPLICATION, SPRING_BOOT, JAR);

    /** Tipos delegados ao build tool do projeto. */
    public static final Set<String> BUILD_TOOL = Set.of(MAVEN, GRADLE, TEST);

    // Propriedades comuns -----------------------------------------------------
    public static final String MODULE = "module";
    public static final String JDK_HOME = "jdkHome";
    public static final String PROGRAM_ARGUMENTS = "programArguments";
    public static final String VM_OPTIONS = "vmOptions";
    public static final String WORKING_DIRECTORY = "workingDirectory";
    public static final String ENVIRONMENT = "environment";
    public static final String BUILD_BEFORE_RUN = "buildBeforeRun";
    public static final String BEFORE_LAUNCH_CHAIN = "beforeLaunchChain";


    public static final String MAIN_CLASS = "mainClass";
    public static final String SPRING_PROFILES = "springProfiles";
    public static final String SERVER_PORT = "serverPort";
    public static final String USE_TEST_CLASSPATH = "useTestClasspath";

    // JAR ---------------------------------------------------------------------
    public static final String JAR_PATH = "jarPath";
    public static final String JAR_SOURCE = "jarSource";

    public static final String JAR_SOURCE_PROJECT = "project";
    public static final String JAR_SOURCE_EXTERNAL = "external";

    public static boolean isExternalJar(java.util.Map<String, Object> properties) {
        Object raw = properties == null ? null : properties.get(JAR_SOURCE);
        return raw != null && JAR_SOURCE_EXTERNAL.equalsIgnoreCase(raw.toString().trim());
    }

    // Maven / Gradle ----------------------------------------------------------
    public static final String GOALS = "goals";
    public static final String TASKS = "tasks";
    public static final String PROFILES = "profiles";
    public static final String RUNNER_ARGUMENTS = "runnerArguments";
    public static final String OFFLINE = "offline";

    // Testes ------------------------------------------------------------------
    public static final String TEST_SCOPE = "testScope";
    public static final String TEST_TARGET = "testTarget";

    // Remote JVM --------------------------------------------------------------
    public static final String REMOTE_MODE = "remoteMode";
    public static final String REMOTE_HOST = "remoteHost";
    public static final String REMOTE_PORT = "remotePort";
    public static final String REMOTE_TIMEOUT = "remoteTimeout";

    public static final String REMOTE_MODE_ATTACH = "attach";
    public static final String REMOTE_MODE_LISTEN = "listen";

    public static final String DEFAULT_REMOTE_HOST = "127.0.0.1";
    public static final int DEFAULT_REMOTE_PORT = 5005;
    public static final int DEFAULT_REMOTE_TIMEOUT = 30_000;

    public static final List<String> DEFAULT_MAVEN_GOALS = List.of(
            "clean", "validate", "compile", "test", "package", "verify", "install",
            "spring-boot:run", "quarkus:dev", "exec:java");

    public static final List<String> DEFAULT_GRADLE_TASKS = List.of(
            "clean", "classes", "build", "assemble", "check", "test", "bootRun", "run");

    /** {@code true} quando o tipo pode iniciar uma sessao de depuracao. */
    public static boolean supportsDebug(String type) {
        return APPLICATION.equals(type) || SPRING_BOOT.equals(type) || JAR.equals(type)
                || TEST.equals(type) || REMOTE.equals(type) || CURRENT_FILE.equals(type);
    }

    /** {@code false} apenas para Remote JVM, que so existe em modo debug. */
    public static boolean supportsRun(String type) {
        return !REMOTE.equals(type);
    }

    /** Hot reload continua disponivel apenas nos lancamentos com classes locais. */
    public static boolean supportsHotReload(String type) {
        return APPLICATION.equals(type) || SPRING_BOOT.equals(type) || TEST.equals(type)
                || CURRENT_FILE.equals(type);
    }

    /** Build antes de executar ligado por padrao, exceto para JAR externo. */
    public static boolean buildBeforeRunDefault(String type) {
        return !JAR.equals(type) && !REMOTE.equals(type);
    }

    public static boolean isJavaType(RunConfigurationData configuration) {
        return configuration != null && ALL.contains(configuration.getType());
    }
}
