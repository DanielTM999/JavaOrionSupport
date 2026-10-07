package dtm.ide.swingdesigner;

import dtm.ide.build.BuildResult;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.swingdesigner.catalog.ClasspathEntry;
import dtm.ide.swingdesigner.catalog.ClasspathIndex;
import dtm.ide.swingdesigner.catalog.ComponentCatalog;
import dtm.ide.swingdesigner.catalog.ComponentOrigin;
import dtm.ide.swingdesigner.catalog.DescriptorSetReader;
import dtm.ide.swingdesigner.catalog.JarHeaderCache;
import dtm.ide.swingdesigner.runtime.DesignerHostException;
import dtm.ide.swingdesigner.runtime.DesignerHostProcess;
import dtm.ide.swingdesigner.runtime.HostJar;
import dtm.ide.swingdesigner.runtime.SwingViewClient;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class ModuleSession implements AutoCloseable {

    private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));
    private static final Duration HOST_START_TIMEOUT = Duration.ofSeconds(45);

    private final JavaModule module;
    private final SwingDesignerEnvironment environment;
    private List<ClasspathEntry> entries = List.of();
    private ClasspathIndex index;
    private volatile ComponentCatalog catalog;
    private SwingViewClient client;
    private Path hostJava;
    private boolean shadowActive;

    ModuleSession(JavaModule module, SwingDesignerEnvironment environment) {
        this.module = module;
        this.environment = environment;
    }

    public JavaModule module() {
        return module;
    }

    public Optional<ComponentCatalog> catalogIfReady() {
        return Optional.ofNullable(catalog);
    }

    public synchronized ComponentCatalog catalog() {
        if (catalog == null) {
            refreshCatalog();
        }
        return catalog;
    }

    public synchronized List<ClasspathEntry> entries() {
        if (catalog == null) {
            refreshCatalog();
        }
        return entries;
    }

    public synchronized SwingViewClient client() {
        if (client != null && client.host().isAlive()) {
            return client;
        }
        client = null;
        JdkInstallation jdk = environment.projectJdk();
        if (jdk == null || !jdk.isUsable()) {
            throw new DesignerHostException("Nenhuma JDK do projeto disponivel para rodar o Swing Designer");
        }
        Path hostJar;
        try {
            hostJar = HostJar.ensure(environment.cacheDirectory());
        } catch (IOException e) {
            throw new DesignerHostException("Nao foi possivel preparar a JVM do Swing Designer: "
                    + e.getMessage(), e);
        }
        List<ClasspathEntry> current = entries();
        DesignerHostProcess process = DesignerHostProcess.start(jdk.javaExecutable(), hostJar,
                environment::output, HOST_START_TIMEOUT);
        SwingViewClient started = new SwingViewClient(process);
        try {
            started.init(paths(current, ComponentOrigin.LIBRARY), runtimeWorkspace(current), null);
        } catch (RuntimeException e) {
            process.close();
            throw e;
        }
        hostJava = jdk.javaExecutable();
        client = started;
        return client;
    }

    public synchronized boolean hasLiveClient() {
        return client != null && client.host().isAlive();
    }

    public synchronized void refreshAfterBuild() {
        clearShadow();
        List<ClasspathEntry> previous = entries;
        refreshCatalog();
        if (client == null || !client.host().isAlive()) {
            return;
        }
        JdkInstallation jdk = environment.projectJdk();
        boolean jdkChanged = jdk == null || !jdk.javaExecutable().equals(hostJava);
        boolean librariesChanged = !paths(previous, ComponentOrigin.LIBRARY)
                .equals(paths(entries, ComponentOrigin.LIBRARY));
        if (jdkChanged || librariesChanged) {
            client.host().close();
            client = null;
            return;
        }
        client.reload(runtimeWorkspace(entries));
    }

    public synchronized BuildResult compileShadow(String className, String text) {
        Path base = shadowDirectory();
        Path classes = base.resolve("classes");
        int dollar = className.indexOf('$');
        String outer = dollar < 0 ? className : className.substring(0, dollar);
        Path source = base.resolve("src").resolve(outer.replace('.', '/') + ".java");
        try {
            Files.createDirectories(source.getParent());
            Files.writeString(source, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return BuildResult.failed("javac", "Nao foi possivel preparar a compilacao do designer: "
                    + e.getMessage());
        }
        List<String> lines = new ArrayList<>();
        BuildResult result = environment.compileShadow(module, source, classes, lines::add);
        if (result == null || !result.successful()) {
            return result == null ? BuildResult.failed("javac", String.join("\n", lines)) : result;
        }
        shadowActive = true;
        if (client != null && client.host().isAlive()) {
            client.reload(runtimeWorkspace(entries));
        }
        return result;
    }

    public synchronized void clearShadow() {
        shadowActive = false;
        Path base = shadowDirectory();
        if (!Files.exists(base)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(base)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.debug("Falha ao limpar {}: {}", base, e.getMessage());
        }
    }

    public boolean hasShadow() {
        return shadowActive;
    }

    private Path shadowDirectory() {
        String key = Integer.toHexString(module.root().toAbsolutePath().normalize().toString().hashCode());
        return environment.cacheDirectory().resolve("shadow").resolve(key);
    }

    List<Path> runtimeWorkspace(List<ClasspathEntry> current) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        Path shadowClasses = shadowDirectory().resolve("classes");
        if (shadowActive && Files.isDirectory(shadowClasses)) {
            paths.add(shadowClasses.toAbsolutePath().normalize());
        }
        paths.addAll(paths(current, ComponentOrigin.WORKSPACE));
        resourceRoots(module).forEach(paths::add);
        JavaProjectDescriptor descriptor = environment.descriptor();
        if (descriptor != null) {
            Path own = module.root().toAbsolutePath().normalize();
            for (JavaModule other : descriptor.buildableModules()) {
                if (other == null || other.isAggregator()
                        || other.root().toAbsolutePath().normalize().equals(own)) {
                    continue;
                }
                if (other.outputDir() != null && Files.isDirectory(other.outputDir())) {
                    paths.add(other.outputDir().toAbsolutePath().normalize());
                }
                resourceRoots(other).forEach(paths::add);
            }
        }
        return new ArrayList<>(paths);
    }

    private static List<Path> resourceRoots(JavaModule target) {
        List<Path> roots = new ArrayList<>();
        for (Path root : target.sourceRoots()) {
            Path name = root.getFileName();
            if (name != null && name.toString().equals("resources") && Files.isDirectory(root)) {
                roots.add(root.toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    public boolean ownsSource(Path file) {
        if (file == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : module.sourceRoots()) {
            if (normalized.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    public Optional<Path> sourceOf(String className) {
        if (className == null || className.isBlank()) {
            return Optional.empty();
        }
        int dollar = className.indexOf('$');
        String outer = dollar < 0 ? className : className.substring(0, dollar);
        String relative = outer.replace('.', '/') + ".java";
        for (Path root : module.sourceRoots()) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    public Optional<String> classNameOf(Path javaFile) {
        Path normalized = javaFile.toAbsolutePath().normalize();
        for (Path root : module.sourceRoots()) {
            Path base = root.toAbsolutePath().normalize();
            if (!normalized.startsWith(base)) {
                continue;
            }
            String relative = base.relativize(normalized).toString().replace('\\', '/');
            if (!relative.endsWith(".java")) {
                return Optional.empty();
            }
            return Optional.of(relative.substring(0, relative.length() - 5).replace('/', '.'));
        }
        return Optional.empty();
    }

    @Override
    public synchronized void close() {
        clearShadow();
        if (client != null) {
            client.host().close();
            client = null;
        }
        if (index != null) {
            index.close();
            index = null;
        }
        catalog = null;
    }

    private void refreshCatalog() {
        List<ClasspathEntry> resolved = resolveEntries();
        ClasspathIndex built = ClasspathIndex.build(resolved,
                new JarHeaderCache(environment.cacheDirectory().resolve("headers")));
        JavaProjectDescriptor descriptor = environment.descriptor();
        Path projectRoot = descriptor == null ? module.root() : descriptor.root();
        ComponentCatalog rebuilt = ComponentCatalog.standard(built,
                DescriptorSetReader.project(projectRoot).orElse(null));
        if (index != null) {
            index.close();
        }
        index = built;
        entries = built.entries();
        catalog = rebuilt;
    }

    private List<ClasspathEntry> resolveEntries() {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        environment.runtimeClasspath(module).ifPresent(classpath -> {
            for (String entry : PATH_SEPARATOR.split(classpath)) {
                if (!entry.isBlank()) {
                    raw.add(entry.trim());
                }
            }
        });
        raw.add(module.outputDir().toString());
        JavaProjectDescriptor descriptor = environment.descriptor();
        List<ClasspathEntry> result = new ArrayList<>();
        for (String entry : raw) {
            Path path;
            try {
                path = Path.of(entry).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (Files.isDirectory(path)) {
                result.add(ClasspathEntry.workspace(path, moduleNameOf(descriptor, path)));
            } else if (Files.isRegularFile(path)) {
                result.add(ClasspathEntry.library(path));
            }
        }
        return result;
    }

    private String moduleNameOf(JavaProjectDescriptor descriptor, Path directory) {
        if (descriptor != null) {
            for (JavaModule candidate : descriptor.modules()) {
                if (candidate.outputDir() != null
                        && candidate.outputDir().toAbsolutePath().normalize().equals(directory)) {
                    return candidate.name();
                }
            }
        }
        if (module.outputDir() != null && module.outputDir().toAbsolutePath().normalize().equals(directory)) {
            return module.name();
        }
        return null;
    }

    private static List<Path> paths(List<ClasspathEntry> entries, ComponentOrigin origin) {
        return entries.stream().filter(entry -> entry.origin() == origin)
                .map(ClasspathEntry::path).toList();
    }
}
