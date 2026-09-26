package dtm.ide.build.incremental;

import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildDiagnosticParser;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.ClasspathValidation;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.JavacCommands;
import dtm.ide.build.JavacDaemons;
import dtm.ide.build.ProcessRunner;
import dtm.ide.deps.MavenLocalRepositoryResolver;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.WorkspaceModuleGraph;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public final class IncrementalJavaBuilder {

    public static final String STATE_DIRECTORY = ".orion/incremental";

    private static final String GENERATED_SOURCES = "target/generated-sources/annotations";
    private static final String GENERATED_TEST_SOURCES = "target/generated-test-sources/test-annotations";
    private static final String TEST_OUTPUT_DIR = "target/test-classes";
    private static final double FULL_MODULE_RATIO = 0.4;

    @FunctionalInterface
    public interface JavacExecutor {
        int run(List<String> command, Path workingDirectory, Map<String, String> environment,
                Consumer<String> output);
    }

    @FunctionalInterface
    public interface ModuleListener {
        void starting(JavaModule module, int index, int total);
    }

    private final JavaProjectDescriptor descriptor;
    private final Supplier<BuildSystem> buildSupplier;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final ProcessRunner runner = new ProcessRunner();

    private static final Map<Path, Path> LOCAL_REPOSITORIES = new ConcurrentHashMap<>();

    private final Map<Path, Boolean> resourceLayouts = new ConcurrentHashMap<>();
    private Supplier<Path> localRepository;

    private JavacExecutor javac = this::runJavac;
    private ModuleListener moduleListener = (module, index, total) -> { };

    public IncrementalJavaBuilder(JavaProjectDescriptor descriptor,
                                  Supplier<BuildSystem> buildSupplier,
                                  Supplier<JdkInstallation> jdkSupplier) {
        this.descriptor = descriptor;
        this.buildSupplier = buildSupplier;
        this.jdkSupplier = jdkSupplier;
    }

    public IncrementalJavaBuilder withJavac(JavacExecutor executor) {
        this.javac = executor == null ? this::runJavac : executor;
        return this;
    }

    private int runJavac(List<String> command, Path workingDirectory, Map<String, String> environment,
                         Consumer<String> output) {
        return JavacDaemons.run(command, workingDirectory, environment, output, runner);
    }

    public IncrementalJavaBuilder withLocalRepository(Supplier<Path> repository) {
        this.localRepository = repository;
        return this;
    }

    private List<Path> processorPathOf(JavaModule module) {
        List<AnnotationProcessorPaths.Coordinate> declared = AnnotationProcessorPaths.declared(
                module.root().resolve(JavaProjectConventions.POM_FILE),
                descriptor.root().resolve(JavaProjectConventions.POM_FILE));
        if (declared.isEmpty()) {
            return List.of();
        }
        Path repository = null;
        try {
            repository = localRepository != null ? localRepository.get()
                    : LOCAL_REPOSITORIES.computeIfAbsent(descriptor.root(), ignored ->
                            new MavenLocalRepositoryResolver().resolve(descriptor, buildSupplier.get())
                                    .repository());
        } catch (Exception e) {
            log.debug("Repositorio local do Maven indisponivel: {}", e.getMessage());
        }
        return AnnotationProcessorPaths.resolve(declared, repository);
    }

    public IncrementalJavaBuilder withModuleListener(ModuleListener listener) {
        this.moduleListener = listener == null ? (module, index, total) -> { } : listener;
        return this;
    }

    public static Path stateDirectory(Path projectRoot) {
        return projectRoot.resolve(STATE_DIRECTORY);
    }

    public boolean isApplicable(JavaModule target) {
        return descriptor != null && descriptor.kind().isMaven() && target != null
                && buildSupplier.get() != null && hasCompiler();
    }

    public BuildResult build(JavaModule target, boolean includeTests, Consumer<String> output) {
        Instant start = Instant.now();
        List<JavaModule> order = WorkspaceModuleGraph.of(descriptor).buildOrderFor(target);
        List<ModulePlan> plans = new ArrayList<>();
        List<ModulePlan> pending = new ArrayList<>();
        for (JavaModule module : order) {
            ModulePlan plan = planOf(module, false);
            plans.add(plan);
            if (plan.full()) {
                pending.add(plan);
            }
        }

        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        int total = plans.size() + (includeTests ? 1 : 0);

        if (!pending.isEmpty()) {
            List<JavaModule> modules = new ArrayList<>();
            for (ModulePlan plan : pending) {
                modules.add(plan.module());
            }
            moduleListener.starting(pending.getFirst().module(), 1, total);
            BuildResult result = delegateAll(modules, false, output, pending.getFirst().reason());
            diagnostics.addAll(result.diagnostics());
            if (!result.successful()) {
                return new BuildResult(result.exitCode(), diagnostics,
                        Duration.between(start, Instant.now()), result.command());
            }
            for (ModulePlan plan : pending) {
                if (plan.fingerprint() != null) {
                    refreshState(plan.module(), false, plan.state(), plan.fingerprint(),
                            plan.classpath(), plan.stateFile());
                }
            }
        }

        for (int index = 0; index < plans.size(); index++) {
            ModulePlan plan = plans.get(index);
            if (plan.full()) {
                continue;
            }
            moduleListener.starting(plan.module(), index + 1, total);
            BuildResult result = plan.recompileAll()
                    ? recompileModule(plan, false, output)
                    : buildIncremental(plan, false, output);
            diagnostics.addAll(result.diagnostics());
            if (!result.successful()) {
                return new BuildResult(result.exitCode(), diagnostics,
                        Duration.between(start, Instant.now()), result.command());
            }
        }
        BuildResult resources = syncResources(target, plans, false, output);
        if (!resources.successful()) {
            diagnostics.addAll(resources.diagnostics());
            return new BuildResult(resources.exitCode(), diagnostics,
                    Duration.between(start, Instant.now()), resources.command());
        }
        if (includeTests) {
            moduleListener.starting(target, total, total);
            BuildResult result = buildTests(target, output);
            diagnostics.addAll(result.diagnostics());
            if (!result.successful()) {
                return new BuildResult(result.exitCode(), diagnostics,
                        Duration.between(start, Instant.now()), result.command());
            }
        }
        return new BuildResult(0, diagnostics, Duration.between(start, Instant.now()),
                "build incremental");
    }

    public void cancel() {
        runner.cancel();
        JavacDaemons.cancelAll();
    }

    public boolean isRunning() {
        return runner.isRunning();
    }

    private record ModulePlan(JavaModule module, String classpath, ModuleBuildState state,
                              String fingerprint, Path stateFile, boolean full, String reason,
                              boolean recompileAll) {

        ModulePlan(JavaModule module, String classpath, ModuleBuildState state, String fingerprint,
                   Path stateFile, boolean full, String reason) {
            this(module, classpath, state, fingerprint, stateFile, full, reason, false);
        }
    }

    public boolean isUpToDate(JavaModule target) {
        if (!isApplicable(target)) {
            return false;
        }
        for (JavaModule module : WorkspaceModuleGraph.of(descriptor).buildOrderFor(target)) {
            List<Path> sources = collectSources(module, false);
            Optional<ModulePlan> cached = cachedPlanOf(module, false, sources);
            if (cached.isEmpty()) {
                return false;
            }
            if (!cached.get().state().changes(module.root(), sources).isEmpty()) {
                return false;
            }
            if (!resourcesAreSynced(cached.get(), false)) {
                return false;
            }
        }
        return true;
    }

    private Optional<ModulePlan> cachedPlanOf(JavaModule module, boolean test, List<Path> sources) {
        Path stateFile = stateFileOf(module, test);
        ModuleBuildState state = ModuleBuildState.load(stateFile);
        String localFingerprint = localFingerprintOf(module);
        String storedClasspath = state.classpath();
        if (sources.isEmpty() && state.matchesLocally(localFingerprint)) {
            return Optional.of(new ModulePlan(module, storedClasspath, state, null, stateFile,
                    false, null));
        }
        if (state.isLocallyUsable(localFingerprint)
                && outputIsComplete(module, test, sources)
                && !storedClasspath.isBlank()
                && !ClasspathValidation.hasMissingJar(storedClasspath)
                && state.classpathFingerprint().equals(
                        ClasspathValidation.fingerprint(storedClasspath))) {
            return Optional.of(new ModulePlan(module, storedClasspath, state,
                    fingerprintOf(localFingerprint, storedClasspath), stateFile, false, null));
        }
        return Optional.empty();
    }

    private ModulePlan planOf(JavaModule module, boolean test) {
        List<Path> sources = collectSources(module, test);
        Optional<ModulePlan> cached = cachedPlanOf(module, test, sources);
        if (cached.isPresent()) {
            return cached.get();
        }
        Path stateFile = stateFileOf(module, test);
        ModuleBuildState state = ModuleBuildState.load(stateFile);
        String localFingerprint = localFingerprintOf(module);
        String storedClasspath = state.classpath();
        if (state.matchesLocally(localFingerprint) && !storedClasspath.isBlank()
                && !ClasspathValidation.hasMissingJar(storedClasspath)
                && state.classpathFingerprint().equals(ClasspathValidation.fingerprint(storedClasspath))) {
            ModulePlan plan = new ModulePlan(module, storedClasspath, state,
                    fingerprintOf(localFingerprint, storedClasspath), stateFile, false,
                    outputIsComplete(module, test, sources) ? "estado incremental ausente" : "saida incompleta",
                    true);
            log.info("Recompilacao do modulo {} com javac: {}", module.artifactId(), plan.reason());
            return plan;
        }

        Optional<String> classpath = classpathOf(module, test);
        if (classpath.isEmpty()) {
            return logged(new ModulePlan(module, null, state, null, stateFile, true,
                    "classpath nao resolvido"));
        }
        String fingerprint = fingerprintOf(module, classpath.get());
        boolean outputComplete = outputIsComplete(module, test, sources);
        boolean full = !state.isUsable(fingerprint) || !outputComplete;
        return logged(new ModulePlan(module, classpath.get(), state, fingerprint, stateFile, full,
                full ? outputComplete ? "estado incremental ausente" : "saida incompleta" : null));
    }

    private static ModulePlan logged(ModulePlan plan) {
        if (plan.full()) {
            log.info("Build completo de {}: {}", plan.module().artifactId(), plan.reason());
        }
        return plan;
    }

    private BuildResult recompileModule(ModulePlan plan, boolean test, Consumer<String> output) {
        JavaModule module = plan.module();
        List<Path> sources = collectSources(module, test);
        BuildResult result = sources.isEmpty() ? ok()
                : compile(module, test, new LinkedHashSet<>(sources), plan.classpath(),
                        outputDirOf(module, test), output);
        if (!result.successful()) {
            if (!result.diagnostics().isEmpty()) {
                return result;
            }
            log.info("javac falhou sem diagnosticos em {}; voltando para o Maven", module.artifactId());
            BuildResult fallback = delegate(module, test, output, "javac indisponivel");
            if (fallback.successful()) {
                refreshState(module, test, plan.state(), plan.fingerprint(), plan.classpath(),
                        plan.stateFile());
            }
            return fallback;
        }
        if (hasResources(module, test)) {
            BuildResult resources = copyResources(module, test, output);
            if (!resources.successful()) {
                return resources;
            }
        }
        refreshState(module, test, plan.state(), plan.fingerprint(), plan.classpath(),
                plan.stateFile());
        return result;
    }

    private BuildResult buildTests(JavaModule module, Consumer<String> output) {
        ModulePlan plan = planOf(module, true);
        if (!plan.full()) {
            BuildResult result = plan.recompileAll()
                    ? recompileModule(plan, true, output)
                    : buildIncremental(plan, true, output);
            return result.successful() ? syncResources(module, List.of(plan), true, output) : result;
        }
        if (plan.fingerprint() == null) {
            return delegateTests(plan, plan.reason(), output);
        }
        List<Path> sources = collectSources(module, true);
        BuildResult result = sources.isEmpty() ? ok()
                : compile(module, true, new LinkedHashSet<>(sources), plan.classpath(),
                        outputDirOf(module, true), output);
        if (!result.successful()) {
            if (!result.diagnostics().isEmpty()) {
                return result;
            }
            log.info("javac falhou sem diagnosticos nos testes de {}; voltando para o Maven",
                    module.artifactId());
            return delegateTests(plan, "javac indisponivel", output);
        }
        if (hasResources(module, true)) {
            BuildResult resources = copyResources(module, true, output);
            if (!resources.successful()) {
                return resources;
            }
        }
        refreshState(module, true, plan.state(), plan.fingerprint(), plan.classpath(),
                plan.stateFile());
        return result;
    }

    private BuildResult delegateTests(ModulePlan plan, String reason, Consumer<String> output) {
        BuildResult result = delegate(plan.module(), true, output, reason);
        if (result.successful() && plan.fingerprint() != null) {
            refreshState(plan.module(), true, plan.state(), plan.fingerprint(), plan.classpath(),
                    plan.stateFile());
        }
        return result;
    }

    private BuildResult syncResources(JavaModule target, List<ModulePlan> plans, boolean test,
                                      Consumer<String> output) {
        List<ModulePlan> stale = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        for (ModulePlan plan : plans) {
            if (plan.full()) {
                continue;
            }
            String current = resourcesFingerprint(plan.module(), test);
            if (!current.equals(plan.state().resourcesFingerprint())) {
                stale.add(plan);
                fingerprints.add(current);
            }
        }
        if (stale.isEmpty()) {
            return ok();
        }
        List<JavaModule> modules = new ArrayList<>();
        for (ModulePlan plan : stale) {
            modules.add(plan.module());
        }
        emit(output, "[" + labelOf(modules) + "] resources alterados");
        boolean inProcess = modules.stream().allMatch(this::usesDefaultResources);
        if (inProcess) {
            for (JavaModule module : modules) {
                BuildResult copied = copyResourcesInProcess(module, test, output);
                if (!copied.successful()) {
                    return copied;
                }
            }
        } else {
            BuildResult result = copyResourcesWithMaven(target, test, output);
            if (!result.successful()) {
                return result;
            }
        }
        for (int index = 0; index < stale.size(); index++) {
            ModuleBuildState state = stale.get(index).state();
            state.recordResources(fingerprints.get(index));
            state.save();
        }
        return ok();
    }

    private BuildResult copyResources(JavaModule module, boolean test, Consumer<String> output) {
        return usesDefaultResources(module)
                ? copyResourcesInProcess(module, test, output)
                : copyResourcesWithMaven(module, test, output);
    }

    private BuildResult copyResourcesInProcess(JavaModule module, boolean test, Consumer<String> output) {
        Path outputDir = outputDirOf(module, test);
        int copied = 0;
        try {
            for (Path root : resourceRootsOf(module, test)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(root)) {
                    files = walk.filter(Files::isRegularFile).toList();
                }
                for (Path file : files) {
                    Path destination = outputDir.resolve(root.relativize(file).toString());
                    if (isSameCopy(file, destination)) {
                        continue;
                    }
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        } catch (Exception e) {
            log.info("Copia de resources de {} falhou ({}); usando o Maven", module.artifactId(),
                    e.getMessage());
            return copyResourcesWithMaven(module, test, output);
        }
        emit(output, "[" + module.artifactId() + "] " + copied + " resource(s) copiado(s)"
                + (test ? " de teste" : ""));
        return ok();
    }

    private static boolean isSameCopy(Path source, Path destination) {
        try {
            return Files.isRegularFile(destination)
                    && Files.size(source) == Files.size(destination)
                    && Files.getLastModifiedTime(source).equals(Files.getLastModifiedTime(destination));
        } catch (Exception e) {
            return false;
        }
    }

    boolean usesDefaultResources(JavaModule module) {
        return resourceLayouts.computeIfAbsent(module.root(), ignored ->
                !MavenResourceLayout.customizes(module.root().resolve(JavaProjectConventions.POM_FILE))
                        && !MavenResourceLayout.customizes(
                                descriptor.root().resolve(JavaProjectConventions.POM_FILE)));
    }

    private BuildResult copyResourcesWithMaven(JavaModule target, boolean test, Consumer<String> output) {
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            return new BuildResult(-1, List.of(new BuildDiagnostic(null, 0, 0, null,
                    "Nenhum build system disponivel.", "build")), Duration.ZERO, "maven");
        }
        return build.executeToolCommand(target,
                List.of(test ? "resources:testResources" : "resources:resources"), output);
    }

    private boolean resourcesAreSynced(ModulePlan plan, boolean test) {
        return resourcesFingerprint(plan.module(), test).equals(plan.state().resourcesFingerprint());
    }

    private BuildResult buildIncremental(ModulePlan plan, boolean test, Consumer<String> output) {
        JavaModule module = plan.module();
        ModuleBuildState state = plan.state();
        List<Path> sources = collectSources(module, test);
        ModuleBuildState.Changes changes = state.changes(module.root(), sources);
        if (changes.isEmpty()) {
            if (state.isDirty()) {
                state.save();
            }
            emit(output, "[" + module.artifactId() + "] sem mudancas");
            return ok();
        }

        Set<Path> toCompile = compilationSet(module, state, changes, sources);
        if (toCompile.isEmpty()) {
            applyDeletions(module, test, state, changes);
            state.save();
            emit(output, "[" + module.artifactId() + "] apenas remocoes aplicadas");
            return ok();
        }

        BuildResult result = compile(module, test, toCompile, plan.classpath(),
                outputDirOf(module, test), output);
        if (result.successful()) {
            applyDeletions(module, test, state, changes);
            for (Path source : toCompile) {
                state.record(module.root(), source);
            }
            state.save();
            return result;
        }
        if (!result.diagnostics().isEmpty()) {
            return result;
        }
        log.info("javac falhou sem diagnosticos em {}; voltando para o Maven", module.artifactId());
        BuildResult fallback = delegate(module, test, output, "javac indisponivel");
        if (fallback.successful() && plan.fingerprint() != null) {
            refreshState(module, test, state, plan.fingerprint(), plan.classpath(), plan.stateFile());
        }
        return fallback;
    }

    private Set<Path> compilationSet(JavaModule module, ModuleBuildState state,
                                     ModuleBuildState.Changes changes, List<Path> sources) {
        Set<Path> selected = new LinkedHashSet<>(changes.touched());
        Set<String> types = new LinkedHashSet<>();
        for (Path source : changes.deleted()) {
            types.addAll(state.qualifiedTypesOf(module.root(), source));
        }
        for (Path source : changes.touched()) {
            types.addAll(state.qualifiedTypesOf(module.root(), source));
            types.addAll(qualifiedTopLevelTypesOf(source));
        }
        selected.addAll(state.dependentsOf(module.root(), types));
        selected.removeAll(changes.deleted());
        selected.removeIf(path -> !Files.isRegularFile(path));

        if (!sources.isEmpty() && selected.size() > sources.size() * FULL_MODULE_RATIO) {
            return new LinkedHashSet<>(sources);
        }
        return selected;
    }

    private BuildResult compile(JavaModule module, boolean test, Set<Path> sources,
                                String classpath, Path outputDir, Consumer<String> output) {
        JdkInstallation jdk = jdkSupplier.get();
        Instant start = Instant.now();
        Path argumentFile = null;
        try {
            Files.createDirectories(outputDir);
            Path generated = module.root().resolve(test ? GENERATED_TEST_SOURCES : GENERATED_SOURCES);
            Files.createDirectories(generated);

            List<String> command = new ArrayList<>();
            command.add(jdk.javacExecutable().toString());
            command.add("-d");
            command.add(outputDir.toString());
            command.add("-s");
            command.add(generated.toString());
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-proc:full");
            command.add("-implicit:none");
            command.add("-nowarn");
            command.add("-cp");
            command.add(classpath);
            List<Path> processors = processorPathOf(module);
            if (!processors.isEmpty()) {
                List<String> entries = new ArrayList<>();
                processors.forEach(processor -> entries.add(processor.toString()));
                entries.add(classpath);
                command.add("-processorpath");
                command.add(String.join(java.io.File.pathSeparator, entries));
            }
            JavacCommands.releaseArgument(descriptor, jdk, List.of()).ifPresent(release -> {
                command.add("--release");
                command.add(release);
            });

            if (sources.size() > JavacCommands.ARGUMENT_FILE_THRESHOLD) {
                argumentFile = JavacCommands.writeArgumentFile(sources);
                command.add("@" + argumentFile);
            } else {
                sources.forEach(source -> command.add(source.toString()));
            }

            BuildDiagnosticParser parser = new BuildDiagnosticParser(module.root());
            emit(output, "[" + module.artifactId() + "] javac " + sources.size()
                    + " arquivo(s)" + (test ? " de teste" : "") + " -> " + outputDir);

            int exitCode = javac.run(command, module.root(),
                    Map.of("JAVA_HOME", jdk.home().toString()), line -> {
                        parser.accept(line);
                        emit(output, line);
                    });

            return new BuildResult(exitCode, parser.diagnostics(),
                    Duration.between(start, Instant.now()), String.join(" ", command));
        } catch (Exception e) {
            log.debug("Falha no javac incremental de {}: {}", module.artifactId(), e.getMessage());
            return new BuildResult(-1, List.of(), Duration.between(start, Instant.now()), "javac");
        } finally {
            JavacCommands.deleteQuietly(argumentFile);
        }
    }

    private BuildResult delegate(JavaModule module, boolean test, Consumer<String> output,
                                 String reason) {
        return delegateAll(List.of(module), test, output, reason);
    }

    private BuildResult delegateAll(List<JavaModule> modules, boolean test,
                                    Consumer<String> output, String reason) {
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            return new BuildResult(-1, List.of(new BuildDiagnostic(null, 0, 0, null,
                    "Nenhum build system disponivel.", "build")), Duration.ZERO, "maven");
        }
        emit(output, "[" + labelOf(modules) + "] build completo (" + reason + ")");
        BuildSystem.BuildAction action = test
                ? BuildSystem.BuildAction.TEST_COMPILE
                : BuildSystem.BuildAction.COMPILE;
        JavaModule last = modules.isEmpty() ? null : modules.getLast();
        return build.execute(BuildRequest.of(action, last)
                .withModules(modules)
                .withSkipTests(true), output);
    }

    private static String labelOf(List<JavaModule> modules) {
        List<String> names = new ArrayList<>();
        for (JavaModule module : modules) {
            names.add(module.artifactId());
        }
        return String.join(", ", names);
    }

    private void refreshState(JavaModule module, boolean test, ModuleBuildState state,
                              String fingerprint, String classpath, Path stateFile) {
        state.reset(fingerprint, localFingerprintOf(module), classpath,
                ClasspathValidation.fingerprint(classpath));
        state.recordResources(resourcesFingerprint(module, test));
        for (Path source : collectSources(module, test)) {
            state.record(module.root(), source);
        }
        state.save();
        log.debug("Estado incremental regravado em {}", stateFile);
    }

    private void applyDeletions(JavaModule module, boolean test, ModuleBuildState state,
                                ModuleBuildState.Changes changes) {
        Path outputDir = outputDirOf(module, test);
        for (Path source : changes.deleted()) {
            removeClasses(module, test, source, outputDir);
            state.remove(module.root(), source);
        }
    }

    private void removeClasses(JavaModule module, boolean test, Path source, Path outputDir) {
        Optional<Path> relative = relativeToSourceRoot(module, test, source);
        if (relative.isEmpty()) {
            return;
        }
        String fileName = relative.get().getFileName().toString();
        String typeName = fileName.substring(0, fileName.length() - ".java".length());
        Path parent = relative.get().getParent();
        Path classDir = parent == null ? outputDir : outputDir.resolve(parent);
        if (!Files.isDirectory(classDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(classDir)) {
            files.filter(path -> {
                String name = path.getFileName().toString();
                return name.equals(typeName + ".class") || name.startsWith(typeName + "$");
            }).forEach(JavacCommands::deleteQuietly);
        } catch (Exception e) {
            log.debug("Nao foi possivel remover as classes de {}: {}", source, e.getMessage());
        }
    }

    private Optional<Path> relativeToSourceRoot(JavaModule module, boolean test, Path source) {
        for (Path root : test ? module.testRoots() : module.sourceRoots()) {
            if (source.startsWith(root)) {
                return Optional.of(root.relativize(source));
            }
        }
        return Optional.empty();
    }

    private Optional<String> classpathOf(JavaModule module, boolean test) {
        BuildSystem build = buildSupplier.get();
        if (build == null) {
            return Optional.empty();
        }
        Optional<String> classpath = test
                ? build.resolveTestClasspath(module)
                : build.resolveCompileClasspath(module);
        return classpath.filter(value -> !value.isBlank());
    }

    private String fingerprintOf(JavaModule module, String classpath) {
        return fingerprintOf(localFingerprintOf(module), classpath);
    }

    private static String fingerprintOf(String localFingerprint, String classpath) {
        return ModuleBuildState.fingerprintOf(localFingerprint,
                ClasspathValidation.fingerprint(classpath));
    }

    private String localFingerprintOf(JavaModule module) {
        JdkInstallation jdk = jdkSupplier.get();
        return ModuleBuildState.fingerprintOf(
                ModuleBuildState.FORMAT_VERSION,
                hashOfFile(module.root().resolve(JavaProjectConventions.POM_FILE)),
                hashOfFile(descriptor.root().resolve(JavaProjectConventions.POM_FILE)),
                jdk == null ? "" : jdk.home().toString());
    }

    private boolean outputIsComplete(JavaModule module, boolean test, List<Path> sources) {
        if (sources.isEmpty()) {
            return true;
        }
        Path outputDir = outputDirOf(module, test);
        if (!Files.isDirectory(outputDir)) {
            return false;
        }
        for (Path source : sources) {
            Optional<Path> relative = relativeToSourceRoot(module, test, source);
            if (relative.isEmpty()) {
                continue;
            }
            Path parent = relative.get().getParent();
            String fileName = relative.get().getFileName().toString();
            String stem = fileName.substring(0, fileName.length() - ".java".length());
            Path expected = parent == null ? outputDir.resolve(stem + ".class")
                    : outputDir.resolve(parent).resolve(stem + ".class");
            if (Files.isRegularFile(expected)) {
                continue;
            }
            for (String type : topLevelTypesOf(source)) {
                Path classFile = parent == null
                        ? outputDir.resolve(type + ".class")
                        : outputDir.resolve(parent).resolve(type + ".class");
                if (!Files.isRegularFile(classFile)) {
                    log.debug("Saida incremental incompleta: {} nao existe", classFile);
                    return false;
                }
            }
        }
        return true;
    }
    private static String hashOfFile(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? ModuleBuildState.hashOf(Files.readAllBytes(file))
                    : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hasResources(JavaModule module, boolean test) {
        return !resourceRootsOf(module, test).isEmpty();
    }

    private static List<Path> resourceRootsOf(JavaModule module, boolean test) {
        List<Path> roots = new ArrayList<>();
        for (Path root : test ? module.testRoots() : module.sourceRoots()) {
            if (root.getFileName() != null && root.getFileName().toString().equals("resources")
                    && Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static String resourcesFingerprint(JavaModule module, boolean test) {
        List<String> stamps = new ArrayList<>();
        for (Path root : resourceRootsOf(module, test)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                    try {
                        stamps.add(path + ":" + Files.size(path) + ":"
                                + Files.getLastModifiedTime(path).toMillis());
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                log.debug("Falha ao varrer {}: {}", root, e.getMessage());
            }
        }
        return ModuleBuildState.fingerprintOf(stamps.toArray(String[]::new));
    }

    private Path stateFileOf(JavaModule module, boolean test) {
        return stateDirectory(descriptor.root())
                .resolve(module.artifactId() + (test ? "-test" : "") + ".state");
    }

    private static Path outputDirOf(JavaModule module, boolean test) {
        return test ? module.root().resolve(TEST_OUTPUT_DIR) : module.outputDir();
    }

    private static List<Path> collectSources(JavaModule module, boolean test) {
        List<Path> sources = new ArrayList<>();
        for (Path root : test ? module.existingTestRoots() : module.existingSourceRoots()) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(sources::add);
            } catch (Exception e) {
                log.debug("Falha ao varrer {}: {}", root, e.getMessage());
            }
        }
        return sources;
    }

    private static Set<String> qualifiedTopLevelTypesOf(Path source) {
        try {
            return new LinkedHashSet<>(ModuleBuildState.topLevelQualifiedTypesDeclaredIn(
                    Files.readString(source)));
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static List<String> topLevelTypesOf(Path source) {
        try {
            return ModuleBuildState.topLevelTypesDeclaredIn(Files.readString(source));
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean hasCompiler() {
        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        return jdk != null && jdk.isJdk();
    }

    private static BuildResult ok() {
        return new BuildResult(0, List.of(), Duration.ZERO, "build incremental");
    }

    private static void emit(Consumer<String> output, String line) {
        if (output != null) {
            output.accept(line);
        }
    }
}
