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
    private final Map<Path, String> lastClasspathFailures = new ConcurrentHashMap<>();

    private static final String TEST_OUTPUT_DIR = "target/test-classes";
    private static final String REACTOR_CLASSPATH_FILE = "target/orion-classpath.txt";
    private static final String PERSISTED_CLASSPATH_DIR = ".orion/classpath";
    private static final String PERSISTED_CLASSPATH_FORMAT = "1";
    private static final int MAX_FAILURE_LINES = 12;

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
        if (classpathRunner.isRunning()) {
            classpathRunner.cancel();
        }
    }

    @Override
    public boolean isRunning() {
        return runner.isRunning();
    }

    @Override
    public Optional<String> resolveRuntimeClasspath(JavaModule module) {
        return resolveClasspath(module, "runtime");
    }

    @Override
    public Optional<String> resolveTestClasspath(JavaModule module) {
        return resolveClasspath(module, "test");
    }

    @Override
    public Optional<String> resolveCompileClasspath(JavaModule module) {
        return resolveClasspath(module, "compile");
    }

    private Optional<String> resolveClasspath(JavaModule module, String scope) {
        if (module == null) {
            return Optional.empty();
        }
        boolean test = "test".equals(scope);
        String key = cacheKey(module, scope);
        String cached = classpathCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        String fingerprint = persistentFingerprint(module, scope);
        Optional<String> resolved = readPersisted(module, scope, fingerprint);
        if (resolved.isEmpty()) {
            resolved = resolveInModule(module, scope);
            if (resolved.isEmpty()) {
                resolved = resolveInReactor(module, scope);
            }
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            lastClasspathFailures.remove(module.root());
            persist(module, scope, fingerprint, resolved.get());
        }
        String full = prefixOutputDirs(module, test)
                + ReactorClasspath.substituteWorkspaceModules(resolved.get(), descriptor, module,
                        test);
        classpathCache.put(key, full);
        return Optional.of(full);
    }

    private Path persistedFile(JavaModule module, String scope) {
        return descriptor.root().resolve(PERSISTED_CLASSPATH_DIR)
                .resolve(module.artifactId() + "-" + scope + ".classpath");
    }

    String persistentFingerprint(JavaModule module, String scope) {
        List<String> parts = new ArrayList<>();
        parts.add(PERSISTED_CLASSPATH_FORMAT);
        parts.add(scope);
        parts.add(module.root().toString());
        parts.add(String.join(",", new java.util.TreeSet<>(activeProfiles())));
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        parts.add(jdk == null ? "" : jdk.home().toString());
        Set<Path> poms = new java.util.TreeSet<>();
        poms.add(descriptor.root().resolve("pom.xml"));
        for (JavaModule candidate : descriptor.modules()) {
            poms.add(candidate.root().resolve("pom.xml"));
        }
        poms.add(descriptor.root().resolve(".mvn").resolve("maven.config"));
        for (Path pom : poms) {
            parts.add(pom + "=" + contentHash(pom));
        }
        return dtm.ide.build.incremental.ModuleBuildState.fingerprintOf(parts.toArray(String[]::new));
    }

    private static String contentHash(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? dtm.ide.build.incremental.ModuleBuildState.fingerprintOf(Files.readString(file))
                    : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Optional<String> readPersisted(JavaModule module, String scope, String fingerprint) {
        Path file = persistedFile(module, scope);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2 || !lines.get(0).equals(fingerprint)) {
                return Optional.empty();
            }
            String classpath = lines.get(1).trim();
            if (classpath.isBlank() || ClasspathValidation.hasMissingJar(classpath)) {
                return Optional.empty();
            }
            return Optional.of(classpath);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void persist(JavaModule module, String scope, String fingerprint, String classpath) {
        Path file = persistedFile(module, scope);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, fingerprint + System.lineSeparator() + classpath
                    + System.lineSeparator());
        } catch (Exception e) {
            log.debug("Nao foi possivel gravar o classpath de {}: {}", module.artifactId(), e.getMessage());
        }
    }

    private Optional<String> resolveInModule(JavaModule module, String scope) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("orion-classpath", ".txt");
            List<String> command = new ArrayList<>(baseCommand());
            command.add("-q");
            command.add("dependency:build-classpath");
            command.add("-Dmdep.outputFile=" + outputFile);
            command.add("-Dmdep.includeScope=" + scope);
            appendActiveProfiles(command);

            List<String> errors = new ArrayList<>();
            int exitCode = classpathRunner.run(command, module.root(),
                    environmentFor(BuildRequest.of(BuildAction.COMPILE, module)),
                    line -> collectError(errors, line));
            if (exitCode != 0) {
                rememberClasspathFailure(module, errors);
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

    private Optional<String> resolveInReactor(JavaModule module, String scope) {
        if (module.root().equals(descriptor.root())) {
            return Optional.empty();
        }
        Path outputFile = module.root().resolve(REACTOR_CLASSPATH_FILE);
        try {
            List<String> errors = new ArrayList<>();
            int exitCode = classpathRunner.run(reactorClasspathCommand(module, scope),
                    descriptor.root(),
                    environmentFor(BuildRequest.of(BuildAction.COMPILE, module)),
                    line -> collectError(errors, line));
            if (exitCode != 0) {
                rememberClasspathFailure(module, errors);
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

    /**
     * Sem uma fase do ciclo de vida o Maven 3 nao resolve os modulos irmaos pelo reactor e passa
     * a exigir o jar instalado no repositorio local. A fase {@code compile} (ou
     * {@code test-compile}) faz o reactor apontar cada irmao para o seu {@code target/classes},
     * enquanto os skips evitam compilar e copiar recursos de novo.
     */
    List<String> reactorClasspathCommand(JavaModule module, String scope) {
        boolean test = "test".equals(scope);
        List<String> command = new ArrayList<>(baseCommand());
        command.add("-q");
        command.add(test ? "test-compile" : "compile");
        command.add("dependency:build-classpath");
        command.add("-Dmaven.main.skip=true");
        command.add("-Dmaven.resources.skip=true");
        if (test) {
            command.add("-Dmaven.test.skip=true");
        }
        command.add("-Dmdep.outputFile=" + REACTOR_CLASSPATH_FILE);
        command.add("-Dmdep.includeScope=" + scope);
        command.add("-pl");
        command.add(relativeModulePath(module));
        command.add("-am");
        appendActiveProfiles(command);
        return command;
    }

    private static void collectError(List<String> errors, String line) {
        if (line == null || errors.size() >= MAX_FAILURE_LINES) {
            return;
        }
        String plain = line.replaceAll("\u001B\\[[;\\d]*m", "").trim();
        if (plain.startsWith("[ERROR]") && plain.length() > "[ERROR]".length()) {
            errors.add(plain);
        }
    }

    private void rememberClasspathFailure(JavaModule module, List<String> errors) {
        if (!errors.isEmpty()) {
            lastClasspathFailures.put(module.root(), String.join(System.lineSeparator(), errors));
        }
    }

    @Override
    public Optional<String> lastClasspathFailure(JavaModule module) {
        return module == null ? Optional.empty()
                : Optional.ofNullable(lastClasspathFailures.get(module.root()));
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

    private String cacheKey(JavaModule module, String scope) {
        return scope + "|" + String.join(",", activeProfiles()) + "|" + module.root();
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
        Path persisted = descriptor.root().resolve(PERSISTED_CLASSPATH_DIR);
        if (!Files.isDirectory(persisted)) {
            return;
        }
        try (var files = Files.list(persisted)) {
            files.forEach(MavenBuildService::deleteQuietly);
        } catch (Exception e) {
            log.debug("Nao foi possivel limpar {}: {}", persisted, e.getMessage());
        }
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
        return executeToolCommand(module, goals, BuildCommand.Options.none(), output);
    }

    @Override
    public BuildResult executeToolCommand(JavaModule module, List<String> goals,
                                          BuildCommand.Options options, Consumer<String> output) {
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
        Map<String, String> environment = environmentFor(BuildRequest.of(BuildAction.COMPILE, module));
        environment.putAll(resolved.environment());
        Instant start = Instant.now();
        BuildDiagnosticParser parser = new BuildDiagnosticParser(descriptor.root());
        emit(output, "> " + String.join(" ", command));
        AtomicBoolean successMarker = new AtomicBoolean();
        int exit = runner.run(command, descriptor.root(), environment, line -> {
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
