package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.JavaDevelopmentBuildService;
import dtm.ide.run.chain.RunChainExecutor;
import dtm.ide.run.chain.RunChainHost;
import dtm.ide.run.chain.RunChainStep;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.test.TestScope;
import dtm.ide.test.TestSelectors;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class JavaRunSupport {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(JavaRunSupport.class, key, fallback);
    }

    public static final String TYPE_RUN = JavaRunTypes.APPLICATION;
    public static final String TYPE_SPRING_BOOT = JavaRunTypes.SPRING_BOOT;
    public static final String TYPE_JAR = JavaRunTypes.JAR;
    public static final String TYPE_MAVEN = JavaRunTypes.MAVEN;
    public static final String TYPE_GRADLE = JavaRunTypes.GRADLE;
    public static final String TYPE_TEST = JavaRunTypes.TEST;
    public static final String TYPE_REMOTE = JavaRunTypes.REMOTE;
    public static final String TYPE_CURRENT_FILE = JavaRunTypes.CURRENT_FILE;

    public static final String PROPERTY_MAIN_CLASS = JavaRunTypes.MAIN_CLASS;
    public static final String PROPERTY_MODULE = JavaRunTypes.MODULE;
    public static final String PROPERTY_PROGRAM_ARGUMENTS = JavaRunTypes.PROGRAM_ARGUMENTS;
    public static final String PROPERTY_VM_OPTIONS = JavaRunTypes.VM_OPTIONS;
    public static final String PROPERTY_WORKING_DIRECTORY = JavaRunTypes.WORKING_DIRECTORY;
    public static final String PROPERTY_ENVIRONMENT = JavaRunTypes.ENVIRONMENT;
    public static final String PROPERTY_PROFILES = JavaRunTypes.SPRING_PROFILES;
    public static final String PROPERTY_PORT = JavaRunTypes.SERVER_PORT;
    public static final String PROPERTY_TEST_CLASSPATH = JavaRunTypes.USE_TEST_CLASSPATH;

    private static final String ACTUATOR_ENDPOINTS = "health,beans,env,mappings,configprops";

    private final Supplier<JavaProjectDescriptor> descriptorSupplier;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final Supplier<BuildSystem> buildSupplier;
    private final Supplier<JavaDevelopmentBuildService> developmentBuildSupplier;
    private final Consumer<String> output;
    private volatile Supplier<RunChainHost> chainHost = () -> RunChainHost.EMPTY;
    private volatile Consumer<BuildResult> buildResultListener = result -> {
    };

    public JavaRunSupport(Supplier<JavaProjectDescriptor> descriptorSupplier,
                          Supplier<JdkInstallation> jdkSupplier,
                          Supplier<BuildSystem> buildSupplier,
                          Consumer<String> output) {
        this(descriptorSupplier, jdkSupplier, buildSupplier, () -> null, output);
    }

    public JavaRunSupport(Supplier<JavaProjectDescriptor> descriptorSupplier,
                          Supplier<JdkInstallation> jdkSupplier,
                          Supplier<BuildSystem> buildSupplier,
                          Supplier<JavaDevelopmentBuildService> developmentBuildSupplier,
                          Consumer<String> output) {
        this.descriptorSupplier = descriptorSupplier;
        this.jdkSupplier = jdkSupplier;
        this.buildSupplier = buildSupplier;
        this.developmentBuildSupplier = developmentBuildSupplier;
        this.output = output == null ? line -> {
        } : output;
    }

    public JavaRunSupport withChainHost(Supplier<RunChainHost> host) {
        this.chainHost = host == null ? () -> RunChainHost.EMPTY : host;
        return this;
    }

    public JavaRunSupport withBuildResultListener(Consumer<BuildResult> listener) {
        this.buildResultListener = listener == null ? result -> {
        } : listener;
        return this;
    }

    public static boolean isCurrentFileType(RunConfigurationData configuration) {
        return configuration != null && TYPE_CURRENT_FILE.equals(configuration.getType());
    }

    public record LaunchCommand(List<String> command, Path workingDirectory,
                                Map<String, String> environment) {

        public LaunchCommand {
            command = command == null ? List.of() : List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }

        public String display() {
            return String.join(" ", command);
        }

        public ProcessSpec toProcessSpec() {
            return ProcessSpec.of(command, workingDirectory, environment);
        }
    }

    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context) {
        return launch(configuration, context, 0);
    }

    public RunProcessHandle launch(RunConfigurationData configuration, RunExecutionContext context,
                                   int allocatedDebugPort) {
        JavaProjectDescriptor descriptor = descriptorSupplier.get();
        if (descriptor == null) {
            return failure(text("error.noProject", "Nenhum projeto Java aberto."));
        }
        String type = configuration == null ? "" : configuration.getType();
        if (JavaRunTypes.REMOTE.equals(type)) {
            return failure(text("error.remoteIsDebugOnly",
                    "Remote JVM so pode ser iniciado pelo botao Debug."));
        }

        JavaRunValidation.Report report = JavaRunValidation.validate(configuration,
                JavaRunValidation.Context.of(descriptor));
        if (!report.isValid()) {
            return failure(report.firstMessage());
        }

        ProcessSpec spec;
        try {
            Optional<String> buildFailure = buildBeforeRun(configuration);
            if (buildFailure.isPresent()) {
                return failure(buildFailure.get());
            }
            Optional<String> chainFailure = runBeforeLaunchChain(configuration);
            if (chainFailure.isPresent()) {
                return failure(chainFailure.get());
            }
            int port = allocatedDebugPort > 0 ? allocatedDebugPort : debugPort(context);
            spec = processSpec(configuration, descriptor, port);
        } catch (Exception e) {
            return failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        output.accept("> " + spec.display());
        try {
            return ProcessLauncher.launch(spec);
        } catch (Exception e) {
            log.warn("Falha ao iniciar a configuracao {}", type, e);
            return failure(text("error.launchFailed", "Falha ao iniciar:") + " " + e.getMessage());
        }
    }

    /** Monta a especificacao do processo de acordo com o tipo da configuracao. */
    public ProcessSpec processSpec(RunConfigurationData configuration,
                                   JavaProjectDescriptor descriptor, int debugPort) {
        String type = configuration == null ? "" : configuration.getType();
        return switch (type) {
            case JavaRunTypes.JAR -> jarSpec(configuration, descriptor, debugPort);
            case JavaRunTypes.MAVEN, JavaRunTypes.GRADLE ->
                    buildToolSpec(configuration, descriptor);
            case JavaRunTypes.TEST -> testSpec(configuration, descriptor, debugPort);
            default -> buildCommand(configuration, descriptor,
                    RunJdkResolver.resolve(propertiesOf(configuration), jdkSupplier),
                    debugPort).toProcessSpec();
        };
    }

    // --- Aplicacao e Spring Boot --------------------------------------------

    public LaunchCommand buildCommand(RunConfigurationData configuration,
                                      JavaProjectDescriptor descriptor,
                                      JdkInstallation jdk,
                                      int debugPort) {
        String mainClass = property(configuration, PROPERTY_MAIN_CLASS);
        if (mainClass.isBlank()) {
            throw new IllegalStateException(text("error.noMainClass",
                    "Nenhuma classe principal definida na configuracao de execucao."));
        }
        JavaModule module = moduleOf(configuration).orElseGet(descriptor::rootModule);

        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());

        if (debugPort > 0) {
            command.add(jdwpAgent(debugPort));
        }
        command.addAll(splitArguments(property(configuration, PROPERTY_VM_OPTIONS)));

        String port = property(configuration, PROPERTY_PORT);
        if (!port.isBlank()) {
            command.add("-Dserver.port=" + port);
        }
        List<String> profiles = splitList(property(configuration, PROPERTY_PROFILES));
        if (!profiles.isEmpty()) {
            command.add("-Dspring.profiles.active=" + String.join(",", profiles));
        }
        if (TYPE_SPRING_BOOT.equals(configuration.getType())) {
            command.add("-Dspring.output.ansi.enabled=always");
            command.add("-Dmanagement.endpoints.web.exposure.include=" + ACTUATOR_ENDPOINTS);
        }

        command.add("-cp");
        command.add(classpathOf(module, usesTestClasspath(configuration)));
        command.add(mainClass);
        command.addAll(splitArguments(property(configuration, PROPERTY_PROGRAM_ARGUMENTS)));

        return new LaunchCommand(command, workingDirectoryOf(configuration, module, descriptor),
                environmentOf(configuration, jdk));
    }

    // --- JAR -----------------------------------------------------------------

    private ProcessSpec jarSpec(RunConfigurationData configuration,
                                JavaProjectDescriptor descriptor, int debugPort) {
        JdkInstallation jdk = RunJdkResolver.resolve(propertiesOf(configuration), jdkSupplier);
        JavaModule module = moduleOf(configuration).orElseGet(descriptor::rootModule);
        Path jar = RunPaths.resolve(property(configuration, JavaRunTypes.JAR_PATH),
                        module, descriptor)
                .orElseThrow(() -> new IllegalStateException(text("error.jarRequired",
                        "Selecione o arquivo JAR a executar.")));
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(text("error.jarMissing",
                    "O arquivo JAR nao foi encontrado:") + " " + jar);
        }

        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());
        if (debugPort > 0) {
            command.add(jdwpAgent(debugPort));
        }
        command.addAll(splitArguments(property(configuration, PROPERTY_VM_OPTIONS)));
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(splitArguments(property(configuration, PROPERTY_PROGRAM_ARGUMENTS)));

        return ProcessSpec.of(command, jarWorkingDirectory(configuration, module, descriptor, jar),
                environmentOf(configuration, jdk));
    }

    private Path jarWorkingDirectory(RunConfigurationData configuration, JavaModule module,
                                     JavaProjectDescriptor descriptor, Path jar) {
        Optional<Path> configured = RunPaths.resolve(
                property(configuration, PROPERTY_WORKING_DIRECTORY), module, descriptor);
        if (configured.isPresent()) {
            return configured.get();
        }
        if (JavaRunTypes.isExternalJar(propertiesOf(configuration)) && jar.getParent() != null) {
            return jar.getParent();
        }
        return module != null ? module.root() : descriptor.root();
    }

    // --- Maven e Gradle ------------------------------------------------------

    private ProcessSpec buildToolSpec(RunConfigurationData configuration,
                                      JavaProjectDescriptor descriptor) {
        boolean gradle = JavaRunTypes.GRADLE.equals(configuration.getType());
        List<String> goals = splitArguments(property(configuration,
                gradle ? JavaRunTypes.TASKS : JavaRunTypes.GOALS));
        if (goals.isEmpty()) {
            throw new IllegalStateException(gradle
                    ? text("error.tasksRequired", "Informe ao menos uma task Gradle.")
                    : text("error.goalsRequired", "Informe ao menos um objetivo Maven."));
        }
        return toolProcess(configuration, descriptor, goals, List.of());
    }

    // --- Testes --------------------------------------------------------------

    private ProcessSpec testSpec(RunConfigurationData configuration,
                                 JavaProjectDescriptor descriptor, int debugPort) {
        boolean gradle = descriptor.isGradle();
        TestScope scope = TestScope.parse(property(configuration, JavaRunTypes.TEST_SCOPE));
        String target = property(configuration, JavaRunTypes.TEST_TARGET);

        List<String> extra = new ArrayList<>(
                TestSelectors.forBuildTool(gradle, scope, target));
        if (debugPort > 0) {
            extra.addAll(testDebugArguments(gradle, debugPort));
        }
        return toolProcess(configuration, descriptor, List.of("test"), extra);
    }

    /**
     * Argumentos que fazem o build tool iniciar uma unica JVM de testes suspensa, para que os
     * breakpoints sejam registrados antes do primeiro teste rodar.
     */
    static List<String> testDebugArguments(boolean gradle, int debugPort) {
        if (!gradle) {
            return List.of("-DforkCount=1",
                    "-Dmaven.surefire.debug=" + jdwpAgent(debugPort));
        }
        try {
            Path script = Files.createTempFile("orion-gradle-test-debug", ".gradle");
            script.toFile().deleteOnExit();
            Files.writeString(script, """
                    allprojects {
                        tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                            maxParallelForks = 1
                            forkEvery = 0
                            jvmArgs '%s'
                        }
                    }
                    """.formatted(jdwpAgent(debugPort)));
            return List.of("--init-script", script.toString());
        } catch (Exception error) {
            throw new IllegalStateException(text("error.testDebugScript",
                    "Nao foi possivel preparar o script de debug dos testes Gradle."), error);
        }
    }

    private ProcessSpec toolProcess(RunConfigurationData configuration,
                                    JavaProjectDescriptor descriptor,
                                    List<String> goals, List<String> extraArguments) {
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            throw new IllegalStateException(text("error.noBuildTool",
                    "O projeto nao possui um build tool configurado."));
        }
        JavaModule module = moduleOf(configuration).orElse(null);
        JdkInstallation jdk = RunJdkResolver.resolve(propertiesOf(configuration), jdkSupplier);

        List<String> arguments = new ArrayList<>(extraArguments);
        arguments.addAll(splitArguments(property(configuration, JavaRunTypes.RUNNER_ARGUMENTS)));

        Map<String, String> environment = new LinkedHashMap<>(
                customEnvironment(configuration));
        environment.put("JAVA_HOME", jdk.home().toString());

        BuildCommand.Options options = new BuildCommand.Options(
                splitList(property(configuration, JavaRunTypes.PROFILES)),
                arguments,
                Boolean.parseBoolean(property(configuration, JavaRunTypes.OFFLINE)),
                environment);

        BuildCommand command = build.toolCommand(module, goals, options)
                .orElseThrow(() -> new IllegalStateException(text("error.toolUnsupported",
                        "O build system atual nao suporta este tipo de configuracao.")));

        Path workingDirectory = RunPaths.resolve(
                        property(configuration, PROPERTY_WORKING_DIRECTORY), module, descriptor)
                .orElseGet(command::workingDirectory);

        return ProcessSpec.of(command.command(), workingDirectory, command.environment());
    }

    // --- Build antes de executar --------------------------------------------

    public Optional<String> runBeforeLaunchChain(RunConfigurationData configuration) {
        List<RunChainStep> steps = RunChainStep.decodeAll(
                property(configuration, JavaRunTypes.BEFORE_LAUNCH_CHAIN));
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        return new RunChainExecutor(chainHost.get(), output)
                .run(steps, configuration == null ? null : configuration.getId());
    }

    /** Executa o build previo exigido pela configuracao, quando aplicavel. */
    Optional<String> buildBeforeRun(RunConfigurationData configuration) {
        String type = configuration == null ? "" : configuration.getType();
        if (JavaRunTypes.BUILD_TOOL.contains(type)) {
            // Maven, Gradle e Testes ja passam pelo build tool no proprio lancamento.
            return Optional.empty();
        }
        boolean enabled = JavaRunValidation.flag(propertiesOf(configuration),
                JavaRunTypes.BUILD_BEFORE_RUN, JavaRunTypes.buildBeforeRunDefault(type));
        if (!enabled) {
            return Optional.empty();
        }
        if (JavaRunTypes.JAR.equals(type)) {
            return packageModule(configuration);
        }
        return compile(configuration);
    }

    private Optional<String> packageModule(RunConfigurationData configuration) {
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            return Optional.empty();
        }
        BuildResult result = build.execute(
                BuildRequest.of(BuildSystem.BuildAction.PACKAGE, moduleOf(configuration).orElse(null))
                        .withSkipTests(true), output);
        buildResultListener.accept(result);
        return result.successful() ? Optional.empty()
                : Optional.of(text("error.buildFailed", "O build falhou; a execucao foi cancelada.")
                        + " " + result.summary());
    }

    private Optional<String> compile(RunConfigurationData configuration) {
        JavaModule module = moduleOf(configuration).orElse(null);
        JavaDevelopmentBuildService development = developmentBuildSupplier == null
                ? null : developmentBuildSupplier.get();
        if (development != null) {
            JavaDevelopmentBuildService.Result result = development.build(module, false, output);
            if (result.successful()) {
                buildResultListener.accept(new BuildResult(0, List.of(), result.duration(),
                        "JDT incremental compile"));
                return Optional.empty();
            }
            if (!result.shouldFallback()) {
                buildResultListener.accept(new BuildResult(1, List.of(), result.duration(),
                        "JDT incremental: " + result.message()));
                return Optional.of(text("error.buildFailed",
                        "O build falhou; a execucao foi cancelada.") + " " + result.message());
            }
            output.accept("Fallback: usando o build oficial do projeto");
        }
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            return Optional.empty();
        }
        BuildSystem.BuildAction action = usesTestClasspath(configuration)
                ? BuildSystem.BuildAction.TEST_COMPILE
                : BuildSystem.BuildAction.COMPILE;
        BuildResult result = build.execute(
                BuildRequest.of(action, module).withSkipTests(true), output);
        buildResultListener.accept(result);

        if (result.successful()) {
            return Optional.empty();
        }
        return Optional.of(text("error.buildFailed", "O build falhou; a execucao foi cancelada.")
                + " " + result.summary());
    }

    // --- Auxiliares ----------------------------------------------------------

    static String jdwpAgent(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + port;
    }

    private static boolean usesTestClasspath(RunConfigurationData configuration) {
        return Boolean.parseBoolean(property(configuration, PROPERTY_TEST_CLASSPATH));
    }

    private String classpathOf(JavaModule module, boolean test) {
        if (!test) {
            JavaDevelopmentBuildService development = developmentBuildSupplier == null
                    ? null : developmentBuildSupplier.get();
            Optional<String> incrementalClasspath = development == null
                    ? Optional.empty() : development.runtimeClasspath(module);
            if (incrementalClasspath.isPresent() && !incrementalClasspath.get().isBlank()) {
                return incrementalClasspath.get();
            }
        }
        BuildSystem build = buildSupplier.get();
        Optional<String> classpath = build == null
                ? Optional.empty()
                : test ? build.resolveTestClasspath(module) : build.resolveRuntimeClasspath(module);

        if (classpath.isPresent() && !classpath.get().isBlank()) {
            return classpath.get();
        }
        log.info("Classpath nao resolvido; usando apenas as classes compiladas de {}", module.root());
        return module.outputDir().toString();
    }

    private Path workingDirectoryOf(RunConfigurationData configuration, JavaModule module,
                                    JavaProjectDescriptor descriptor) {
        return RunPaths.resolve(property(configuration, PROPERTY_WORKING_DIRECTORY),
                        module, descriptor)
                .orElseGet(() -> module != null ? module.root() : descriptor.root());
    }

    private Map<String, String> environmentOf(RunConfigurationData configuration,
                                              JdkInstallation jdk) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", jdk.home().toString());
        environment.putAll(customEnvironment(configuration));
        return environment;
    }

    /** Le o editor de ambiente aceitando {@code NOME=valor} por linha ou separado por virgulas. */
    static Map<String, String> customEnvironment(RunConfigurationData configuration) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String entry : splitList(property(configuration, PROPERTY_ENVIRONMENT))) {
            if (entry.startsWith("#")) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals > 0) {
                environment.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
            }
        }
        return environment;
    }

    private Optional<JavaModule> moduleOf(RunConfigurationData configuration) {
        JavaProjectDescriptor descriptor = descriptorSupplier.get();
        String name = property(configuration, PROPERTY_MODULE);
        if (descriptor == null) {
            return Optional.empty();
        }
        if (name.isBlank()) {
            return Optional.ofNullable(descriptor.rootModule());
        }
        return descriptor.modules().stream()
                .filter(module -> module.name().equals(name))
                .findFirst();
    }

    static Map<String, Object> propertiesOf(RunConfigurationData configuration) {
        return configuration == null || configuration.getProperties() == null
                ? Map.of() : configuration.getProperties();
    }

    static String property(RunConfigurationData configuration, String key) {
        Object value = propertiesOf(configuration).get(key);
        return value == null ? "" : value.toString().trim();
    }

    static List<String> splitArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    arguments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split("[,\\n]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static int debugPort(RunExecutionContext context) {
        return context != null && context.isDebug() ? DebugPorts.allocate() : 0;
    }

    public RunProcessHandle failure(String message) {
        output.accept(message);
        return RunProcessHandle.outputOnly(
                new ByteArrayInputStream((message + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
