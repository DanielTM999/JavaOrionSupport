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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class LombokAgentResolver {

    public static final String GROUP_ID = "org.projectlombok";
    public static final String ARTIFACT_ID = "lombok";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String CENTRAL_BASE = "https://repo1.maven.org/maven2/";

    private static final String FALLBACK_VERSION = "1.18.42";

    private static final Pattern POM_LOMBOK_VERSION = Pattern.compile(
            "(?s)<artifactId>\\s*lombok\\s*</artifactId>.{0,400}?<version>\\s*([^<]+?)\\s*</version>");

    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static final Pattern GRADLE_LOMBOK_VERSION = Pattern.compile(
            "org\\.projectlombok:lombok:([^\"'\\s]+)");

    private final Path sdkRoot;

    public LombokAgentResolver(Path sdkRoot) {
        this.sdkRoot = sdkRoot;
    }

    public Optional<Path> resolve(JavaProjectDescriptor descriptor) {
        Optional<String> version = detectVersion(descriptor);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        return jarFor(version.get());
    }

    public Optional<Path> jarFor(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        Path local = localRepositoryJar(version);
        if (local != null && Files.isRegularFile(local)) {
            return Optional.of(local);
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
        if (descriptor == null) {
            return Optional.empty();
        }
        boolean declared = false;
        for (JavaModule module : descriptor.modules()) {
            for (Path buildFile : buildFilesOf(module)) {
                String content = JavaProjectConventions.readOrEmpty(buildFile);
                if (content.isBlank() || !content.contains(ARTIFACT_ID)) {
                    continue;
                }
                declared = true;
                Optional<String> version = versionFrom(content, buildFile);
                if (version.isPresent()) {
                    return version;
                }
            }
        }
        return declared ? Optional.of(FALLBACK_VERSION) : Optional.empty();
    }

    private static List<Path> buildFilesOf(JavaModule module) {
        Path root = module.root();
        return List.of(
                root.resolve(JavaProjectConventions.POM_FILE),
                root.resolve(JavaProjectConventions.GRADLE_BUILD_GROOVY),
                root.resolve(JavaProjectConventions.GRADLE_BUILD_KOTLIN));
    }

    private static Optional<String> versionFrom(String content, Path buildFile) {
        if (JavaProjectConventions.isMavenPom(buildFile)) {
            return mavenVersion(content);
        }
        return gradleVersion(content);
    }

    private static Optional<String> mavenVersion(String pomXml) {
        String declared = declaredVersion(PomEditor.readDependencies(pomXml));
        if (declared.isBlank()) {
            Matcher matcher = POM_LOMBOK_VERSION.matcher(pomXml);
            declared = matcher.find() ? matcher.group(1).trim() : "";
        }
        String resolved = resolveProperties(declared, pomXml);
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

    private static String resolveProperties(String version, String pomXml) {
        if (version == null || version.isBlank() || !version.contains("${")) {
            return version;
        }
        Matcher matcher = PROPERTY_REFERENCE.matcher(version);
        if (!matcher.find()) {
            return version;
        }
        MavenPom pom = MavenPom.parseContent(pomXml);
        String resolved = pom.property(matcher.group(1).trim());
        return resolved == null || resolved.isBlank() ? "" : resolved.trim();
    }

    private static Path localRepositoryJar(String version) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".m2", "repository", "org", "projectlombok", "lombok", version,
                "lombok-" + version + ".jar");
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
