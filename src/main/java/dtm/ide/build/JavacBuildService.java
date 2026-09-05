package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.Platform;
import dtm.ide.sdk.SdkDownloader;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public final class JavacBuildService implements BuildSystem {

    private static final int ARGUMENT_FILE_THRESHOLD = 30;

    private final JavaProjectDescriptor descriptor;
    private final Supplier<JdkInstallation> jdkSupplier;
    private final ProcessRunner runner = new ProcessRunner();

    public JavacBuildService(JavaProjectDescriptor descriptor, Supplier<JdkInstallation> jdkSupplier) {
        this.descriptor = descriptor;
        this.jdkSupplier = jdkSupplier;
    }

    @Override
    public String name() {
        return "javac";
    }

    @Override
    public BuildResult execute(BuildRequest request, Consumer<String> output) {
        Instant start = Instant.now();
        JavaModule module = request.module() == null ? descriptor.rootModule() : request.module();
        if (module == null) {
            return BuildResult.failed("javac", "Nenhum modulo para compilar.");
        }

        if (request.action() == BuildAction.CLEAN) {
            SdkDownloader.deleteRecursively(module.outputDir());
            emit(output, "Removido: " + module.outputDir());
            return new BuildResult(0, List.of(), Duration.between(start, Instant.now()), "clean");
        }
        if (request.action() == BuildAction.TEST) {
            String reason = "Projetos sem build system nao tem suite de testes configurada. "
                    + "Use o Test Explorer para rodar testes JUnit avulsos.";
            emit(output, reason);
            return BuildResult.failed("javac", reason);
        }

        JdkInstallation jdk = jdkSupplier == null ? null : jdkSupplier.get();
        if (jdk == null || !jdk.isJdk()) {
            String reason = "Nenhuma JDK com compilador foi encontrada. Instale uma pelo JDK Manager.";
            emit(output, reason);
            return BuildResult.failed("javac", reason);
        }

        if (request.action() == BuildAction.REBUILD) {
            SdkDownloader.deleteRecursively(module.outputDir());
        }

        List<Path> sources = collectSources(module, request.action() == BuildAction.TEST_COMPILE);
        if (sources.isEmpty()) {
            String reason = "Nenhum arquivo .java encontrado em " + module.root();
            emit(output, reason);
            return BuildResult.failed("javac", reason);
        }

        Path argumentFile = null;
        try {
            Files.createDirectories(module.outputDir());
            List<String> command = new ArrayList<>();
            command.add(jdk.javacExecutable().toString());
            command.add("-d");
            command.add(module.outputDir().toString());
            command.add("-encoding");
            command.add("UTF-8");
            command.addAll(request.extraArguments());

            if (sources.size() > ARGUMENT_FILE_THRESHOLD) {
                argumentFile = writeArgumentFile(sources);
                command.add("@" + argumentFile);
            } else {
                sources.forEach(source -> command.add(source.toString()));
            }

            BuildDiagnosticParser parser = new BuildDiagnosticParser(module.root());
            emit(output, "> javac " + sources.size() + " arquivo(s) -> " + module.outputDir());

            int exitCode = runner.run(command, module.root(), Map.of(), line -> {
                parser.accept(line);
                emit(output, line);
            });

            BuildResult result = new BuildResult(exitCode, parser.diagnostics(),
                    Duration.between(start, Instant.now()), String.join(" ", command));

            if (result.successful() && request.action() == BuildAction.PACKAGE) {
                return packageJar(module, jdk, result, output, start);
            }
            return result;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            emit(output, reason);
            return BuildResult.failed("javac", reason);
        } finally {
            deleteQuietly(argumentFile);
        }
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
        JavaModule target = module == null ? descriptor.rootModule() : module;
        if (target == null) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        entries.add(target.outputDir().toString());
        Path lib = target.root().resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> jars = Files.list(lib)) {
                jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .forEach(path -> entries.add(path.toString()));
            } catch (Exception ignored) {
            }
        }
        return Optional.of(String.join(File.pathSeparator, entries));
    }

    @Override
    public void invalidateClasspathCache() {
    }

    private BuildResult packageJar(JavaModule module, JdkInstallation jdk, BuildResult compileResult,
                                   Consumer<String> output, Instant start) {
        Path jarFile = module.root().resolve(module.artifactId() + ".jar");
        Path jarTool = jdk.home().resolve("bin").resolve("jar" + Platform.current().executableSuffix());
        if (!Files.isRegularFile(jarTool)) {
            emit(output, "A ferramenta jar nao foi encontrada na JDK selecionada.");
            return compileResult;
        }
        List<String> command = List.of(jarTool.toString(), "--create", "--file", jarFile.toString(),
                "-C", module.outputDir().toString(), ".");

        emit(output, "> empacotando " + jarFile.getFileName());
        int exitCode = runner.run(command, module.root(), Map.of(), line -> emit(output, line));
        return new BuildResult(exitCode, compileResult.diagnostics(),
                Duration.between(start, Instant.now()), String.join(" ", command));
    }

    private static List<Path> collectSources(JavaModule module, boolean includeTests) {
        List<Path> roots = new ArrayList<>(module.existingSourceRoots());
        if (includeTests) {
            roots.addAll(module.existingTestRoots());
        }
        List<Path> sources = new ArrayList<>();
        for (Path sourceRoot : roots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(sources::add);
            } catch (Exception e) {
                log.debug("Falha ao varrer {}: {}", sourceRoot, e.getMessage());
            }
        }
        return sources;
    }

    private static Path writeArgumentFile(List<Path> sources) throws Exception {
        Path file = Files.createTempFile("orion-javac-sources", ".txt");
        StringBuilder content = new StringBuilder();
        for (Path source : sources) {
            content.append('"').append(source.toString().replace("\\", "\\\\")).append('"')
                    .append(System.lineSeparator());
        }
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        return file;
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
