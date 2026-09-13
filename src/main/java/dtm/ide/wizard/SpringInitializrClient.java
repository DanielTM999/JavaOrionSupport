package dtm.ide.wizard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.sdk.SdkDownloader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class SpringInitializrClient {

    private static final String BASE_URL = "https://start.spring.io";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MODERN_QUALIFIED_BOOT_VERSION = Pattern.compile(
            "^(\\d+)\\.\\d+\\.\\d+\\.(RELEASE|BUILD-SNAPSHOT|M\\d+|RC\\d+)$");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    public record Option(String id, String name) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? id : name;
        }
    }

    public record Starter(String id, String name, String description, String group) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record Metadata(
            List<Option> bootVersions,
            String defaultBootVersion,
            List<Option> javaVersions,
            List<Option> languages,
            List<Option> packagings,
            List<Starter> starters
    ) {

        public Metadata {
            bootVersions = bootVersions == null ? List.of() : List.copyOf(bootVersions);
            javaVersions = javaVersions == null ? List.of() : List.copyOf(javaVersions);
            languages = languages == null ? List.of() : List.copyOf(languages);
            packagings = packagings == null ? List.of() : List.copyOf(packagings);
            starters = starters == null ? List.of() : List.copyOf(starters);
        }

        public boolean isEmpty() {
            return bootVersions.isEmpty() && starters.isEmpty();
        }
    }

    public record GenerateRequest(
            String type,
            String language,
            String bootVersion,
            String groupId,
            String artifactId,
            String version,
            String name,
            String description,
            String packageName,
            String javaVersion,
            String packaging,
            List<String> dependencies
    ) {

        public GenerateRequest {
            type = blankTo(type, "maven-project");
            language = blankTo(language, "java");
            groupId = blankTo(groupId, "com.example");
            artifactId = blankTo(artifactId, "demo");
            version = blankTo(version, "0.0.1-SNAPSHOT");
            name = blankTo(name, artifactId);
            description = description == null ? "" : description.trim();
            packageName = blankTo(packageName, groupId + "." + artifactId.replace("-", ""));
            javaVersion = blankTo(javaVersion, "21");
            packaging = blankTo(packaging, "jar");
            bootVersion = normalizeBootVersion(bootVersion);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public Metadata metadata() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/metadata/client"))
                    .header("Accept", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Initializr respondeu {}", response.statusCode());
                return emptyMetadata();
            }
            return parseMetadata(MAPPER.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyMetadata();
        } catch (Exception e) {
            log.debug("Initializr indisponivel: {}", e.getMessage());
            return emptyMetadata();
        }
    }

    static Metadata parseMetadata(JsonNode root) {
        if (root == null) {
            return emptyMetadata();
        }
        return new Metadata(
                options(root.path("bootVersion")),
                root.path("bootVersion").path("default").asText(""),
                options(root.path("javaVersion")),
                options(root.path("language")),
                options(root.path("packaging")),
                starters(root.path("dependencies")));
    }

    private static List<Option> options(JsonNode node) {
        List<Option> options = new ArrayList<>();
        for (JsonNode value : node.path("values")) {
            String id = value.path("id").asText("");
            if (!id.isBlank()) {
                options.add(new Option(id, value.path("name").asText(id)));
            }
        }
        return options;
    }

    private static List<Starter> starters(JsonNode dependencies) {
        List<Starter> starters = new ArrayList<>();
        for (JsonNode group : dependencies.path("values")) {
            String groupName = group.path("name").asText("");
            for (JsonNode value : group.path("values")) {
                String id = value.path("id").asText("");
                if (!id.isBlank()) {
                    starters.add(new Starter(id, value.path("name").asText(id),
                            value.path("description").asText(""), groupName));
                }
            }
        }
        return starters;
    }

    private static Metadata emptyMetadata() {
        return new Metadata(List.of(), "", List.of(), List.of(), List.of(), List.of());
    }

    public Path generate(GenerateRequest request, Path targetDirectory) throws Exception {
        Path staging = Files.createTempDirectory("orion-initializr");
        Path archive = staging.resolve("starter.zip");
        try {
            download(buildUrl(request), archive);
            SdkDownloader.installArchive(archive, staging);

            Path extracted = SdkDownloader.singleChildDirectory(staging).orElse(staging);
            Files.createDirectories(targetDirectory);
            moveContents(extracted, targetDirectory);
            return targetDirectory;
        } finally {
            SdkDownloader.deleteRecursively(staging);
        }
    }

    private void download(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "JavaOrionSupport/1.0")
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "O Spring Initializr respondeu " + response.statusCode()
                            + "; verifique as opcoes escolhidas.");
        }
        try (InputStream body = response.body()) {
            Files.copy(body, target);
        }
    }

    private static void moveContents(Path from, Path to) throws Exception {
        if (from.equals(to)) {
            return;
        }
        try (var entries = Files.list(from)) {
            for (Path entry : entries.toList()) {
                Path destination = to.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                    moveContents(entry, destination);
                } else if (!Files.exists(destination)) {
                    Files.move(entry, destination);
                }
            }
        }
    }

    static String buildUrl(GenerateRequest request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", request.type());
        parameters.put("language", request.language());
        parameters.put("groupId", request.groupId());
        parameters.put("artifactId", request.artifactId());
        parameters.put("version", request.version());
        parameters.put("name", request.name());
        parameters.put("packageName", request.packageName());
        parameters.put("javaVersion", request.javaVersion());
        parameters.put("packaging", request.packaging());
        if (!request.description().isBlank()) {
            parameters.put("description", request.description());
        }
        if (!request.bootVersion().isBlank()) {
            parameters.put("bootVersion", request.bootVersion());
        }
        if (!request.dependencies().isEmpty()) {
            parameters.put("dependencies", String.join(",", request.dependencies()));
        }

        StringBuilder url = new StringBuilder(BASE_URL).append("/starter.zip?");
        parameters.forEach((key, value) -> url.append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&'));
        url.setLength(url.length() - 1);
        return url.toString();
    }

    /**
     * Converte a notacao interna exposta pelo metadata do Initializr para a versao
     * efetivamente publicada nos repositorios Spring/Maven. O Spring Boot 2 e anterior
     * usava {@code .RELEASE} de verdade, portanto essas versoes permanecem intactas.
     */
    static String normalizeBootVersion(String version) {
        String value = version == null ? "" : version.trim();
        Matcher matcher = MODERN_QUALIFIED_BOOT_VERSION.matcher(value);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 3) {
            return value;
        }

        String qualifier = matcher.group(2);
        String base = value.substring(0, value.length() - qualifier.length() - 1);
        return switch (qualifier) {
            case "RELEASE" -> base;
            case "BUILD-SNAPSHOT" -> base + "-SNAPSHOT";
            default -> base + "-" + qualifier;
        };
    }

    public static String projectTypeOf(boolean gradle) {
        return gradle ? "gradle-project-kotlin" : "maven-project";
    }

    public static Optional<String> preferredBootVersion(Metadata metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        if (!metadata.defaultBootVersion().isBlank()) {
            return Optional.of(metadata.defaultBootVersion());
        }
        return metadata.bootVersions().stream().map(Option::id).findFirst();
    }
}
