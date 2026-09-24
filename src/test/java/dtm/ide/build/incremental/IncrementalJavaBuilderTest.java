package dtm.ide.build.incremental;

import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildRequest;
import dtm.ide.build.BuildResult;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalJavaBuilderTest {

    @TempDir
    Path root;

    private final RecordingBuildSystem build = new RecordingBuildSystem();
    private final List<List<String>> javacCalls = new ArrayList<>();

    private JdkInstallation jdk;

    @BeforeEach
    void setUp() throws Exception {
        jdk = new JdkInstallation(root.resolve("jdk"), JdkVendor.TEMURIN, 21, "21.0.4",
                JdkInstallation.JdkOrigin.MANAGED);
        Files.createDirectories(jdk.javacExecutable().getParent());
        Files.writeString(jdk.javacExecutable(), "", StandardCharsets.UTF_8);
        pom("web");
        Files.writeString(rootPom(), "<project/>", StandardCharsets.UTF_8);
        Files.createDirectories(module().outputDir());
    }

    @Test
    void theFirstBuildDelegatesToMavenAndSeedsTheState() {
        source("Lojista.java", "package a; public class Lojista {}");

        BuildResult result = builder().build(module(), false, line -> {
        });

        assertTrue(result.successful());
        assertEquals(1, build.requests.size());
        assertTrue(build.requests.getFirst().alsoMake());
        assertTrue(javacCalls.isEmpty());
        assertTrue(Files.isRegularFile(root.resolve(".orion/incremental/web.state")));
    }

    @Test
    void aSecondBuildWithoutChangesRunsNothing() {
        source("Lojista.java", "package a; public class Lojista {}");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        int resolutionsAfterFirstBuild = build.classpathResolutions;

        BuildResult result = builder().build(module(), false, line -> {
        });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertTrue(javacCalls.isEmpty());
        assertEquals(resolutionsAfterFirstBuild, build.classpathResolutions);
    }

    @Test
    void missingCompiledOutputForcesAFullBuild() throws Exception {
        source("Lojista.java", "package a; public class Lojista {}");
        builder().build(module(), false, line -> { });
        build.requests.clear();
        Files.delete(module().outputDir().resolve("a/Lojista.class"));

        builder().build(module(), false, line -> { });

        assertEquals(1, build.requests.size());
    }

    @Test
    void nestedTypesDoNotMakeTheOutputLookIncomplete() {
        source("Lojista.java", "package a; public class Lojista { record Endereco(String rua) {} "
                + "enum Tipo { A } static class Interna {} }");
        builder().build(module(), false, line -> { });
        build.requests.clear();

        BuildResult result = builder().build(module(), false, line -> { });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertTrue(javacCalls.isEmpty());
    }

    @Test
    void aModuleWithoutSourcesIsNotRebuiltOnEveryOpen() throws Exception {
        Files.createDirectories(root.resolve("web/src/main/java"));
        builder().build(module(), false, line -> { });
        build.requests.clear();
        int resolutions = build.classpathResolutions;

        builder().build(module(), false, line -> { });

        assertTrue(build.requests.isEmpty());
        assertEquals(resolutions, build.classpathResolutions);
    }

    @Test
    void upToDateReflectsTheCachedState() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista { class Item {} }");
        assertFalse(builder().isUpToDate(module()));
        builder().build(module(), false, line -> { });
        int resolutions = build.classpathResolutions;

        assertTrue(builder().isUpToDate(module()));
        assertEquals(resolutions, build.classpathResolutions);

        Files.writeString(lojista, "package a; public class Lojista { void extra() {} }",
                StandardCharsets.UTF_8);
        assertFalse(builder().isUpToDate(module()));
    }

    @Test
    void republishedJarAtTheSamePathInvalidatesTheState() throws Exception {
        source("Lojista.java", "package a; public class Lojista {}");
        Path dependency = Files.writeString(root.resolve("dependency.jar"), "first",
                StandardCharsets.UTF_8);
        build.classpath = dependency.toString();
        builder().build(module(), false, line -> { });
        build.requests.clear();

        Files.writeString(dependency, "a different artifact payload", StandardCharsets.UTF_8);
        builder().build(module(), false, line -> { });

        assertEquals(1, build.requests.size());
    }

    @Test
    void anEditedSourceIsCompiledWithJavacTogetherWithItsReferences() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        Path service = source("LojistaService.java",
                "package a; public class LojistaService { Lojista lojista; }");
        source("Outro.java", "package a; public class Outro {}");
        source("Mais.java", "package a; public class Mais {}");
        source("Ainda.java", "package a; public class Ainda {}");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        Files.writeString(lojista, "package a; public class Lojista { void extra() {} }",
                StandardCharsets.UTF_8);

        BuildResult result = builder().build(module(), false, line -> {
        });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertEquals(1, javacCalls.size());
        List<String> command = javacCalls.getFirst();
        assertTrue(command.contains(lojista.toString()));
        assertTrue(command.contains(service.toString()));
        assertTrue(command.contains("-proc:full"));
        assertTrue(command.contains("-implicit:none"));
        assertTrue(command.contains(module().outputDir().toString()));
        assertTrue(command.contains(build.classpath));
    }

    @Test
    void changingThePomForcesTheFullMavenBuild() throws Exception {
        source("Lojista.java", "package a; public class Lojista {}");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        Files.writeString(pomFile(), "<project><!-- nova dependencia --></project>",
                StandardCharsets.UTF_8);

        builder().build(module(), false, line -> {
        });

        assertEquals(1, build.requests.size());
        assertTrue(javacCalls.isEmpty());
    }

    @Test
    void changingAResourceOnlySyncsTheResources() throws Exception {
        source("Lojista.java", "package a; public class Lojista {}");
        resource("application.properties", "a=1");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        resource("application.properties", "a=22");

        BuildResult result = builder().build(module(), false, line -> {
        });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertTrue(javacCalls.isEmpty());
        assertEquals(List.of(List.of("resources:resources")), build.toolCommands);

        build.toolCommands.clear();
        builder().build(module(), false, line -> {
        });

        assertTrue(build.toolCommands.isEmpty());
        assertTrue(build.requests.isEmpty());
    }

    @Test
    void aResourceRewrittenByTheMavenBuildDoesNotForceTheNextBuild() {
        source("Lojista.java", "package a; public class Lojista {}");
        build.duringBuild = () -> resource("native.dll", "binario " + System.nanoTime());
        builder().build(module(), true, line -> {
        });
        build.duringBuild = () -> {
        };
        build.requests.clear();
        build.toolCommands.clear();
        javacCalls.clear();

        BuildResult result = builder().build(module(), true, line -> {
        });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertTrue(build.toolCommands.isEmpty());
        assertTrue(javacCalls.isEmpty());
    }

    @Test
    void theTestStepCompilesTestsWithJavacWithoutRelaunchingTheMavenLifecycle() {
        source("Lojista.java", "package a; public class Lojista {}");
        Path test = testSource("LojistaTest.java", "package a; class LojistaTest { Lojista l; }");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();

        BuildResult result = builder().build(module(), true, line -> {
        });

        assertTrue(result.successful());
        assertTrue(build.requests.isEmpty());
        assertEquals(1, javacCalls.size());
        assertTrue(javacCalls.getFirst().contains(test.toString()));
        assertTrue(javacCalls.getFirst().contains(
                root.resolve("web/target/test-classes").toString()));
    }

    @Test
    void aSecondTestBuildWithoutChangesRunsNothing() throws Exception {
        source("Lojista.java", "package a; public class Lojista {}");
        testSource("LojistaTest.java", "package a; class LojistaTest {}");
        write(root.resolve("web/src/test/resources/dados.json"), "{}");
        builder().build(module(), true, line -> {
        });
        Files.createDirectories(root.resolve("web/target/test-classes/a"));
        Files.writeString(root.resolve("web/target/test-classes/a/LojistaTest.class"), "");
        build.requests.clear();
        build.toolCommands.clear();
        javacCalls.clear();

        builder().build(module(), true, line -> {
        });

        assertTrue(build.requests.isEmpty());
        assertTrue(build.toolCommands.isEmpty());
        assertTrue(javacCalls.isEmpty());
    }

    @Test
    void deletingASourceRemovesItsCompiledClasses() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        source("Outro.java", "package a; public class Outro {}");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        Path classFile = module().outputDir().resolve("a/Lojista.class");
        Path innerClass = module().outputDir().resolve("a/Lojista$Inner.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "", StandardCharsets.UTF_8);
        Files.writeString(innerClass, "", StandardCharsets.UTF_8);
        Files.delete(lojista);

        builder().build(module(), false, line -> {
        });

        assertFalse(Files.exists(classFile));
        assertFalse(Files.exists(innerClass));
    }

    @Test
    void aJavacFailureWithoutDiagnosticsFallsBackToMaven() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        builder().build(module(), false, line -> {
        });
        build.requests.clear();
        Files.writeString(lojista, "package a; public class Lojista { void extra() {} }",
                StandardCharsets.UTF_8);

        BuildResult result = builder(-1).build(module(), false, line -> {
        });

        assertTrue(result.successful());
        assertEquals(1, javacCalls.size());
        assertEquals(1, build.requests.size());
    }

    @Test
    void theWholeDependencyChainIsVisited() {
        pom("persistence");
        write(pomFile(), "<project><groupId>com.example</groupId><artifactId>web</artifactId>"
                + "<dependencies><dependency><groupId>com.example</groupId>"
                + "<artifactId>persistence</artifactId></dependency></dependencies></project>");
        source("Lojista.java", "package a; public class Lojista {}");

        builder(descriptorWith("web", "persistence")).build(module(), false, line -> {
        });

        assertEquals(1, build.requests.size());
        assertTrue(build.requests.getFirst().alsoMake());
        assertEquals(List.of("persistence", "web"), build.requests.getFirst().modules().stream()
                .map(JavaModule::artifactId).toList());
    }

    @Test
    void onlyTheModulesNeedingAFullBuildEnterTheBatch() {
        pom("persistence");
        write(root.resolve("persistence/src/main/java/a/Cliente.java"),
                "package a; public class Cliente {}");
        write(pomFile(), webPomWithPersistence(""));
        source("Lojista.java", "package a; public class Lojista {}");
        builder(descriptorWith("web", "persistence")).build(module(), false, line -> {
        });
        build.requests.clear();
        write(pomFile(), webPomWithPersistence("<!-- nova dependencia -->"));

        builder(descriptorWith("web", "persistence")).build(module(), false, line -> {
        });

        assertEquals(1, build.requests.size());
        assertEquals(List.of("web"), build.requests.getFirst().modules().stream()
                .map(JavaModule::artifactId).toList());
        assertTrue(javacCalls.isEmpty());
    }

    private String webPomWithPersistence(String extra) {
        return "<project><groupId>com.example</groupId><artifactId>web</artifactId>"
                + "<dependencies><dependency><groupId>com.example</groupId>"
                + "<artifactId>persistence</artifactId></dependency></dependencies>"
                + extra + "</project>";
    }

    @Test
    void theModuleListenerAnnouncesTheBatchOnItsFirstModule() {
        pom("persistence");
        write(pomFile(), "<project><groupId>com.example</groupId><artifactId>web</artifactId>"
                + "<dependencies><dependency><groupId>com.example</groupId>"
                + "<artifactId>persistence</artifactId></dependency></dependencies></project>");
        source("Lojista.java", "package a; public class Lojista {}");
        List<String> steps = new ArrayList<>();

        builder(descriptorWith("web", "persistence"))
                .withModuleListener((module, index, total) ->
                        steps.add(module.artifactId() + " " + index + "/" + total))
                .build(module(), false, line -> {
                });

        assertEquals(List.of("persistence 1/2"), steps);
    }

    @Test
    void theTestStepIsCountedAsAnExtraModuleStep() {
        source("Lojista.java", "package a; public class Lojista {}");
        List<String> steps = new ArrayList<>();

        builder(descriptor())
                .withModuleListener((module, index, total) ->
                        steps.add(module.artifactId() + " " + index + "/" + total))
                .build(module(), true, line -> {
                });

        assertEquals(List.of("web 1/2", "web 2/2"), steps);
    }

    @Test
    void aSingleModuleProjectSeedsItsStateWithoutProjectList() {
        singleModuleSource("Lojista.java", "package a; public class Lojista {}");

        BuildResult result = builder(singleModuleDescriptor())
                .build(singleModule(), false, line -> {
                });

        assertTrue(result.successful());
        assertEquals(1, build.requests.size());
        assertEquals(root, build.requests.getFirst().module().root());
        assertTrue(javacCalls.isEmpty());
        assertTrue(Files.isRegularFile(root.resolve(".orion/incremental/demo.state")));
    }

    @Test
    void aSingleModuleProjectSkipsTheBuildWhenNothingChanged() {
        singleModuleSource("Lojista.java", "package a; public class Lojista {}");
        builder(singleModuleDescriptor()).build(singleModule(), false, line -> {
        });
        build.requests.clear();

        builder(singleModuleDescriptor()).build(singleModule(), false, line -> {
        });

        assertTrue(build.requests.isEmpty());
        assertTrue(javacCalls.isEmpty());
    }

    @Test
    void aSingleModuleProjectCompilesOnlyTheEditedSource() throws Exception {
        Path lojista = singleModuleSource("Lojista.java", "package a; public class Lojista {}");
        singleModuleSource("Outro.java", "package a; public class Outro {}");
        singleModuleSource("Mais.java", "package a; public class Mais {}");
        singleModuleSource("Ainda.java", "package a; public class Ainda {}");
        builder(singleModuleDescriptor()).build(singleModule(), false, line -> {
        });
        build.requests.clear();
        Files.writeString(lojista, "package a; public class Lojista { void extra() {} }",
                StandardCharsets.UTF_8);

        builder(singleModuleDescriptor()).build(singleModule(), false, line -> {
        });

        assertTrue(build.requests.isEmpty());
        assertEquals(1, javacCalls.size());
        assertTrue(javacCalls.getFirst().contains(lojista.toString()));
    }

    @Test
    void gradleAndPlainProjectsKeepTheOriginalBuild() {
        for (JavaProjectKind kind : List.of(JavaProjectKind.GRADLE,
                JavaProjectKind.GRADLE_MULTIPROJECT, JavaProjectKind.PLAIN_JAVA)) {
            JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root, kind,
                    List.of(module()), false, false, 21, null);

            assertFalse(new IncrementalJavaBuilder(descriptor, () -> build, () -> jdk)
                    .isApplicable(module()));
        }
    }

    @Test
    void withoutACompilerTheOriginalBuildIsKept() {
        assertFalse(new IncrementalJavaBuilder(descriptor(), () -> build,
                () -> new JdkInstallation(root.resolve("jre"), JdkVendor.TEMURIN, 21, "21.0.4",
                        JdkInstallation.JdkOrigin.MANAGED)).isApplicable(module()));
    }

    private JavaProjectDescriptor singleModuleDescriptor() {
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN, List.of(singleModule()),
                true, false, 21, null);
    }

    private JavaModule singleModule() {
        return new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java"), root.resolve("src/main/resources")),
                List.of(root.resolve("src/test/java")), root.resolve("target/classes"));
    }

    private Path singleModuleSource(String name, String content) {
        return write(root.resolve("src/main/java/a").resolve(name), content);
    }

    private IncrementalJavaBuilder builder() {
        return builder(0);
    }

    private IncrementalJavaBuilder builder(int javacExit) {
        return builder(descriptor()).withJavac((command, directory, environment, output) -> {
            javacCalls.add(List.copyOf(command));
            return javacExit;
        });
    }

    private IncrementalJavaBuilder builder(JavaProjectDescriptor descriptor) {
        return new IncrementalJavaBuilder(descriptor, () -> build, () -> jdk)
                .withJavac((command, directory, environment, output) -> {
                    javacCalls.add(List.copyOf(command));
                    return 0;
                });
    }

    private JavaProjectDescriptor descriptor() {
        return descriptorWith("web");
    }

    private JavaProjectDescriptor descriptorWith(String... artifactIds) {
        List<JavaModule> modules = new ArrayList<>();
        for (String artifactId : artifactIds) {
            modules.add(module(artifactId));
        }
        return new JavaProjectDescriptor(root, JavaProjectKind.MAVEN_MULTIMODULE, modules,
                true, false, 21, null);
    }

    private JavaModule module() {
        return module("web");
    }

    private JavaModule module(String artifactId) {
        Path moduleRoot = root.resolve(artifactId);
        return new JavaModule(moduleRoot, artifactId, "com.example", artifactId, "jar",
                List.of(moduleRoot.resolve("src/main/java"),
                        moduleRoot.resolve("src/main/resources")),
                List.of(moduleRoot.resolve("src/test/java")),
                moduleRoot.resolve("target/classes"));
    }

    private Path rootPom() {
        return root.resolve("pom.xml");
    }

    private Path pomFile() {
        return root.resolve("web/pom.xml");
    }

    private void pom(String artifactId) {
        write(root.resolve(artifactId).resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>" + artifactId
                        + "</artifactId></project>");
    }

    private Path source(String name, String content) {
        return write(root.resolve("web/src/main/java/a").resolve(name), content);
    }

    private Path testSource(String name, String content) {
        return write(root.resolve("web/src/test/java/a").resolve(name), content);
    }

    private void resource(String name, String content) {
        write(root.resolve("web/src/main/resources").resolve(name), content);
    }

    private Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class RecordingBuildSystem implements BuildSystem {

        private final List<BuildRequest> requests = new ArrayList<>();
        private final List<List<String>> toolCommands = new ArrayList<>();
        private String classpath = "classpath-resolvido";
        private int classpathResolutions;
        private Runnable duringBuild = () -> {
        };

        @Override
        public String name() {
            return "maven";
        }

        @Override
        public BuildResult executeToolCommand(JavaModule module, List<String> command,
                                              Consumer<String> output) {
            toolCommands.add(List.copyOf(command));
            return new BuildResult(0, List.of(), Duration.ZERO, "maven");
        }

        @Override
        public BuildResult execute(BuildRequest request, Consumer<String> output) {
            requests.add(request);
            duringBuild.run();
            for (JavaModule module : request.selection()) {
                try {
                    Files.createDirectories(module.outputDir());
                    writeClassOutputs(module);
                } catch (Exception ignored) {
                }
            }
            return new BuildResult(0, List.of(), Duration.ZERO, "maven");
        }

        private void writeClassOutputs(JavaModule module) throws Exception {
            for (Path sourceRoot : module.existingSourceRoots()) {
                try (var paths = Files.walk(sourceRoot)) {
                    for (Path source : paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .toList()) {
                        Path relative = sourceRoot.relativize(source);
                        String name = relative.getFileName().toString();
                        Path classRelative = relative.resolveSibling(
                                name.substring(0, name.length() - 5) + ".class");
                        Path classFile = module.outputDir().resolve(classRelative);
                        Files.createDirectories(classFile.getParent());
                        Files.writeString(classFile, "", StandardCharsets.UTF_8);
                    }
                }
            }
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public Optional<String> resolveRuntimeClasspath(JavaModule module) {
            classpathResolutions++;
            return Optional.of(classpath);
        }

        @Override
        public Optional<String> resolveTestClasspath(JavaModule module) {
            return Optional.of(classpath);
        }

        @Override
        public void invalidateClasspathCache() {
        }

        @Override
        public Optional<BuildCommand> toolCommand(JavaModule module, List<String> goals,
                                                  BuildCommand.Options options) {
            return Optional.empty();
        }
    }
}
