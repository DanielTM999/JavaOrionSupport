package dtm.ide.build;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public final class GradleBuildService implements BuildSystem {

    private final JavaProjectDescriptor descriptor;
    private final BuildToolProvisioner provisioner;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final DownloadProgressListener progressListener;
    private final ProcessRunner runner = new ProcessRunner();
    private final Map<String, String> classpathCache = new ConcurrentHashMap<>();

    private volatile Runnable staleClasspathListener = () -> {
    };
    private volatile Supplier<Set<String>> activeProfiles;

    public GradleBuildService(JavaProjectDescriptor descriptor, BuildToolProvisioner provisioner,
                              Supplier<JdkInstallation> jdkSupplier,
                              DownloadProgressListener progressListener) {
        this.descriptor = descriptor;
        this.provisioner = provisioner;
        this.jdkSupplier = jdkSupplier;
        this.progressListener = progressListener;
    }

    public void setActiveProfiles(Supplier<Set<String>> supplier) {
        this.activeProfiles = supplier;
    }

    /** Installs an internal hook used to ask the language server to reread the build model. */
    public void setStaleClasspathListener(Runnable listener) {
        this.staleClasspathListener = listener == null ? () -> {
        } : listener;
    }

    private Set<String> activeProfiles() {
        Supplier<Set<String>> supplier = activeProfiles;
        Set<String> profiles = supplier == null ? null : supplier.get();
        return profiles == null ? Set.of() : profiles;
    }

    @Override
    public String name() {
        return "Gradle";
    }

    @Override
    public BuildResult execute(BuildRequest request, Consumer<String> output) {
        List<String> command;
        try {
            command = buildCommand(request);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            emit(output, reason);
            return BuildResult.failed("gradle", reason);
        }

        Path workingDirectory = descriptor.root();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(workingDirectory);
        Instant start = Instant.now();

        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exitCode = runner.run(command, workingDirectory, environmentFor(request), line -> {
            parser.accept(line);
            if (line.contains("BUILD SUCCESSFUL")) {
                successMarker.set(true);
            }
            emit(output, line);
        });
        if (exitCode != 0 && successMarker.get()) {
            exitCode = 0;
        }

        return new BuildResult(exitCode, parser.diagnostics(),
                Duration.between(start, Instant.now()), String.join(" ", command));
    }

    @Override
    public void cancel() {
        runner.cancel();
    }

    @Override
    public boolean isRunning() {
        return runner.isRunning();
    }

    @Override
    public Optional<String> resolveRuntimeClasspath(JavaModule module) {
        return resolveClasspath(module, false);
    }

    @Override
    public Optional<String> resolveTestClasspath(JavaModule module) {
        return resolveClasspath(module, true);
    }

    private Optional<String> resolveClasspath(JavaModule module, boolean test) {
        if (module == null) {
            return Optional.empty();
        }
        String key = cacheKey(module, test);
        String cached = classpathCache.get(key);
        boolean staleCache = false;
        if (cached != null) {
            if (!ClasspathValidation.hasMissingJar(cached)) {
                return Optional.of(cached);
            }
            staleCache = classpathCache.remove(key, cached);
            if (staleCache) {
                log.debug("Classpath Gradle obsoleto para {}: dependencia JAR ausente; resolvendo novamente",
                        module.root());
            }
        }
        Path initScript = null;
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("orion-gradle-classpath", ".txt");
            initScript = writeClasspathInitScript(outputFile);

            List<String> command = new ArrayList<>(baseCommand());
            command.add("--init-script");
            command.add(initScript.toString());
            command.add("-q");
            command.add(taskPath(module, test
                    ? "orionPrintTestClasspath" : "orionPrintRuntimeClasspath"));

            int exitCode = runner.run(command, descriptor.root(), Map.of(), line -> {
            });
            if (exitCode != 0) {
                notifyStaleClasspath(staleCache);
                return Optional.empty();
            }
            String classpath = Files.readString(outputFile).trim();
            if (classpath.isBlank()) {
                notifyStaleClasspath(staleCache);
                return Optional.empty();
            }
            if (ClasspathValidation.hasMissingJar(classpath)) {
                log.debug("Classpath Gradle ainda contem JAR ausente para {}; nao armazenando o resultado",
                        module.root());
                notifyStaleClasspath(staleCache);
                return Optional.empty();
            }
            classpathCache.put(key, classpath);
            notifyStaleClasspath(staleCache);
            return Optional.of(classpath);
        } catch (Exception e) {
            log.debug("Falha ao resolver o classpath Gradle de {}: {}", module.root(), e.getMessage());
            notifyStaleClasspath(staleCache);
            return Optional.empty();
        } finally {
            deleteQuietly(initScript);
            deleteQuietly(outputFile);
        }
    }

    private void notifyStaleClasspath(boolean staleCache) {
        if (!staleCache) {
            return;
        }
        try {
            staleClasspathListener.run();
        } catch (Exception e) {
            log.debug("Falha ao atualizar o servidor Java apos classpath obsoleto: {}",
                    e.getMessage());
        }
    }

    private static String cacheKey(JavaModule module, boolean test) {
        return (test ? "test|" : "runtime|") + module.root();
    }

    @Override
    public void invalidateClasspathCache() {
        classpathCache.clear();
    }

    @Override
    public BuildResult refreshDependencies(JavaModule module, DependencyCoordinate dependency,
                                           Consumer<String> output) {
        // O cache de modulos do Gradle e compartilhado pelo build; a opcao oficial atualiza
        // todas as configuracoes mesmo quando a acao nasceu de uma dependencia selecionada.
        BuildResult result = executeToolCommand(null,
                List.of("dependencies", "--refresh-dependencies"), output);
        if (result.successful()) {
            invalidateClasspathCache();
        }
        return result;
    }

    @Override
    public BuildResult executeToolCommand(JavaModule module, List<String> tasks,
                                          Consumer<String> output) {
        return executeToolCommand(module, tasks, BuildCommand.Options.none(), output);
    }

    @Override
    public BuildResult executeToolCommand(JavaModule module, List<String> tasks,
                                          BuildCommand.Options options, Consumer<String> output) {
        BuildCommand.Options resolved = options == null ? BuildCommand.Options.none() : options;
        List<String> command = new ArrayList<>(baseCommand());
        if (tasks != null) {
            tasks.forEach(task -> command.add(module == null || task.startsWith("-")
                    ? task : taskPath(module, task)));
        }
        if (resolved.offline()) {
            command.add("--offline");
        }
        for (String profile : resolved.profiles()) {
            command.add("-P" + profile);
        }
        for (String profile : activeProfiles()) {
            command.add("-P" + profile);
        }
        command.addAll(resolved.extraArguments());
        Map<String, String> environment = environmentFor(BuildRequest.of(BuildAction.COMPILE, module));
        environment.putAll(resolved.environment());
        Instant start = Instant.now();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(descriptor.root());
        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exit = runner.run(command, descriptor.root(), environment, line -> {
            parser.accept(line);
            if (line.contains("BUILD SUCCESSFUL")) {
                successMarker.set(true);
            }
            emit(output, line);
        });
        if (exit != 0 && successMarker.get()) {
            exit = 0;
        }
        return new BuildResult(exit, parser.diagnostics(), Duration.between(start, Instant.now()),
                String.join(" ", command));
    }

    @Override
    public Optional<BuildCommand> toolCommand(JavaModule module, List<String> tasks,
                                              BuildCommand.Options options) {
        BuildCommand.Options resolved = options == null ? BuildCommand.Options.none() : options;
        List<String> command = new ArrayList<>(baseCommand());
        if (tasks != null) {
            tasks.forEach(task -> command.add(module == null ? task : taskPath(module, task)));
        }
        if (resolved.offline()) {
            command.add("--offline");
        }
        for (String profile : resolved.profiles()) {
            command.add("-P" + profile);
        }
        for (String profile : activeProfiles()) {
            command.add("-P" + profile);
        }
        command.addAll(resolved.extraArguments());

        Map<String, String> environment = new LinkedHashMap<>(resolved.environment());
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        if (jdk != null) {
            environment.putIfAbsent("JAVA_HOME", jdk.home().toString());
        }
        return Optional.of(new BuildCommand(command, descriptor.root(), environment));
    }

    private List<String> buildCommand(BuildRequest request) {
        List<String> command = new ArrayList<>(baseCommand());
        for (String task : tasksFor(request.action())) {
            command.add(request.module() == null ? task : taskPath(request.module(), task));
        }
        if (request.offline()) {
            command.add("--offline");
        }
        if (request.skipTests() && request.action() != BuildAction.TEST) {
            command.add("-x");
            command.add("test");
        }
        for (String profile : request.profiles()) {
            command.add("-Dspring.profiles.active=" + profile);
        }
        for (String profile : activeProfiles()) {
            command.add("-P" + profile);
        }
        command.addAll(request.extraArguments());
        return command;
    }

    private List<String> baseCommand() {
        BuildToolProvisioner.BuildTool gradle = provisioner.ensureGradle(descriptor, progressListener);
        List<String> command = new ArrayList<>();
        command.add(gradle.executable().toString());
        command.add("--console=plain");
        return command;
    }

    private static List<String> tasksFor(BuildAction action) {
        return switch (action) {
            case CLEAN -> List.of("clean");
            case REBUILD -> List.of("clean", "classes");
            case TEST -> List.of("test");
            case PACKAGE -> List.of("assemble");
            case INSTALL -> List.of("publishToMavenLocal");
            case COMPILE -> List.of("classes");
            case TEST_COMPILE -> List.of("testClasses");
        };
    }

    private String taskPath(JavaModule module, String task) {
        if (module == null || module.root().equals(descriptor.root())) {
            return task;
        }
        String relative = descriptor.root().relativize(module.root()).toString()
                .replace(File.separatorChar, ':')
                .replace('/', ':');
        return ":" + relative + ":" + task;
    }

    private Path writeClasspathInitScript(Path outputFile) throws Exception {
        Path script = Files.createTempFile("orion-gradle-init", ".gradle");
        String target = outputFile.toString().replace("\\", "\\\\");
        String content = """
                allprojects {
                    tasks.register('orionPrintRuntimeClasspath') {
                        doLast {
                            def parts = []
                            if (project.plugins.hasPlugin('java')) {
                                parts += project.sourceSets.main.output.files
                                parts += project.configurations.runtimeClasspath.files
                            }
                            new File('%s').text = parts.join(File.pathSeparator)
                        }
                    }
                    tasks.register('orionPrintTestClasspath') {
                        doLast {
                            def parts = []
                            if (project.plugins.hasPlugin('java')) {
                                parts += project.sourceSets.test.output.files
                                parts += project.sourceSets.main.output.files
                                parts += project.configurations.testRuntimeClasspath.files
                            }
                            new File('%s').text = parts.join(File.pathSeparator)
                        }
                    }
                }
                """.formatted(target, target);
        Files.writeString(script, content);
        return script;
    }

    private Map<String, String> environmentFor(BuildRequest request) {
        Map<String, String> environment = new LinkedHashMap<>(request.environment());
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        if (jdk != null) {
            environment.putIfAbsent("JAVA_HOME", jdk.home().toString());
        }
        return environment;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static void emit(Consumer<String> output, String line) {
        if (output != null) {
            output.accept(line);
        }
    }
}
