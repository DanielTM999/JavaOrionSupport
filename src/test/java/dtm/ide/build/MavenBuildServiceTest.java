package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenBuildServiceTest {

    @TempDir
    Path root;

    @Test
    void commandForcesColorAfterBatchMode() {
        JavaModule module = rootModule();
        MavenBuildService service = service(descriptor(module));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, module));

        assertEquals(List.of(executable().toString(), "-B", "-Dstyle.color=always", "compile"),
                command);
    }

    @Test
    void aSubmoduleIsBuiltWithProjectListAndAlsoMake() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api));

        assertTrue(command.contains("-pl"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void alsoMakeCanBeTurnedOffForASingleModule() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withAlsoMake(false));

        assertTrue(command.contains("-pl"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertFalse(command.contains("-am"));
    }

    @Test
    void severalModulesAreSelectedInASingleReactor() {
        JavaModule core = module(root.resolve("core"));
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), core, api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withModules(List.of(core, api)));

        assertTrue(command.contains("-pl"));
        assertEquals("core,api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void theAggregatorIsDroppedFromTheModuleSelection() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.buildCommand(BuildRequest.of(
                BuildSystem.BuildAction.COMPILE, api).withModules(List.of(rootModule(), api)));

        assertEquals("api", command.get(command.indexOf("-pl") + 1));
    }

    @Test
    void reactorClasspathRunsTheCompilePhaseWithoutCompiling() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.reactorClasspathCommand(api, "runtime");

        assertTrue(command.indexOf("compile") < command.indexOf("dependency:build-classpath"));
        assertTrue(command.contains("-Dmaven.main.skip=true"));
        assertTrue(command.contains("-Dmaven.resources.skip=true"));
        assertFalse(command.contains("-Dmaven.test.skip=true"));
        assertTrue(command.contains("-Dmdep.includeScope=runtime"));
        assertEquals("api", command.get(command.indexOf("-pl") + 1));
        assertTrue(command.contains("-am"));
    }

    @Test
    void reactorTestClasspathRunsTheTestCompilePhaseWithoutCompiling() {
        JavaModule api = module(root.resolve("api"));
        MavenBuildService service = service(descriptor(rootModule(), api));

        List<String> command = service.reactorClasspathCommand(api, "test");

        assertTrue(command.contains("test-compile"));
        assertFalse(command.contains("compile"));
        assertTrue(command.contains("-Dmaven.main.skip=true"));
        assertTrue(command.contains("-Dmaven.test.skip=true"));
        assertTrue(command.contains("-Dmdep.includeScope=test"));
    }

    @Test
    void selectedDependencyRefreshUsesTargetedPurgeAndForcesResolution() {
        MavenBuildService service = service(descriptor(rootModule()));
        List<String> arguments = service.dependencyRefreshArguments(
                DependencyCoordinate.of("com.github.demo", "library", "1.0"));

        assertTrue(arguments.contains("-U"));
        assertTrue(arguments.contains("dependency:purge-local-repository"));
        assertTrue(arguments.contains("-DmanualInclude=com.github.demo:library"));
        assertTrue(arguments.contains("dependency:resolve"));
    }

    @Test
    void projectRefreshDoesNotRestrictThePurgeToOneArtifact() {
        List<String> arguments = service(descriptor(rootModule())).dependencyRefreshArguments(null);

        assertFalse(arguments.stream().anyMatch(argument -> argument.startsWith("-DmanualInclude=")));
    }
    @Test
    void aPersistedClasspathIsReusedUntilAPomChanges() throws Exception {
        JavaModule module = rootModule();
        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path jar = java.nio.file.Files.writeString(root.resolve("dependency.jar"), "jar");
        MavenBuildService service = service(descriptor(module));
        Path persisted = root.resolve(".orion/classpath/" + module.artifactId() + "-runtime.classpath");
        java.nio.file.Files.createDirectories(persisted.getParent());
        java.nio.file.Files.writeString(persisted,
                service.persistentFingerprint(module, "runtime") + "\n" + jar + "\n");

        assertEquals(java.util.Optional.of(module.outputDir() + java.io.File.pathSeparator + jar),
                service.resolveRuntimeClasspath(module));

        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project><!-- nova --></project>");
        MavenBuildService afterPomChange = service(descriptor(module));

        assertTrue(afterPomChange.resolveRuntimeClasspath(module).isEmpty());
    }

    @Test
    void toolCommandOptionsReachTheMavenProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        java.nio.file.Files.writeString(executable(),
                "@echo ARGS %*\r\n@echo OPTS %MAVEN_OPTS%\r\n");
        MavenBuildService service = service(descriptor(rootModule()));
        List<String> output = new java.util.ArrayList<>();

        BuildResult result = service.executeToolCommand(null, List.of("exec:java"),
                new BuildCommand.Options(List.of(), List.of("-DskipTests"), true,
                        java.util.Map.of("MAVEN_OPTS", "-Dorion.debug=on")), output::add);

        assertTrue(result.successful(), String.join("\n", output));
        String args = output.stream().filter(line -> line.startsWith("ARGS")).findFirst().orElse("");
        assertTrue(args.contains("exec:java"), args);
        assertTrue(args.contains("-o"), args);
        assertTrue(args.contains("-DskipTests"), args);
        assertTrue(output.contains("OPTS -Dorion.debug=on"), String.join("\n", output));
    }

    @Test
    void multimoduleResolutionUsesCurrentReactorInsteadOfInstalledSiblingPoms() throws Exception {
        JavaModule app = module(root.resolve("app"));
        Path currentJar = java.nio.file.Files.writeString(root.resolve("current.jar"), "jar");
        var calls = new java.util.ArrayList<List<String>>();
        MavenBuildService service = service(descriptor(rootModule(), app), (command, directory, env, out) -> {
            calls.add(command);
            assertEquals(root, directory);
            assertTrue(command.contains("-am"));
            assertEquals("app", command.get(command.indexOf("-pl") + 1));
            return writeClasspath(command, directory, currentJar.toString());
        });
        assertTrue(service.resolveRuntimeClasspath(app).orElseThrow().contains(currentJar.toString()));
        assertEquals(1, calls.size());
    }

    @Test
    void failedReactorResolutionCannotFallBackToObsoleteInstalledDependencies() {
        JavaModule app = module(root.resolve("app"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MavenBuildService service = service(descriptor(rootModule(), app), (command, directory, env, out) -> {
            calls.incrementAndGet();
            assertEquals(root, directory);
            out.accept("[ERROR] Current reactor dependency is unavailable");
            return 1;
        });
        assertTrue(service.resolveRuntimeClasspath(app).isEmpty());
        assertEquals(1, calls.get());
        assertTrue(service.lastClasspathFailure(app).orElseThrow().contains("reactor dependency"));
    }

    @Test
    void pomChangesInvalidateTheClasspathAlreadyLoadedInMemory() throws Exception {
        JavaModule app = module(root.resolve("app"));
        java.nio.file.Files.createDirectories(app.root());
        Path pom = java.nio.file.Files.writeString(app.root().resolve("pom.xml"), "<project/>");
        Path first = java.nio.file.Files.writeString(root.resolve("first.jar"), "jar");
        Path added = java.nio.file.Files.writeString(root.resolve("added.jar"), "jar");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MavenBuildService service = service(descriptor(rootModule(), app), (command, directory, env, out) ->
                writeClasspath(command, directory, calls.incrementAndGet() == 1 ? first.toString() : added.toString()));
        assertTrue(service.resolveRuntimeClasspath(app).orElseThrow().contains(first.toString()));
        assertTrue(service.resolveRuntimeClasspath(app).orElseThrow().contains(first.toString()));
        assertEquals(1, calls.get());
        java.nio.file.Files.writeString(pom, "<project><!-- added dependency --></project>");
        assertTrue(service.resolveRuntimeClasspath(app).orElseThrow().contains(added.toString()));
        assertEquals(2, calls.get());
    }

    @Test
    void deletedJarsCannotRemainInTheInMemoryClasspath() throws Exception {
        JavaModule module = rootModule();
        Path first = java.nio.file.Files.writeString(root.resolve("first.jar"), "jar");
        Path second = java.nio.file.Files.writeString(root.resolve("second.jar"), "jar");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MavenBuildService service = service(descriptor(module), (command, directory, env, out) ->
                writeClasspath(command, directory, calls.incrementAndGet() == 1 ? first.toString() : second.toString()));
        assertTrue(service.resolveRuntimeClasspath(module).orElseThrow().contains(first.toString()));
        java.nio.file.Files.delete(first);
        assertTrue(service.resolveRuntimeClasspath(module).orElseThrow().contains(second.toString()));
        assertEquals(2, calls.get());
    }

    @Test
    void clearingProjectCachesDropsMemoryPersistedAndIncrementalData() throws Exception {
        JavaModule module = rootModule();
        Path first = java.nio.file.Files.writeString(root.resolve("first.jar"), "jar");
        Path second = java.nio.file.Files.writeString(root.resolve("second.jar"), "jar");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MavenBuildService service = service(descriptor(module), (command, directory, env, out) ->
                writeClasspath(command, directory, calls.incrementAndGet() == 1 ? first.toString() : second.toString()));
        assertTrue(service.resolveRuntimeClasspath(module).orElseThrow().contains(first.toString()));
        Path incremental = root.resolve(".orion/incremental/state.txt");
        java.nio.file.Files.createDirectories(incremental.getParent());
        java.nio.file.Files.writeString(incremental, "cached");
        ProjectBuildCaches.invalidate(service, root);
        assertFalse(java.nio.file.Files.exists(incremental));
        try (var files = java.nio.file.Files.list(root.resolve(".orion/classpath"))) {
            assertEquals(0, files.count());
        }
        assertTrue(service.resolveRuntimeClasspath(module).orElseThrow().contains(second.toString()));
        assertEquals(2, calls.get());
    }

    @Test
    void noExternalDependenciesIsAValidClasspathAndCanBePersisted() {
        JavaModule module = rootModule();
        MavenBuildService service = service(descriptor(module), (command, directory, env, out) ->
                writeClasspath(command, directory, ""));
        assertEquals(module.outputDir().toString(), service.resolveRuntimeClasspath(module).orElseThrow());
        MavenBuildService reopened = service(descriptor(module), (command, directory, env, out) -> {
            throw new AssertionError("The successful empty dependency list should be reusable");
        });
        assertEquals(module.outputDir().toString(), reopened.resolveRuntimeClasspath(module).orElseThrow());
    }

    @Test
    void clearingDuringResolutionPreventsTheOldResultFromRepopulatingCaches() throws Exception {
        JavaModule module = rootModule();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var answer = new java.util.concurrent.atomic.AtomicReference<java.util.Optional<String>>();
        MavenBuildService service = service(descriptor(module), (command, directory, env, out) -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return writeClasspath(command, directory, "");
            } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        Thread resolving = Thread.ofVirtual().start(() -> answer.set(service.resolveRuntimeClasspath(module)));
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            service.invalidateClasspathCache();
        } finally { release.countDown(); }
        assertTrue(resolving.join(java.time.Duration.ofSeconds(5)));
        assertEquals(java.util.Optional.empty(), answer.get());
        Path persisted = root.resolve(".orion/classpath/" + module.artifactId() + "-runtime.classpath");
        assertFalse(java.nio.file.Files.exists(persisted));
        assertEquals(module.outputDir().toString(), service.resolveRuntimeClasspath(module).orElseThrow());
    }

    @Test
    void aRecreatedServiceDoesNotShareThePendingReactorsOutputFile() throws Exception {
        JavaModule app = module(root.resolve("app"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pendingFile = new java.util.concurrent.atomic.AtomicReference<Path>();
        MavenBuildService previous = service(descriptor(rootModule(), app), (command, directory, env, out) -> {
            String relative = command.stream().filter(arg -> arg.startsWith("-Dmdep.outputFile="))
                    .findFirst().orElseThrow().substring("-Dmdep.outputFile=".length());
            pendingFile.set(app.root().resolve(relative));
            entered.countDown();
            try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return writeClasspath(command, directory, "");
            } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        Thread pending = Thread.ofVirtual().start(() -> previous.resolveRuntimeClasspath(app));
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            previous.invalidateClasspathCache();
            MavenBuildService recreated = service(descriptor(rootModule(), app), (command, directory, env, out) ->
                    writeClasspath(command, directory, ""));
            assertTrue(recreated.resolveRuntimeClasspath(app).isPresent());
            assertTrue(java.nio.file.Files.exists(pendingFile.get()), "The new service must not delete the old query's file");
        } finally { release.countDown(); }
        assertTrue(pending.join(java.time.Duration.ofSeconds(5)));
    }

    private static int writeClasspath(List<String> command, Path directory, String classpath) {
        try {
            String output = command.stream().filter(arg -> arg.startsWith("-Dmdep.outputFile="))
                    .findFirst().orElseThrow().substring("-Dmdep.outputFile=".length());
            Path moduleDirectory = command.contains("-pl")
                    ? directory.resolve(command.get(command.indexOf("-pl") + 1)) : directory;
            Path file = moduleDirectory.resolve(output);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, classpath);
            return 0;
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private MavenBuildService service(JavaProjectDescriptor descriptor) {
        return service(descriptor, null);
    }

    private MavenBuildService service(JavaProjectDescriptor descriptor, MavenBuildService.ClasspathCommand runner) {
        BuildToolProvisioner provisioner = new BuildToolProvisioner(null, null) {
            @Override
            public BuildTool ensureMaven(JavaProjectDescriptor ignored,
                                         DownloadProgressListener listener) {
                return new BuildTool(executable(), ToolOrigin.PATH);
            }
        };
        return new MavenBuildService(descriptor, provisioner, null, null, runner);
    }

    private Path executable() {
        return root.resolve("mvn.cmd");
    }

    private JavaProjectDescriptor descriptor(JavaModule... modules) {
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(modules), true,
                false, 21, null);
    }

    private JavaModule rootModule() {
        return module(root);
    }

    private JavaModule module(Path moduleRoot) {
        return new JavaModule(moduleRoot, moduleRoot.getFileName().toString(), "com.example",
                moduleRoot.getFileName().toString(), "jar",
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                moduleRoot.resolve("target/classes"));
    }
}
