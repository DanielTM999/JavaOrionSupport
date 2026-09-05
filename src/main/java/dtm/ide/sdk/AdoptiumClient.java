package dtm.ide.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Slf4j
public final class AdoptiumClient {

    private static final String ASSETS_URL = "https://api.adoptium.net/v3/assets/latest/%d/hotspot";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    public Optional<AdoptiumRelease> latest(int major) {
        return latest(major, Platform.current());
    }

    public Optional<AdoptiumRelease> latest(int major, Platform platform) {
        String url = String.format(ASSETS_URL, major)
                + "?os=" + platform.adoptiumOs()
                + "&architecture=" + platform.adoptiumArch()
                + "&image_type=jdk"
                + "&vendor=eclipse";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Adoptium respondeu {} para a JDK {}", response.statusCode(), major);
                return Optional.empty();
            }
            return parse(mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Falha ao consultar o Adoptium para a JDK {}: {}", major, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AdoptiumRelease> parse(JsonNode root) {
        if (root == null || !root.isArray() || root.isEmpty()) {
            return Optional.empty();
        }
        for (JsonNode asset : root) {
            JsonNode pkg = asset.path("binary").path("package");
            String link = pkg.path("link").asText("");
            String name = pkg.path("name").asText("");
            String version = asset.path("version").path("semver").asText("");
            if (version.isBlank()) {
                version = asset.path("release_name").asText("");
            }
            if (!link.isBlank() && !name.isBlank()) {
                return Optional.of(new AdoptiumRelease(version, link, name));
            }
        }
        return Optional.empty();
    }

    public record AdoptiumRelease(String version, String link, String fileName) {
    }
}
