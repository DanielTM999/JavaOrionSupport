package dtm.ide.build;

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
        if (cached != null) {
            return Optional.of(cached);
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
                return Optional.empty();
            }
            String classpath = Files.readString(outputFile).trim();
            if (classpath.isBlank()) {
                return Optional.empty();
            }
            classpathCache.put(key, classpath);
            return Optional.of(classpath);
        } catch (Exception e) {
            log.debug("Falha ao resolver o classpath Gradle de {}: {}", module.root(), e.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(initScript);
            deleteQuietly(outputFile);
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
    public BuildResult executeToolCommand(JavaModule module, List<String> tasks,
                                          Consumer<String> output) {
        List<String> command = new ArrayList<>(baseCommand());
        if (tasks != null) {
            tasks.forEach(task -> command.add(module == null ? task : taskPath(module, task)));
        }
        for (String profile : activeProfiles()) {
            command.add("-P" + profile);
        }
        Instant start = Instant.now();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(descriptor.root());
        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exit = runner.run(command, descriptor.root(), environmentFor(
                BuildRequest.of(BuildAction.COMPILE, module)), line -> {
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
