package dtm.ide.sdk;

import dtm.ide.api.extension.Resource;
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
    private static final String SELECTIONS_FILE = "project-jdks.properties";

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

    public enum JdkOutcome {
        FOUND,
        INSTALLED,
        FALLBACK,
        UNRESOLVED
    }

    public record JdkResolution(JdkInstallation installation, JdkOutcome outcome,
                                int requestedMajor, String failure) {

        public boolean resolved() {
            return installation != null;
        }
    }

    public JdkResolution provisionForProject(JavaProjectDescriptor descriptor,
                                             int defaultMajor,
                                             DownloadProgressListener progressListener) {
        int required = descriptor == null
                ? defaultMajor
                : descriptor.jdkMajor().orElse(defaultMajor);

        JdkInstallation pinned = pinnedHome(descriptor);
        if (pinned != null) {
            return new JdkResolution(pinned,
                    pinned.major() == required ? JdkOutcome.FOUND : JdkOutcome.FALLBACK,
                    required, null);
        }

        Optional<JdkInstallation> exact = find(required);
        if (exact.isPresent()) {
            return new JdkResolution(exact.get(), JdkOutcome.FOUND, required, null);
        }

        String failure = null;
        int downloadable = downloadableMajorFor(required);
        if (downloadable > 0) {
            try {
                JdkInstallation installed = ensure(downloadable, progressListener);
                JdkOutcome outcome = installed.major() == required
                        ? JdkOutcome.INSTALLED
                        : JdkOutcome.FALLBACK;
                return new JdkResolution(installed, outcome, required, null);
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Falha ao instalar a JDK {} para o projeto: {}", downloadable, failure);
            }
        }

        Optional<JdkInstallation> fallback = resolveForProject(descriptor, defaultMajor);
        if (fallback.isPresent()) {
            return new JdkResolution(fallback.get(), JdkOutcome.FALLBACK, required, failure);
        }
        return new JdkResolution(null, JdkOutcome.UNRESOLVED, required, failure);
    }

    public int downloadableMajorFor(int required) {
        if (DOWNLOADABLE_MAJORS.contains(required)) {
            return required;
        }
        return DOWNLOADABLE_MAJORS.stream()
                .filter(major -> major >= required)
                .min(Comparator.naturalOrder())
                .orElse(-1);
    }

    public Optional<JdkInstallation> inspectExisting(Path home) {
        if (home == null) {
            return Optional.empty();
        }
        try {
            return JdkDetector.inspect(home.toAbsolutePath().normalize(),
                            JdkInstallation.JdkOrigin.SYSTEM)
                    .filter(JdkInstallation::isUsable);
        } catch (Exception e) {
            log.debug("Falha ao inspecionar a JDK em {}: {}", home, e.getMessage());
            return Optional.empty();
        }
    }

    private JdkInstallation pinnedHome(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        Path home = readSelectedHome(descriptor.root());
        return home == null ? null : inspectExisting(home).orElse(null);
    }

    public Path readSelectedHome(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        String value = selections().getProperty(selectionKey(projectRoot), "").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public void selectHomeForProject(Path projectRoot, Path jdkHome) {
        if (projectRoot == null) {
            return;
        }
        Path file = selectionsFile();
        if (file == null) {
            return;
        }
        try {
            Properties props = selections();
            if (jdkHome == null) {
                props.remove(selectionKey(projectRoot));
            } else {
                props.setProperty(selectionKey(projectRoot),
                        jdkHome.toAbsolutePath().normalize().toString());
            }
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "JavaOrionSupport");
            }
        } catch (Exception e) {
            log.debug("Falha ao gravar a JDK escolhida para {}: {}", projectRoot, e.getMessage());
        }
    }

    private Properties selections() {
        Properties props = new Properties();
        Path file = selectionsFile();
        if (file == null || !Files.isRegularFile(file)) {
            return props;
        }
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            log.debug("Falha ao ler {}: {}", file, e.getMessage());
        }
        return props;
    }

    private Path selectionsFile() {
        Path root = sdkRoot();
        return root == null ? null : root.resolve(SELECTIONS_FILE);
    }

    private static String selectionKey(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().toString();
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
