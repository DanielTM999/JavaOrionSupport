package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.test.TestScope;
import dtm.stools.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validacao das configuracoes de execucao Java.
 *
 * <p>A API de formulario do Workbench nao permite bloquear o botao Apply, entao a validacao
 * roda em tres momentos: no formulario (erros inline), na selecao da configuracao (para
 * desabilitar Run/Debug) e no lancador (protecao final).</p>
 */
public final class JavaRunValidation {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaRunValidation.class, key, fallback);
    }

    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern ENVIRONMENT_LINE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_.]*=.*");

    private JavaRunValidation() {
    }

    /** Um erro associado a uma propriedade do formulario. */
    public record Problem(String field, String message) {
    }

    /** Resultado da validacao de uma configuracao. */
    public record Report(List<Problem> problems) {

        public Report {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static Report valid() {
            return new Report(List.of());
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        public Optional<String> messageFor(String field) {
            return problems.stream()
                    .filter(problem -> problem.field().equals(field))
                    .map(Problem::message)
                    .findFirst();
        }

        public String firstMessage() {
            return problems.isEmpty() ? "" : problems.getFirst().message();
        }

        public Map<String, String> byField() {
            Map<String, String> messages = new LinkedHashMap<>();
            problems.forEach(problem -> messages.putIfAbsent(problem.field(), problem.message()));
            return messages;
        }
    }

    /** Contexto do projeto usado para resolver modulos e caminhos relativos. */
    public record Context(JavaProjectDescriptor descriptor, boolean checkFileSystem) {

        public static Context of(JavaProjectDescriptor descriptor) {
            return new Context(descriptor, true);
        }

        /** Contexto que ignora a existencia dos arquivos, util enquanto o usuario digita. */
        public static Context lenient(JavaProjectDescriptor descriptor) {
            return new Context(descriptor, false);
        }
    }

    public static Report validate(RunConfigurationData configuration, Context context) {
        if (configuration == null) {
            return Report.valid();
        }
        return validate(configuration.getType(), properties(configuration), context);
    }

    public static Report validate(String type, Map<String, Object> properties, Context context) {
        List<Problem> problems = new ArrayList<>();
        Context resolved = context == null ? new Context(null, false) : context;
        JavaModule module = moduleOf(properties, resolved.descriptor());

        switch (type == null ? "" : type) {
            case JavaRunTypes.APPLICATION, JavaRunTypes.SPRING_BOOT ->
                    validateApplication(type, properties, problems);
            case JavaRunTypes.JAR -> validateJar(properties, module, resolved, problems);
            case JavaRunTypes.MAVEN -> validateBuildTool(properties, JavaRunTypes.GOALS,
                    text("error.goalsRequired", "Informe ao menos um objetivo Maven."), problems);
            case JavaRunTypes.GRADLE -> validateBuildTool(properties, JavaRunTypes.TASKS,
                    text("error.tasksRequired", "Informe ao menos uma task Gradle."), problems);
            case JavaRunTypes.TEST -> validateTest(properties, problems);
            case JavaRunTypes.REMOTE -> {
                validateRemote(properties, problems);
                return new Report(problems);
            }
            default -> {
                return Report.valid();
            }
        }
        validateCommon(properties, module, resolved, problems);
        return new Report(problems);
    }

    private static void validateApplication(String type, Map<String, Object> properties,
                                            List<Problem> problems) {
        String mainClass = value(properties, JavaRunTypes.MAIN_CLASS);
        if (mainClass.isBlank()) {
            problems.add(new Problem(JavaRunTypes.MAIN_CLASS, text("error.mainClassRequired",
                    "Informe a classe principal.")));
        } else if (!QUALIFIED_NAME.matcher(mainClass).matches()) {
            problems.add(new Problem(JavaRunTypes.MAIN_CLASS, text("error.mainClassInvalid",
                    "Classe principal invalida. Use o nome qualificado, como com.exemplo.Main.")));
        }
        if (JavaRunTypes.SPRING_BOOT.equals(type)) {
            String port = value(properties, JavaRunTypes.SERVER_PORT);
            if (!port.isBlank() && !isPort(port)) {
                problems.add(new Problem(JavaRunTypes.SERVER_PORT, text("error.portInvalid",
                        "Porta invalida. Use um numero entre 1 e 65535.")));
            }
        }
    }

    private static void validateJar(Map<String, Object> properties, JavaModule module,
                                    Context context, List<Problem> problems) {
        String jar = value(properties, JavaRunTypes.JAR_PATH);
        if (jar.isBlank()) {
            problems.add(new Problem(JavaRunTypes.JAR_PATH, text("error.jarRequired",
                    "Selecione o arquivo JAR a executar.")));
            return;
        }
        if (RunPaths.isMalformed(jar)) {
            problems.add(new Problem(JavaRunTypes.JAR_PATH, text("error.pathInvalid",
                    "Caminho invalido.")));
            return;
        }
        if (!jar.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            problems.add(new Problem(JavaRunTypes.JAR_PATH, text("error.jarExtension",
                    "O arquivo selecionado precisa ter a extensao .jar.")));
            return;
        }
        boolean buildBeforeRun = flag(properties, JavaRunTypes.BUILD_BEFORE_RUN,
                JavaRunTypes.buildBeforeRunDefault(JavaRunTypes.JAR));
        if (!context.checkFileSystem() || buildBeforeRun) {
            return;
        }
        Optional<Path> resolved = RunPaths.resolve(jar, module, context.descriptor());
        if (resolved.isPresent() && !Files.isRegularFile(resolved.get())) {
            problems.add(new Problem(JavaRunTypes.JAR_PATH, text("error.jarMissing",
                    "O arquivo JAR nao foi encontrado:") + " " + resolved.get()));
        }
    }

    private static void validateBuildTool(Map<String, Object> properties, String key,
                                          String message, List<Problem> problems) {
        if (JavaRunSupport.splitArguments(value(properties, key)).isEmpty()) {
            problems.add(new Problem(key, message));
        }
    }

    private static void validateTest(Map<String, Object> properties, List<Problem> problems) {
        TestScope scope = TestScope.parse(value(properties, JavaRunTypes.TEST_SCOPE));
        String target = value(properties, JavaRunTypes.TEST_TARGET);
        if (scope.requiresTarget() && target.isBlank()) {
            problems.add(new Problem(JavaRunTypes.TEST_TARGET, text("error.testTargetRequired",
                    "Informe o alvo dos testes para o escopo selecionado.")));
            return;
        }
        if (target.isBlank()) {
            return;
        }
        boolean structured = scope == TestScope.PACKAGE || scope == TestScope.CLASS;
        if (structured && !QUALIFIED_NAME.matcher(target).matches()) {
            problems.add(new Problem(JavaRunTypes.TEST_TARGET, text("error.testTargetInvalid",
                    "Alvo invalido. Use um nome qualificado, como com.exemplo.MinhaClasseTest.")));
        }
        if (scope == TestScope.METHOD) {
            String normalized = target.replace('#', '.');
            if (!QUALIFIED_NAME.matcher(normalized).matches() || !normalized.contains(".")) {
                problems.add(new Problem(JavaRunTypes.TEST_TARGET, text("error.testMethodInvalid",
                        "Metodo invalido. Use com.exemplo.MinhaClasseTest#meuTeste.")));
            }
        }
    }

    private static void validateRemote(Map<String, Object> properties, List<Problem> problems) {
        String mode = value(properties, JavaRunTypes.REMOTE_MODE);
        boolean listen = JavaRunTypes.REMOTE_MODE_LISTEN.equalsIgnoreCase(mode);
        String host = value(properties, JavaRunTypes.REMOTE_HOST);
        if (host.isBlank()) {
            problems.add(new Problem(JavaRunTypes.REMOTE_HOST, listen
                    ? text("error.bindRequired", "Informe o endereco de escuta.")
                    : text("error.hostRequired", "Informe o host da JVM remota.")));
        } else if (host.contains(" ")) {
            problems.add(new Problem(JavaRunTypes.REMOTE_HOST, text("error.hostInvalid",
                    "Endereco invalido.")));
        }
        String port = value(properties, JavaRunTypes.REMOTE_PORT);
        if (!isPort(port)) {
            problems.add(new Problem(JavaRunTypes.REMOTE_PORT, text("error.portInvalid",
                    "Porta invalida. Use um numero entre 1 e 65535.")));
        }
        String timeout = value(properties, JavaRunTypes.REMOTE_TIMEOUT);
        if (!timeout.isBlank() && !isTimeout(timeout)) {
            problems.add(new Problem(JavaRunTypes.REMOTE_TIMEOUT, text("error.timeoutInvalid",
                    "Timeout invalido. Use milissegundos entre 0 e 600000.")));
        }
    }

    private static void validateCommon(Map<String, Object> properties, JavaModule module,
                                       Context context, List<Problem> problems) {
        String workingDirectory = value(properties, JavaRunTypes.WORKING_DIRECTORY);
        if (RunPaths.isMalformed(workingDirectory)) {
            problems.add(new Problem(JavaRunTypes.WORKING_DIRECTORY, text("error.pathInvalid",
                    "Caminho invalido.")));
        } else if (context.checkFileSystem()) {
            RunPaths.resolve(workingDirectory, module, context.descriptor())
                    .filter(path -> !Files.isDirectory(path))
                    .ifPresent(path -> problems.add(new Problem(JavaRunTypes.WORKING_DIRECTORY,
                            text("error.directoryMissing", "Diretorio nao encontrado:")
                                    + " " + path)));
        }
        String jdkHome = value(properties, JavaRunTypes.JDK_HOME);
        if (!jdkHome.isBlank() && context.checkFileSystem()
                && !Files.isDirectory(Path.of(jdkHome))) {
            problems.add(new Problem(JavaRunTypes.JDK_HOME, text("error.jdkMissing",
                    "A JDK selecionada nao esta mais disponivel:") + " " + jdkHome));
        }
        environmentProblem(value(properties, JavaRunTypes.ENVIRONMENT)).ifPresent(problems::add);
    }

    /** Valida o editor multilinha de ambiente no formato {@code NOME=valor}. */
    public static Optional<Problem> environmentProblem(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (String line : raw.split("\\R")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            if (!ENVIRONMENT_LINE.matcher(entry).matches()) {
                return Optional.of(new Problem(JavaRunTypes.ENVIRONMENT,
                        text("error.environmentInvalid",
                                "Use uma variavel por linha no formato NOME=valor. Linha invalida:")
                                + " " + entry));
            }
        }
        return Optional.empty();
    }

    public static boolean isPort(String raw) {
        try {
            int port = Integer.parseInt(raw == null ? "" : raw.trim());
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    public static boolean isTimeout(String raw) {
        try {
            int timeout = Integer.parseInt(raw == null ? "" : raw.trim());
            return timeout >= 0 && timeout <= 600_000;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static JavaModule moduleOf(Map<String, Object> properties,
                                       JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        String name = value(properties, JavaRunTypes.MODULE);
        if (name.isBlank()) {
            return descriptor.rootModule();
        }
        return descriptor.modules().stream()
                .filter(module -> module.name().equals(name))
                .findFirst()
                .orElseGet(descriptor::rootModule);
    }

    private static Map<String, Object> properties(RunConfigurationData configuration) {
        return configuration.getProperties() == null ? Map.of() : configuration.getProperties();
    }

    public static String value(Map<String, Object> properties, String key) {
        if (properties == null) {
            return "";
        }
        Object raw = properties.get(key);
        return raw == null ? "" : raw.toString().trim();
    }

    public static boolean flag(Map<String, Object> properties, String key, boolean fallback) {
        String raw = value(properties, key);
        return raw.isBlank() ? fallback : Boolean.parseBoolean(raw);
    }
}
