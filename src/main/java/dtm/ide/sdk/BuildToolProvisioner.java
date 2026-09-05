package dtm.ide.sdk;

import dtm.ide.project.JavaProjectDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
public class BuildToolProvisioner {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(BuildToolProvisioner.class, key, def);
    }

    public static final String MAVEN_PROGRESS_ID = "downloadMaven";
    public static final String GRADLE_PROGRESS_ID = "downloadGradle";

    public static final String MANAGED_MAVEN_VERSION = "3.9.9";
    public static final String MANAGED_GRADLE_VERSION = "8.10.2";

    private static final String MAVEN_DOWNLOAD_URL =
            "https://archive.apache.org/dist/maven/maven-3/%1$s/binaries/apache-maven-%1$s-bin.zip";
    private static final String GRADLE_DOWNLOAD_URL =
            "https://services.gradle.org/distributions/gradle-%s-bin.zip";

    private final JdkService jdkService;
    private final SdkDownloader downloader;

    public BuildToolProvisioner(JdkService jdkService, SdkDownloader downloader) {
        this.jdkService = jdkService;
        this.downloader = downloader;
    }

    public enum ToolOrigin {
        WRAPPER,
        PATH,
        MANAGED
    }

    public record BuildTool(Path executable, ToolOrigin origin) {
    }

    public Optional<BuildTool> findMaven(JavaProjectDescriptor descriptor) {
        Optional<BuildTool> wrapper = wrapperOf(descriptor);
        if (wrapper.isPresent()) {
            return wrapper;
        }
        Optional<Path> onPath = findOnPath(Platform.isWindowsHost() ? "mvn.cmd" : "mvn", "mvn");
        if (onPath.isPresent()) {
            return onPath.map(path -> new BuildTool(path, ToolOrigin.PATH));
        }
        return managedMaven().map(path -> new BuildTool(path, ToolOrigin.MANAGED));
    }

    public BuildTool ensureMaven(JavaProjectDescriptor descriptor, DownloadProgressListener listener) {
        Optional<BuildTool> existing = findMaven(descriptor);
        if (existing.isPresent()) {
            return existing.get();
        }
        Path root = managedRoot("maven").orElseThrow(this::resourceUnavailable);
        downloader.download(new SdkArtifact(
                "apache-maven-" + MANAGED_MAVEN_VERSION + "-bin.zip",
                String.format(MAVEN_DOWNLOAD_URL, MANAGED_MAVEN_VERSION),
                MAVEN_PROGRESS_ID,
                text("download.maven", "Baixando Maven") + " " + MANAGED_MAVEN_VERSION), root, listener);

        return managedMaven()
                .map(path -> new BuildTool(path, ToolOrigin.MANAGED))
                .orElseThrow(() -> SdkDownloader.error(text("error.mavenMissing",
                        "O Maven foi baixado, mas o executavel nao foi encontrado."), null));
    }

    private Optional<Path> managedMaven() {
        return managedRoot("maven")
                .flatMap(SdkDownloader::singleChildDirectory)
                .map(home -> home.resolve("bin").resolve(Platform.isWindowsHost() ? "mvn.cmd" : "mvn"))
                .filter(Files::isRegularFile);
    }

    public Optional<BuildTool> findGradle(JavaProjectDescriptor descriptor) {
        Optional<BuildTool> wrapper = wrapperOf(descriptor);
        if (wrapper.isPresent()) {
            return wrapper;
        }
        Optional<Path> onPath = findOnPath(Platform.isWindowsHost() ? "gradle.bat" : "gradle", "gradle");
        if (onPath.isPresent()) {
            return onPath.map(path -> new BuildTool(path, ToolOrigin.PATH));
        }
        return managedGradle().map(path -> new BuildTool(path, ToolOrigin.MANAGED));
    }

    public BuildTool ensureGradle(JavaProjectDescriptor descriptor, DownloadProgressListener listener) {
        Optional<BuildTool> existing = findGradle(descriptor);
        if (existing.isPresent()) {
            return existing.get();
        }
        Path root = managedRoot("gradle").orElseThrow(this::resourceUnavailable);
        downloader.download(new SdkArtifact(
                "gradle-" + MANAGED_GRADLE_VERSION + "-bin.zip",
                String.format(GRADLE_DOWNLOAD_URL, MANAGED_GRADLE_VERSION),
                GRADLE_PROGRESS_ID,
                text("download.gradle", "Baixando Gradle") + " " + MANAGED_GRADLE_VERSION), root, listener);

        return managedGradle()
                .map(path -> new BuildTool(path, ToolOrigin.MANAGED))
                .orElseThrow(() -> SdkDownloader.error(text("error.gradleMissing",
                        "O Gradle foi baixado, mas o executavel nao foi encontrado."), null));
    }

    private Optional<Path> managedGradle() {
        return managedRoot("gradle")
                .flatMap(SdkDownloader::singleChildDirectory)
                .map(home -> home.resolve("bin").resolve(Platform.isWindowsHost() ? "gradle.bat" : "gradle"))
                .filter(Files::isRegularFile);
    }

    private Optional<BuildTool> wrapperOf(JavaProjectDescriptor descriptor) {
        return descriptor == null
                ? Optional.empty()
                : descriptor.wrapperPath().map(path -> new BuildTool(path, ToolOrigin.WRAPPER));
    }

    static Optional<Path> findOnPath(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        Set<String> directories = new LinkedHashSet<>(
                List.of(pathEnv.split(Pattern.quote(File.pathSeparator))));
        for (String directory : directories) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(directory.trim()).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Path> managedRoot(String tool) {
        Path sdkRoot = jdkService == null ? null : jdkService.sdkRoot();
        return sdkRoot == null ? Optional.empty() : Optional.of(sdkRoot.resolve(tool));
    }

    private RuntimeException resourceUnavailable() {
        return SdkDownloader.error(text("error.resourceDirUnavailable",
                "Plugin resource directory is not available."), null);
    }
}
