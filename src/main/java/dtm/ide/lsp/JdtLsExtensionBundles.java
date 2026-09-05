package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.SdkArtifact;
import dtm.ide.sdk.SdkDownloader;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

@Slf4j
public final class JdtLsExtensionBundles {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(JdtLsExtensionBundles.class, key, def);
    }

    public static final String DEBUG_PROGRESS_ID = "downloadJavaDebug";
    public static final String SPRING_PROGRESS_ID = "downloadSpringTools";
    public static final String TEST_PROGRESS_ID = "downloadJavaTest";

    public static final String JAVA_DEBUG_VERSION = "0.53.1";
    private static final String JAVA_DEBUG_URL =
            "https://repo1.maven.org/maven2/com/microsoft/java/com.microsoft.java.debug.plugin/%1$s/"
                    + "com.microsoft.java.debug.plugin-%1$s.jar";
    public static final String JAVA_TEST_VERSION = "0.46.0";
    private static final String JAVA_TEST_URL =
            "https://open-vsx.org/api/vscjava/vscode-java-test/%1$s/file/"
                    + "vscjava.vscode-java-test-%1$s.vsix";

    private static final String SPRING_TOOLS_LATEST =
            "https://open-vsx.org/api/vmware/vscode-spring-boot/latest";
    private static final String SPRING_TOOLS_FALLBACK_VERSION = "1.58.0";
    private static final String SPRING_TOOLS_FALLBACK_URL =
            "https://open-vsx.org/api/vmware/vscode-spring-boot/" + SPRING_TOOLS_FALLBACK_VERSION
                    + "/file/vmware.vscode-spring-boot-" + SPRING_TOOLS_FALLBACK_VERSION + ".vsix";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SdkDownloader downloader;
    private final Path sdkRoot;
    private volatile Set<BundleId> hostBundles;

    public JdtLsExtensionBundles(SdkDownloader downloader, Path sdkRoot) {
        this.downloader = downloader;
        this.sdkRoot = sdkRoot;
    }

    public Optional<Path> findJavaDebugPlugin() {
        Path jar = javaDebugRoot() == null ? null
                : javaDebugRoot().resolve("com.microsoft.java.debug.plugin-" + JAVA_DEBUG_VERSION + ".jar");
        return jar != null && Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    public Path ensureJavaDebugPlugin(DownloadProgressListener listener) {
        Optional<Path> existing = findJavaDebugPlugin();
        if (existing.isPresent()) {
            return existing.get();
        }
        Path root = javaDebugRoot();
        if (root == null) {
            throw SdkDownloader.error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        String fileName = "com.microsoft.java.debug.plugin-" + JAVA_DEBUG_VERSION + ".jar";
        downloader.downloadRaw(new SdkArtifact(
                fileName,
                String.format(JAVA_DEBUG_URL, JAVA_DEBUG_VERSION),
                DEBUG_PROGRESS_ID,
                text("download.javaDebug", "Baixando depurador Java")), root, listener);

        return findJavaDebugPlugin().orElseThrow(() -> SdkDownloader.error(
                text("error.javaDebugMissing",
                        "O depurador Java foi baixado, mas o bundle nao foi encontrado."), null));
    }

    public Optional<Path> findJavaTestPlugin() {
        return searchJars(javaTestRoot(), name ->
                name.startsWith("com.microsoft.java.test.plugin-") && name.endsWith(".jar"))
                .stream().findFirst();
    }

    public Path ensureJavaTestPlugin(DownloadProgressListener listener) {
        Optional<Path> existing = findJavaTestPlugin();
        if (existing.isPresent()) {
            return existing.get();
        }
        Path root = javaTestRoot();
        if (root == null) {
            throw SdkDownloader.error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        downloader.download(new SdkArtifact(
                "vscode-java-test-" + JAVA_TEST_VERSION + ".zip",
                String.format(JAVA_TEST_URL, JAVA_TEST_VERSION),
                TEST_PROGRESS_ID,
                text("download.javaTest", "Baixando Test Runner Java")), root, listener);
        return findJavaTestPlugin().orElseThrow(() -> SdkDownloader.error(
                text("error.javaTestMissing",
                        "O Test Runner Java foi baixado, mas o bundle nao foi encontrado."), null));
    }

    public List<Path> findJavaTestExtensions() {
        Path root = javaTestRoot();
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> declared = declaredJavaExtensions(root);
        if (!declared.isEmpty()) {
            return declared;
        }
        return searchJars(root, name -> name.startsWith("com.microsoft.java.test.plugin-")
                || name.startsWith("junit-")
                || name.startsWith("org.apiguardian.")
                || name.startsWith("org.eclipse.jdt.junit")
                || name.startsWith("org.jacoco.core_")
                || name.startsWith("org.objectweb.asm")
                || name.startsWith("org.opentest4j_"));
    }

    public List<Path> findSpringJdtExtensions() {
        Path root = springToolsRoot();
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> declared = declaredJavaExtensions(root);
        if (!declared.isEmpty()) {
            return declared;
        }
        return searchJars(root, name -> name.endsWith(".jar")
                && !name.contains("test")
                && (name.startsWith("org.springframework.tooling.")
                        || name.startsWith("jdt-ls-extension")
                        || name.startsWith("jdt-ls-commons")
                        || name.startsWith("sts-gradle-tooling")
                        || name.startsWith("io.projectreactor.reactor-core")
                        || name.startsWith("org.reactivestreams.reactive-streams")));
    }

    public void ensureSpringTools(DownloadProgressListener listener) {
        if (isSpringToolsInstalled()) {
            return;
        }
        Path root = springToolsRoot();
        if (root == null) {
            throw SdkDownloader.error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        SpringToolsRelease release = resolveSpringToolsRelease();
        downloader.download(new SdkArtifact(
                "vscode-spring-boot-" + release.version() + ".zip",
                release.url(),
                SPRING_PROGRESS_ID,
                text("download.springTools", "Baixando Spring Tools") + " " + release.version()),
                root, listener);

        if (findSpringJdtExtensions().isEmpty()) {
            throw SdkDownloader.error(text("error.springToolsMissing",
                    "O pacote do Spring foi baixado, mas a extensao para o jdtls nao foi encontrada."),
                    null);
        }
        markSpringToolsInstalled(release.version());
    }

    private boolean isSpringToolsInstalled() {
        if (findSpringJdtExtensions().isEmpty()) {
            return false;
        }
        Path marker = springToolsMarker();
        if (marker != null && !Files.isRegularFile(marker)) {
            markSpringToolsInstalled("");
        }
        return true;
    }

    private void markSpringToolsInstalled(String version) {
        Path marker = springToolsMarker();
        if (marker == null) {
            return;
        }
        try {
            Files.writeString(marker, version == null ? "" : version);
        } catch (Exception error) {
            log.debug("Falha ao gravar o marcador do Spring Tools em {}: {}", marker,
                    error.getMessage());
        }
    }

    private record SpringToolsRelease(String version, String url) {
    }

    private SpringToolsRelease resolveSpringToolsRelease() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(SPRING_TOOLS_LATEST))
                    .header("Accept", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                String version = json.path("version").asText("");
                String url = json.path("files").path("download").asText("");
                if (!version.isBlank() && !url.isBlank()) {
                    return new SpringToolsRelease(version, url);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Falha ao consultar o Open VSX: {}", e.getMessage());
        }
        log.info("Usando a versao fixa do Spring Tools: {}", SPRING_TOOLS_FALLBACK_VERSION);
        return new SpringToolsRelease(SPRING_TOOLS_FALLBACK_VERSION, SPRING_TOOLS_FALLBACK_URL);
    }

    public List<String> resolveBundlePaths(boolean includeSpring) {
        List<Path> candidates = new ArrayList<>();
        findJavaDebugPlugin().ifPresent(candidates::add);
        candidates.addAll(findJavaTestExtensions());
        if (includeSpring) {
            candidates.addAll(findSpringJdtExtensions());
        }

        Set<BundleId> installed = hostBundleIds();
        Set<String> seen = new LinkedHashSet<>();
        List<String> bundles = new ArrayList<>();
        for (Path jar : candidates) {
            Optional<BundleId> id = bundleId(jar);
            if (id.isPresent() && installed.contains(id.get())) {
                log.debug("Bundle descartado, o jdtls ja o instala: {} ({})", id.get(), jar);
                continue;
            }
            if (!seen.add(id.map(BundleId::toString).orElseGet(jar::toString))) {
                log.debug("Bundle descartado, repetido na lista: {}", jar);
                continue;
            }
            bundles.add(jar.toString());
        }
        return List.copyOf(bundles);
    }

    private record BundleId(String symbolicName, String version) {

        @Override
        public String toString() {
            return symbolicName + "_" + version;
        }
    }

    private static Optional<BundleId> bundleId(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                String name = attributes.getValue("Bundle-SymbolicName");
                String version = attributes.getValue("Bundle-Version");
                if (name != null && !name.isBlank() && version != null && !version.isBlank()) {
                    int directive = name.indexOf(';');
                    if (directive >= 0) {
                        name = name.substring(0, directive);
                    }
                    return Optional.of(new BundleId(name.trim(), version.trim()));
                }
            }
        } catch (Exception error) {
            log.debug("Falha ao ler o manifesto de {}: {}", jar, error.getMessage());
        }
        return bundleIdFromFileName(jar);
    }

    private static Optional<BundleId> bundleIdFromFileName(Path jar) {
        Path fileName = jar.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".jar")) {
            return Optional.empty();
        }
        String base = fileName.toString();
        base = base.substring(0, base.length() - ".jar".length());
        int separator = base.lastIndexOf('_');
        if (separator <= 0 || separator == base.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(
                new BundleId(base.substring(0, separator), base.substring(separator + 1)));
    }

    private Set<BundleId> hostBundleIds() {
        Set<BundleId> cached = hostBundles;
        if (cached != null) {
            return cached;
        }
        Set<BundleId> found = new LinkedHashSet<>();
        for (Path jar : searchJars(jdtlsPluginsRoot(), name -> name.endsWith(".jar"))) {
            bundleId(jar).ifPresent(found::add);
        }
        if (found.isEmpty()) {
            return Set.of();
        }
        Set<BundleId> resolved = Set.copyOf(found);
        hostBundles = resolved;
        return resolved;
    }

    private static List<Path> declaredJavaExtensions(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> manifests = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equals("package.json"))
                    .toList();
            Path normalizedRoot = root.toAbsolutePath().normalize();
            for (Path manifest : manifests) {
                JsonNode extensions = MAPPER.readTree(manifest.toFile())
                        .path("contributes").path("javaExtensions");
                if (!extensions.isArray()) {
                    continue;
                }
                List<Path> resolved = new ArrayList<>();
                for (JsonNode extension : extensions) {
                    String relative = extension.asText("");
                    if (relative.isBlank()) {
                        continue;
                    }
                    Path jar = manifest.getParent().resolve(relative).toAbsolutePath().normalize();
                    if (jar.startsWith(normalizedRoot) && Files.isRegularFile(jar)) {
                        resolved.add(jar);
                    }
                }
                if (!resolved.isEmpty()) {
                    return resolved.stream().distinct().sorted().toList();
                }
            }
        } catch (Exception error) {
            log.debug("Falha ao ler os bundles declarados em {}: {}", root, error.getMessage());
        }
        return List.of();
    }

    private static List<Path> searchJars(Path root, java.util.function.Predicate<String> nameMatch) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> nameMatch.test(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Path javaDebugRoot() {
        return sdkRoot == null ? null : sdkRoot.resolve("java-debug").resolve(JAVA_DEBUG_VERSION);
    }

    private Path springToolsRoot() {
        return sdkRoot == null ? null : sdkRoot.resolve("spring-tools");
    }

    private Path springToolsMarker() {
        Path root = springToolsRoot();
        return root == null ? null : root.resolve(".installed");
    }

    private Path javaTestRoot() {
        return sdkRoot == null ? null : sdkRoot.resolve("java-test").resolve(JAVA_TEST_VERSION);
    }

    private Path jdtlsPluginsRoot() {
        return sdkRoot == null ? null : sdkRoot.resolve("jdtls")
                .resolve(JdtLsProvisioner.DEFAULT_VERSION)
                .resolve("plugins");
    }
}
