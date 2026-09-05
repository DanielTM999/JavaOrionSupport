package dtm.ide.lsp;

import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.Platform;
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
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
public final class JdtLsProvisioner {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(JdtLsProvisioner.class, key, def);
    }

    public static final String PROGRESS_ID = "downloadJdtLs";

    public static final String DEFAULT_VERSION = "1.60.0";
    private static final String FALLBACK_FILE = "jdt-language-server-1.60.0-202606262232.tar.gz";

    private static final String MILESTONE_BASE = "https://download.eclipse.org/jdtls/milestones/";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final SdkDownloader downloader;
    private final Path sdkRoot;

    public JdtLsProvisioner(SdkDownloader downloader, Path sdkRoot) {
        this.downloader = downloader;
        this.sdkRoot = sdkRoot;
    }

    public record JdtLsInstallation(Path home, Path launcherJar, Path configDir) {
    }

    public Optional<JdtLsInstallation> find() {
        return find(installRoot());
    }

    public JdtLsInstallation ensure(DownloadProgressListener listener) {
        Optional<JdtLsInstallation> existing = find();
        if (existing.isPresent()) {
            return existing.get();
        }
        Path root = installRoot();
        if (root == null) {
            throw SdkDownloader.error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }

        String fileName = resolveFileName();
        downloader.download(new SdkArtifact(
                fileName,
                MILESTONE_BASE + DEFAULT_VERSION + "/" + fileName,
                PROGRESS_ID,
                text("download.jdtls", "Baixando IntelliSense Java") + " " + DEFAULT_VERSION),
                root, listener);

        return find(root).orElseThrow(() -> SdkDownloader.error(text("error.jdtlsMissing",
                "O IntelliSense Java foi baixado, mas o launcher nao foi encontrado."), null));
    }

    private static Optional<JdtLsInstallation> find(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path home = Files.isDirectory(root.resolve("plugins"))
                ? root
                : SdkDownloader.singleChildDirectory(root).orElse(root);

        Optional<Path> launcher = launcherJar(home);
        Path configDir = home.resolve("config_" + Platform.current().jdtLsConfig());
        if (launcher.isEmpty() || !Files.isDirectory(configDir)) {
            return Optional.empty();
        }
        return Optional.of(new JdtLsInstallation(home, launcher.get(), configDir));
    }

    private static Optional<Path> launcherJar(Path home) {
        Path plugins = home.resolve("plugins");
        if (!Files.isDirectory(plugins)) {
            return Optional.empty();
        }
        try (Stream<Path> jars = Files.list(plugins)) {
            return jars.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("org.eclipse.equinox.launcher_") && name.endsWith(".jar");
                    })
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String resolveFileName() {
        String url = MILESTONE_BASE + DEFAULT_VERSION + "/latest.txt";
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String name = response.statusCode() == 200 ? response.body().trim() : "";
            if (name.endsWith(".tar.gz")) {
                return name;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Falha ao consultar a milestone do jdtls: {}", e.getMessage());
        }
        log.info("Usando o build fixo do jdtls: {}", FALLBACK_FILE);
        return FALLBACK_FILE;
    }

    private Path installRoot() {
        return sdkRoot == null ? null : sdkRoot.resolve("jdtls").resolve(DEFAULT_VERSION);
    }

    public Path workspaceFor(Path projectRoot) {
        if (sdkRoot == null || projectRoot == null) {
            return null;
        }
        Path normalized = projectRoot.toAbsolutePath().normalize();
        String fileName = normalized.getFileName() == null
                ? "project" : normalized.getFileName().toString();
        String key = Integer.toHexString(normalized.toString()
                .toLowerCase(java.util.Locale.ROOT).hashCode());
        return sdkRoot.resolve("workspaces")
                .resolve(DEFAULT_VERSION)
                .resolve(fileName + "-" + key)
                .toAbsolutePath().normalize();
    }
}
