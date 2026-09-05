package dtm.ide.deps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public final class MavenCentralClient {

    private static final String SEARCH_URL = "https://search.maven.org/solrsearch/select";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int DEFAULT_ROWS = 30;
    private static final int VERSION_ROWS = 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;
    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();

    public record SearchResult(DependencyCoordinate coordinate, int versionCount, long lastUpdated) {
    }

    public List<SearchResult> search(String query) {
        return trySearch(query).orElseGet(List::of);
    }

    public Optional<List<SearchResult>> trySearch(String query) {
        if (query == null || query.isBlank()) {
            return Optional.of(List.of());
        }
        String solrQuery = toSolrQuery(query.trim());
        JsonNode docs = get(SEARCH_URL + "?q=" + encode(solrQuery)
                + "&rows=" + DEFAULT_ROWS + "&wt=json");
        if (docs == null) {
            return Optional.empty();
        }
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode doc : docs) {
            String groupId = doc.path("g").asText("");
            String artifactId = doc.path("a").asText("");
            if (groupId.isBlank() || artifactId.isBlank()) {
                continue;
            }
            results.add(new SearchResult(
                    DependencyCoordinate.of(groupId, artifactId, doc.path("latestVersion").asText("")),
                    doc.path("versionCount").asInt(0),
                    doc.path("timestamp").asLong(0)));
        }
        return Optional.of(results);
    }

    public List<String> versions(String groupId, String artifactId) {
        if (groupId == null || artifactId == null || groupId.isBlank() || artifactId.isBlank()) {
            return List.of();
        }
        String key = groupId + ":" + artifactId;
        List<String> cached = versionCache.get(key);
        if (cached != null) {
            return cached;
        }
        String solrQuery = "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"";
        JsonNode docs = get(SEARCH_URL + "?q=" + encode(solrQuery)
                + "&core=gav&rows=" + VERSION_ROWS + "&wt=json");
        if (docs == null) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        for (JsonNode doc : docs) {
            String version = doc.path("v").asText("");
            if (!version.isBlank() && !versions.contains(version)) {
                versions.add(version);
            }
        }
        versionCache.put(key, List.copyOf(versions));
        return versions;
    }

    public String latestStableVersion(String groupId, String artifactId) {
        return versions(groupId, artifactId).stream()
                .filter(MavenCentralClient::isStable)
                .findFirst()
                .orElse("");
    }

    public void clearCache() {
        versionCache.clear();
    }

    public static boolean isStable(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String lower = version.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("snapshot") && !lower.contains("alpha") && !lower.contains("beta")
                && !lower.contains("-rc") && !lower.contains(".rc") && !lower.contains("-m")
                && !lower.contains("preview") && !lower.contains("-ea");
    }

    static String toSolrQuery(String query) {
        int colon = query.indexOf(':');
        if (colon > 0 && colon < query.length() - 1 && !query.contains(" ")) {
            String groupId = query.substring(0, colon).trim();
            String artifactId = query.substring(colon + 1).trim();
            return "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"";
        }
        if (query.contains(" ") || query.contains("*") || query.contains(":")) {
            return query;
        }
        return query + " OR " + query + "*";
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Maven Central respondeu {} para {}", response.statusCode(), url);
                return null;
            }
            JsonNode docs = mapper.readTree(response.body()).path("response").path("docs");
            return docs.isArray() ? docs : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Falha ao consultar o Maven Central: {}", e.getMessage());
            return null;
        }
    }

    private HttpClient http() {
        HttpClient existing = http;
        if (existing != null) return existing;
        synchronized (this) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(TIMEOUT)
                        .build();
            }
            return http;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
