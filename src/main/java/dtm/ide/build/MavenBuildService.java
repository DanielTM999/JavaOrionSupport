package dtm.ide.build;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public final class MavenBuildService implements BuildSystem {

    private final JavaProjectDescriptor descriptor;
    private final BuildToolProvisioner provisioner;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final DownloadProgressListener progressListener;
    private final ProcessRunner runner = new ProcessRunner();
    private final ProcessRunner classpathRunner = new ProcessRunner();
    private final Map<String, String> classpathCache = new ConcurrentHashMap<>();

    private static final String TEST_OUTPUT_DIR = "target/test-classes";
    private static final String REACTOR_CLASSPATH_FILE = "target/orion-classpath.txt";

    private volatile Supplier<Set<String>> activeProfiles;

    public MavenBuildService(JavaProjectDescriptor descriptor, BuildToolProvisioner provisioner,
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
        return "Maven";
    }

    @Override
    public BuildResult execute(BuildRequest request, Consumer<String> output) {
        List<String> command;
        try {
            command = buildCommand(request);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            emit(output, reason);
            return BuildResult.failed("mvn", reason);
        }

        Path workingDirectory = descriptor.root();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(workingDirectory);
        Instant start = Instant.now();

        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exitCode = runner.run(command, workingDirectory, environmentFor(request), line -> {
            parser.accept(line);
            if (line.contains("BUILD SUCCESS")) {
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
        Optional<String> resolved = resolveInModule(module, test);
        if (resolved.isEmpty()) {
            resolved = resolveInReactor(module, test);
        }
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String full = prefixOutputDirs(module, test)
                + ReactorClasspath.substituteWorkspaceModules(resolved.get(), descriptor, module,
                        test);
        classpathCache.put(key, full);
        return Optional.of(full);
    }

    private Optional<String> resolveInModule(JavaModule module, boolean test) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("orion-classpath", ".txt");
            List<String> command = new ArrayList<>(baseCommand());
            command.add("-q");
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + outputFile);
            command.add("-Dmdep.includeScope=" + (test ? "test" : "runtime"));
            appendActiveProfiles(command);

            int exitCode = classpathRunner.run(command, module.root(),
                    environmentFor(BuildRequest.of(BuildAction.COMPILE, module)), line -> {
                    });
            if (exitCode != 0) {
                return Optional.empty();
            }
            return readClasspath(outputFile);
        } catch (Exception e) {
            log.debug("Falha ao resolver o classpath de {}: {}", module.root(), e.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private Optional<String> resolveInReactor(JavaModule module, boolean test) {
        if (module.root().equals(descriptor.root())) {
            return Optional.empty();
        }
        Path outputFile = module.root().resolve(REACTOR_CLASSPATH_FILE);
        try {
            List<String> command = new ArrayList<>(baseCommand());
            command.add("-q");
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + REACTOR_CLASSPATH_FILE);
            command.add("-Dmdep.includeScope=" + (test ? "test" : "runtime"));
            command.add("-pl");
            command.add(relativeModulePath(module));
            command.add("-am");
            appendActiveProfiles(command);

            int exitCode = classpathRunner.run(command, descriptor.root(),
                    environmentFor(BuildRequest.of(BuildAction.COMPILE, module)), line -> {
                    });
            if (exitCode != 0) {
                return Optional.empty();
            }
            return readClasspath(outputFile);
        } catch (Exception e) {
            log.debug("Falha ao resolver o classpath de {} pelo reactor: {}", module.root(),
                    e.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private void appendActiveProfiles(List<String> command) {
        Set<String> profiles = activeProfiles();
        if (!profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
    }

    private static Optional<String> readClasspath(Path outputFile) throws Exception {
        if (outputFile == null || !Files.isRegularFile(outputFile)) {
            return Optional.empty();
        }
        String classpath = Files.readString(outputFile).trim();
        return classpath.isBlank() ? Optional.empty() : Optional.of(classpath);
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

    private String cacheKey(JavaModule module, boolean test) {
        return (test ? "test|" : "runtime|") + String.join(",", activeProfiles())
                + "|" + module.root();
    }

    private static String prefixOutputDirs(JavaModule module, boolean test) {
        String separator = java.io.File.pathSeparator;
        if (!test) {
            return module.outputDir() + separator;
        }
        return module.root().resolve(TEST_OUTPUT_DIR) + separator + module.outputDir() + separator;
    }

    @Override
    public void invalidateClasspathCache() {
        classpathCache.clear();
    }

    @Override
    public BuildResult refreshDependencies(JavaModule module, DependencyCoordinate dependency,
                                           Consumer<String> output) {
        BuildResult result = executeToolCommand(module, dependencyRefreshArguments(dependency), output);
        if (result.successful()) {
            invalidateClasspathCache();
        }
        return result;
    }

    List<String> dependencyRefreshArguments(DependencyCoordinate dependency) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-U");
        arguments.add("dependency:purge-local-repository");
        if (dependency != null && dependency.isValid()) {
            arguments.add("-DmanualInclude=" + dependency.key());
        }
        arguments.add("-DreResolve=true");
        arguments.add("dependency:resolve");
        return arguments;
    }

    @Override
    public BuildResult executeToolCommand(JavaModule module, List<String> goals,
                                          Consumer<String> output) {
        List<String> command = new ArrayList<>(baseCommand());
        command.addAll(goals == null ? List.of() : goals);
        appendModuleSelection(command, module);
        Set<String> profiles = activeProfiles();
        if (!profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
        Instant start = Instant.now();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(descriptor.root());
        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exit = runner.run(command, descriptor.root(), environmentFor(
                BuildRequest.of(BuildAction.COMPILE, module)), line -> {
            parser.accept(line);
            if (line.contains("BUILD SUCCESS")) {
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
    public Optional<BuildCommand> toolCommand(JavaModule module, List<String> goals,
                                              BuildCommand.Options options) {
        BuildCommand.Options resolved = options == null ? BuildCommand.Options.none() : options;
        List<String> command = new ArrayList<>(baseCommand());
        command.addAll(goals == null ? List.of() : goals);
        appendModuleSelection(command, module);

        Set<String> profiles = new LinkedHashSet<>(resolved.profiles());
        profiles.addAll(activeProfiles());
        if (!profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
        if (resolved.offline()) {
            command.add("-o");
        }
        command.addAll(resolved.extraArguments());

        Map<String, String> environment = new LinkedHashMap<>(resolved.environment());
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        if (jdk != null) {
            environment.putIfAbsent("JAVA_HOME", jdk.home().toString());
        }
        return Optional.of(new BuildCommand(command, descriptor.root(), environment));
    }

    private void appendModuleSelection(List<String> command, JavaModule module) {
        appendModuleSelection(command, module == null ? List.of() : List.of(module), true);
    }

    private void appendModuleSelection(List<String> command, BuildRequest request) {
        appendModuleSelection(command, request.selection(), request.alsoMake());
    }

    private void appendModuleSelection(List<String> command, List<JavaModule> selection,
                                       boolean alsoMake) {
        List<String> paths = new ArrayList<>();
        for (JavaModule module : selection) {
            if (module == null || module.root().equals(descriptor.root())) {
                continue;
            }
            String path = relativeModulePath(module);
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        command.add("-pl");
        command.add(String.join(",", paths));
        if (alsoMake) {
            command.add("-am");
        }
    }

    List<String> buildCommand(BuildRequest request) {
        List<String> command = new ArrayList<>(baseCommand());
        command.addAll(goalsFor(request.action()));

        appendModuleSelection(command, request);
        Set<String> profiles = new LinkedHashSet<>(request.profiles());
        profiles.addAll(activeProfiles());
        if (!profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
        if (request.offline()) {
            command.add("-o");
        }
        if (request.skipTests() && request.action() != BuildAction.TEST) {
            command.add("-DskipTests");
        }
        command.addAll(request.extraArguments());
        return command;
    }

    private List<String> baseCommand() {
        BuildToolProvisioner.BuildTool maven = provisioner.ensureMaven(descriptor, progressListener);
        List<String> command = new ArrayList<>();
        command.add(maven.executable().toString());
        command.add("-B");
        command.add("-Dstyle.color=always");
        return command;
    }

    private static List<String> goalsFor(BuildAction action) {
        return switch (action) {
            case CLEAN -> List.of("clean");
            case REBUILD -> List.of("clean", "compile");
            case TEST -> List.of("test");
            case PACKAGE -> List.of("package");
            case INSTALL -> List.of("install");
            case COMPILE -> List.of("compile");
            case TEST_COMPILE -> List.of("test-compile");
        };
    }

    private String relativeModulePath(JavaModule module) {
        try {
            String relative = descriptor.root().relativize(module.root()).toString();
            return relative.isBlank() ? "." : relative.replace('\\', '/');
        } catch (Exception e) {
            return module.root().toString();
        }
    }

    private Map<String, String> environmentFor(BuildRequest request) {
        Map<String, String> environment = new LinkedHashMap<>(request.environment());
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        if (jdk != null) {
            environment.putIfAbsent("JAVA_HOME", jdk.home().toString());
        }
        return environment;
    }

    private static void emit(Consumer<String> output, String line) {
        if (output != null) {
            output.accept(line);
        }
    }
}
