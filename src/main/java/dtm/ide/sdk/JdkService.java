package dtm.ide.sdk;

import dtm.ide.api.extension.Resource;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.request_actions.http.download.core.DownloadObserver;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class JdkService {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(JdkService.class, key, def);
    }

    public static final String JDK_PROGRESS_ID = "downloadJdk";

    public static final int LANGUAGE_SERVER_MIN_MAJOR = 21;

    public static final int DEFAULT_MAJOR = 21;

    public static final List<Integer> DOWNLOADABLE_MAJORS = List.of(8, 11, 17, 21, 25);

    private static final String SDK_DIR = "sdk";
    private static final String JDK_DIR = "jdk";

    private final Resource resource;
    private final SdkDownloader downloader;
    private final AdoptiumClient adoptium = new AdoptiumClient();
    private final AtomicReference<List<JdkInstallation>> cache = new AtomicReference<>();

    public JdkService(Resource resource, DownloadObserver downloadObserver) {
        this.resource = resource;
        this.downloader = new SdkDownloader(downloadObserver);
    }

    public List<JdkInstallation> available() {
        List<JdkInstallation> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        List<JdkInstallation> detected = JdkDetector.detect(managedRoot());
        cache.set(detected);
        return detected;
    }

    public void refresh() {
        cache.set(null);
    }

    public Optional<JdkInstallation> find(int major) {
        return available().stream()
                .filter(installation -> installation.major() == major)
                .filter(JdkInstallation::isUsable)
                .max(Comparator.comparing(JdkInstallation::isJdk)
                        .thenComparing(JdkInstallation::fullVersion));
    }

    public Optional<JdkInstallation> newest() {
        return available().stream()
                .filter(JdkInstallation::isUsable)
                .min(Comparator.naturalOrder());
    }

    public Optional<JdkInstallation> resolveForProject(JavaProjectDescriptor descriptor) {
        return resolveForProject(descriptor, DEFAULT_MAJOR);
    }

    public Optional<JdkInstallation> resolveForProject(JavaProjectDescriptor descriptor,
                                                       int defaultMajor) {
        Optional<Integer> requested = descriptor == null ? Optional.empty() : descriptor.jdkMajor();
        if (requested.isPresent()) {
            Optional<JdkInstallation> exact = find(requested.get());
            if (exact.isPresent()) {
                return exact;
            }
            log.info("JDK {} pedida pelo projeto nao esta instalada; usando a disponivel mais proxima.",
                    requested.get());
            Optional<JdkInstallation> compatible = available().stream()
                    .filter(JdkInstallation::isUsable)
                    .filter(installation -> installation.major() >= requested.get())
                    .min(Comparator.comparingInt(JdkInstallation::major));
            if (compatible.isPresent()) {
                return compatible;
            }
        }
        return find(defaultMajor).or(this::newest);
    }

    public Optional<JdkInstallation> languageServerJdk() {
        return available().stream()
                .filter(JdkInstallation::isUsable)
                .filter(installation -> installation.major() >= LANGUAGE_SERVER_MIN_MAJOR)
                .min(Comparator.comparingInt(JdkInstallation::major));
    }

    public JdkInstallation ensure(int major, DownloadProgressListener progressListener) {
        Optional<JdkInstallation> existing = find(major);
        if (existing.isPresent()) {
            return existing.get();
        }
        install(major, progressListener);
        return find(major).orElseThrow(() -> SdkDownloader.error(
                text("error.jdkMissing", "A JDK foi baixada, mas o executavel java nao foi encontrado.")
                        + " (JDK " + major + ")", null));
    }

    public JdkInstallation install(int major, DownloadProgressListener progressListener) {
        Path root = managedRoot();
        if (root == null) {
            throw SdkDownloader.error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }

        AdoptiumClient.AdoptiumRelease release = adoptium.latest(major).orElseThrow(() ->
                SdkDownloader.error(text("error.jdkUnavailable",
                        "Nao ha build da JDK") + " " + major + " "
                        + text("error.forThisPlatform", "para esta plataforma."), null));

        Path installDir = root.resolve("temurin-" + major);
        SdkDownloader.deleteRecursively(installDir);

        SdkArtifact artifact = new SdkArtifact(
                release.fileName(),
                release.link(),
                JDK_PROGRESS_ID,
                text("download.jdk", "Baixando JDK") + " " + major + " (" + release.version() + ")");

        downloader.download(artifact, installDir, progressListener);
        refresh();

        Path home = SdkDownloader.singleChildDirectory(installDir).orElse(installDir);
        return JdkDetector.inspect(home, JdkInstallation.JdkOrigin.MANAGED)
                .orElseThrow(() -> SdkDownloader.error(text("error.jdkMissing",
                        "A JDK foi baixada, mas o executavel java nao foi encontrado.")
                        + " (" + installDir + ")", null));
    }

    public boolean remove(JdkInstallation installation) {
        if (installation == null || !installation.isManaged()) {
            return false;
        }
        Path managedRoot = managedRoot();
        Path home = installation.home();
        if (managedRoot == null || !home.startsWith(managedRoot)) {
            return false;
        }
        Path installDir = home;
        while (installDir.getParent() != null && !installDir.getParent().equals(managedRoot)) {
            installDir = installDir.getParent();
        }
        SdkDownloader.deleteRecursively(installDir);
        refresh();
        return true;
    }

    public void pinForProject(Path projectRoot, Integer major) {
        if (projectRoot == null) {
            return;
        }
        Path file = projectRoot.resolve(JavaProjectConventions.ORION_SETTINGS_DIR)
                .resolve(JavaProjectConventions.ORION_JAVA_PROPERTIES);
        try {
            Properties props = new Properties();
            if (Files.isRegularFile(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            if (major == null) {
                props.remove(JavaProjectConventions.KEY_JDK_VERSION);
            } else {
                props.setProperty(JavaProjectConventions.KEY_JDK_VERSION, String.valueOf(major));
            }
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "JavaOrionSupport");
            }
        } catch (Exception e) {
            log.debug("Falha ao fixar a JDK do projeto {}: {}", projectRoot, e.getMessage());
        }
    }

    public Path managedRoot() {
        Path root = resourcePath();
        return root == null ? null : root.resolve(SDK_DIR).resolve(JDK_DIR).toAbsolutePath().normalize();
    }

    public Path sdkRoot() {
        Path root = resourcePath();
        return root == null ? null : root.resolve(SDK_DIR).toAbsolutePath().normalize();
    }

    private Path resourcePath() {
        try {
            return resource == null ? null : resource.getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }
}
