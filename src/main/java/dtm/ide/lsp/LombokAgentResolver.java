package dtm.ide.lsp;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.GradleDependencyEditor;
import dtm.ide.deps.PomEditor;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.MavenPom;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class LombokAgentResolver {

    public static final String GROUP_ID = "org.projectlombok";
    public static final String ARTIFACT_ID = "lombok";
    public static final String TESTED_VERSION = "1.18.48";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String CENTRAL_BASE = "https://repo1.maven.org/maven2/";

    private static final Pattern POM_LOMBOK_VERSION = Pattern.compile(
            "(?s)<artifactId>\\s*lombok\\s*</artifactId>.{0,400}?<version>\\s*([^<]+?)\\s*</version>");

    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static final Pattern GRADLE_LOMBOK_VERSION = Pattern.compile(
            "org\\.projectlombok:lombok:([^\"'\\s]+)");

    private static final Pattern CATALOG_ALIAS = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.+)$");

    private static final Pattern CATALOG_VERSION_REF = Pattern.compile(
            "version\\.ref\\s*=\\s*[\"']([^\"']+)[\"']");

    private static final Pattern CATALOG_INLINE_VERSION = Pattern.compile(
            "version\\s*=\\s*[\"']([^\"']+)[\"']");

    private static final Pattern CLASSPATH_JAR = Pattern.compile(
            "lombok-([0-9][^/\\\\]*?)\\.jar$");

    private static final Pattern SETTINGS_LOCAL_REPOSITORY = Pattern.compile(
            "(?s)<localRepository>\\s*([^<]+?)\\s*</localRepository>");

    public record Detection(boolean declared, String version, Path projectJar) {

        static final Detection NONE = new Detection(false, null, null);
    }

    public record Agent(boolean declared, String version, Path jar, String failure) {

        static final Agent NOT_USED = new Agent(false, null, null, null);

        public boolean isUsable() {
            return jar != null;
        }
    }

    private final Path sdkRoot;

    public LombokAgentResolver(Path sdkRoot) {
        this.sdkRoot = sdkRoot;
    }

    public Optional<Path> resolve(JavaProjectDescriptor descriptor) {
        return Optional.ofNullable(resolveAgent(descriptor, List.of()).jar());
    }

    public Agent resolveAgent(JavaProjectDescriptor descriptor, Collection<Path> classpath) {
        Detection detection = detect(descriptor, classpath);
        if (!detection.declared()) {
            return Agent.NOT_USED;
        }
        String version = detection.version();
        List<String> attempts = new ArrayList<>();
        for (Path candidate : candidates(detection)) {
            if (isUsableAgentJar(candidate)) {
                return new Agent(true, version, candidate, null);
            }
            if (candidate != null) {
                discardCorruptedCache(candidate);
                attempts.add(candidate.getFileName().toString());
            }
        }
        Optional<Path> tested = jarFor(TESTED_VERSION);
        if (tested.isPresent() && isUsableAgentJar(tested.get())) {
            log.info("Lombok {} declarado no projeto; usando o agente testado {}",
                    version == null ? "sem versao" : version, TESTED_VERSION);
            return new Agent(true, TESTED_VERSION, tested.get(), null);
        }
        tested.ifPresent(this::discardCorruptedCache);
        String failure = attempts.isEmpty()
                ? "nenhum jar do Lombok disponivel"
                : "jar invalido: " + String.join(", ", attempts);
        log.warn("Lombok detectado no projeto, mas o agente nao pode ser preparado ({})", failure);
        return new Agent(true, version, null, failure);
    }

    private List<Path> candidates(Detection detection) {
        List<Path> candidates = new ArrayList<>();
        if (detection.projectJar() != null) {
            candidates.add(detection.projectJar());
        }
        if (detection.version() != null && !detection.version().isBlank()) {
            jarFor(detection.version()).ifPresent(candidates::add);
        }
        return candidates;
    }

    public Optional<Path> jarFor(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> local = localJar(version);
        if (local.isPresent()) {
            return local;
        }
        Path cached = cachedJar(version);
        if (cached == null) {
            return Optional.empty();
        }
        if (Files.isRegularFile(cached)) {
            return Optional.of(cached);
        }
        return download(version, cached);
    }

    public Optional<String> detectVersion(JavaProjectDescriptor descriptor) {
        Detection detection = detect(descriptor, List.of());
        if (!detection.declared()) {
            return Optional.empty();
        }
        return Optional.of(detection.version() == null ? TESTED_VERSION : detection.version());
    }

    public Detection detect(JavaProjectDescriptor descriptor) {
        return detect(descriptor, List.of());
    }

    public Detection detect(JavaProjectDescriptor descriptor, Collection<Path> classpath) {
        Path classpathJar = lombokFromClasspath(classpath);
        if (descriptor == null) {
            return classpathJar == null
                    ? Detection.NONE
                    : new Detection(true, versionOfJar(classpathJar), classpathJar);
        }
        String catalog = readCatalogs(descriptor);
        boolean declared = classpathJar != null || catalogDeclaresLombok(catalog);
        String version = classpathJar != null ? versionOfJar(classpathJar) : null;
        for (Path buildFile : buildFilesOf(descriptor)) {
            String content = JavaProjectConventions.readOrEmpty(buildFile);
            if (content.isBlank()) {
                continue;
            }
            boolean mentionsLombok = content.contains(ARTIFACT_ID);
            boolean usesCatalogAlias = usesCatalogAlias(content);
            if (!mentionsLombok && !usesCatalogAlias) {
                continue;
            }
            declared = true;
            if (version != null) {
                continue;
            }
            Optional<String> found = usesCatalogAlias && !mentionsLombok
                    ? catalogVersion(catalog)
                    : versionFrom(content, buildFile, descriptor);
            if (found.isPresent()) {
                version = found.get();
            }
        }
        if (!declared) {
            return Detection.NONE;
        }
        if (version == null) {
            version = catalogVersion(catalog).orElse(null);
        }
        return new Detection(true, version, classpathJar);
    }

    public static boolean isUsableAgentJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return false;
            }
            String premain = manifest.getMainAttributes().getValue("Premain-Class");
            if (premain == null || premain.isBlank()) {
                return false;
            }
            return premain.startsWith("lombok")
                    && file.getEntry("lombok/launch/Agent.class") != null;
        } catch (Exception e) {
            log.debug("Jar do Lombok invalido em {}: {}", jar, e.getMessage());
            return false;
        }
    }

    private void discardCorruptedCache(Path jar) {
        Path cacheRoot = sdkRoot == null ? null : sdkRoot.resolve("lombok");
        if (jar == null || cacheRoot == null || !jar.startsWith(cacheRoot)
                || !Files.isRegularFile(jar)) {
            return;
        }
        try {
            Files.delete(jar);
            log.warn("Jar do Lombok corrompido removido do cache: {}", jar);
        } catch (Exception e) {
            log.debug("Nao foi possivel remover o jar corrompido {}: {}", jar, e.getMessage());
        }
    }

    private static Path lombokFromClasspath(Collection<Path> classpath) {
        if (classpath == null) {
            return null;
        }
        for (Path entry : classpath) {
            if (entry == null) {
                continue;
            }
            String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
            if (CLASSPATH_JAR.matcher(name).find() && Files.isRegularFile(entry)) {
                return entry;
            }
        }
        return null;
    }

    private static String versionOfJar(Path jar) {
        if (jar == null || jar.getFileName() == null) {
            return null;
        }
        Matcher matcher = CLASSPATH_JAR.matcher(jar.getFileName().toString());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<Path> buildFilesOf(JavaProjectDescriptor descriptor) {
        Set<Path> files = new LinkedHashSet<>();
        files.addAll(buildFilesOf(descriptor.root()));
        for (JavaModule module : descriptor.modules()) {
            files.addAll(buildFilesOf(module.root()));
        }
        return List.copyOf(files);
    }

    private static List<Path> buildFilesOf(Path root) {
        return List.of(
                root.resolve(JavaProjectConventions.POM_FILE),
                root.resolve(JavaProjectConventions.GRADLE_BUILD_GROOVY),
                root.resolve(JavaProjectConventions.GRADLE_BUILD_KOTLIN));
    }

    private static String readCatalogs(JavaProjectDescriptor descriptor) {
        StringBuilder catalogs = new StringBuilder();
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(descriptor.root());
        descriptor.modules().forEach(module -> roots.add(module.root()));
        for (Path root : roots) {
            String content = JavaProjectConventions.readOrEmpty(
                    root.resolve("gradle").resolve("libs.versions.toml"));
            if (!content.isBlank()) {
                catalogs.append(content).append('\n');
            }
        }
        return catalogs.toString();
    }

    static boolean usesCatalogAlias(String buildScript) {
        return buildScript.contains("libs.lombok") || buildScript.contains("libs.versions.lombok");
    }

    static boolean catalogDeclaresLombok(String catalog) {
        return catalog != null && catalog.contains(GROUP_ID + ":" + ARTIFACT_ID);
    }

    static Optional<String> catalogVersion(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return Optional.empty();
        }
        String versionRef = null;
        Matcher aliases = CATALOG_ALIAS.matcher(catalog);
        while (aliases.find()) {
            String value = aliases.group(2);
            if (!value.contains(ARTIFACT_ID)) {
                continue;
            }
            Matcher shortNotation = GRADLE_LOMBOK_VERSION.matcher(value);
            if (shortNotation.find()) {
                return Optional.of(shortNotation.group(1).trim());
            }
            if (!value.contains(GROUP_ID)) {
                continue;
            }
            Matcher inline = CATALOG_INLINE_VERSION.matcher(value);
            if (inline.find()) {
                return Optional.of(inline.group(1).trim());
            }
            Matcher reference = CATALOG_VERSION_REF.matcher(value);
            if (reference.find()) {
                versionRef = reference.group(1).trim();
            }
        }
        if (versionRef == null) {
            versionRef = ARTIFACT_ID;
        }
        Pattern declaration = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(versionRef) + "\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher declared = declaration.matcher(catalog);
        return declared.find() ? Optional.of(declared.group(1).trim()) : Optional.empty();
    }

    private static Optional<String> versionFrom(String content, Path buildFile,
                                                JavaProjectDescriptor descriptor) {
        if (JavaProjectConventions.isMavenPom(buildFile)) {
            return mavenVersion(content, descriptor);
        }
        return gradleVersion(content);
    }

    private static Optional<String> mavenVersion(String pomXml, JavaProjectDescriptor descriptor) {
        String declared = declaredVersion(PomEditor.readDependencies(pomXml));
        if (declared.isBlank()) {
            Matcher matcher = POM_LOMBOK_VERSION.matcher(pomXml);
            declared = matcher.find() ? matcher.group(1).trim() : "";
        }
        String resolved = resolveProperties(declared, pomXml, descriptor);
        return resolved == null || resolved.isBlank() ? Optional.empty() : Optional.of(resolved);
    }

    private static Optional<String> gradleVersion(String buildScript) {
        String declared = declaredVersion(GradleDependencyEditor.readDependencies(buildScript));
        if (declared.isBlank()) {
            Matcher matcher = GRADLE_LOMBOK_VERSION.matcher(buildScript);
            declared = matcher.find() ? matcher.group(1).trim() : "";
        }
        return declared.isBlank() || declared.contains("$") ? Optional.empty() : Optional.of(declared);
    }

    private static String declaredVersion(List<DependencyCoordinate> dependencies) {
        return dependencies.stream()
                .filter(dependency -> GROUP_ID.equals(dependency.groupId())
                        && ARTIFACT_ID.equals(dependency.artifactId()))
                .map(DependencyCoordinate::version)
                .filter(version -> !version.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String resolveProperties(String version, String pomXml,
                                            JavaProjectDescriptor descriptor) {
        if (version == null || version.isBlank() || !version.contains("${")) {
            return version;
        }
        Matcher matcher = PROPERTY_REFERENCE.matcher(version);
        if (!matcher.find()) {
            return version;
        }
        String name = matcher.group(1).trim();
        String resolved = MavenPom.parseContent(pomXml).property(name);
        if (resolved != null && !resolved.isBlank()) {
            return resolved.trim();
        }
        if (descriptor == null) {
            return "";
        }
        for (JavaModule module : descriptor.modules()) {
            String value = MavenPom.parse(module.root().resolve(JavaProjectConventions.POM_FILE))
                    .property(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Optional<Path> localJar(String version) {
        String fileName = "lombok-" + version + ".jar";
        Path maven = mavenLocalRepository();
        if (maven != null) {
            Path candidate = maven.resolve(Path.of("org", "projectlombok", "lombok", version,
                    fileName));
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return gradleCacheJar(version, fileName);
    }

    private static Path mavenLocalRepository() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        Path settings = Path.of(home, ".m2", "settings.xml");
        String content = JavaProjectConventions.readOrEmpty(settings);
        Matcher matcher = SETTINGS_LOCAL_REPOSITORY.matcher(content);
        if (matcher.find()) {
            String declared = matcher.group(1).replace("${user.home}", home).trim();
            if (!declared.isBlank() && !declared.contains("${")) {
                Path repository = Path.of(declared);
                if (Files.isDirectory(repository)) {
                    return repository;
                }
            }
        }
        return Path.of(home, ".m2", "repository");
    }

    private static Optional<Path> gradleCacheJar(String version, String fileName) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        Path modules = Path.of(home, ".gradle", "caches", "modules-2", "files-2.1",
                GROUP_ID, ARTIFACT_ID, version);
        if (!Files.isDirectory(modules)) {
            return Optional.empty();
        }
        try (Stream<Path> tree = Files.walk(modules, 3)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> fileName.equals(path.getFileName().toString()))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Path cachedJar(String version) {
        return sdkRoot == null
                ? null
                : sdkRoot.resolve("lombok").resolve("lombok-" + version + ".jar");
    }

    private Optional<Path> download(String version, Path target) {
        String url = CENTRAL_BASE + "org/projectlombok/lombok/" + version
                + "/lombok-" + version + ".jar";
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            Files.createDirectories(target.getParent());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("Lombok {} indisponivel no Maven Central (HTTP {})",
                        version, response.statusCode());
                return Optional.empty();
            }
            try (InputStream body = response.body()) {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Lombok {} baixado para o agente do jdtls", version);
            return Optional.of(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Falha ao baixar o Lombok {}: {}", version, e.getMessage());
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (Exception ignored) {
            }
        }
    }
}
