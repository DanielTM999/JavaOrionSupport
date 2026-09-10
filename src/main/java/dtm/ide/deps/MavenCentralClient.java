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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MavenCentralClient {

    private static final String SEARCH_URL = "https://central.sonatype.com/solrsearch/select";
    private static final String METADATA_URL = "https://repo1.maven.org/maven2";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);
    private static final int DEFAULT_ROWS = 30;
    private static final int VERSION_ROWS = 60;
    private static final Pattern VERSION_TAG =
            Pattern.compile("<version>\\s*([^<\\s][^<]*?)\\s*</version>");

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;
    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final String searchUrl;
    private final String metadataUrl;
    private final long cooldownMillis;
    private final LongSupplier clock;
    private final AtomicLong openUntil = new AtomicLong();
    private volatile Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    public MavenCentralClient() {
        this(SEARCH_URL);
    }

    MavenCentralClient(String searchUrl) {
        this(searchUrl, null, DEFAULT_COOLDOWN, System::currentTimeMillis);
    }

    MavenCentralClient(String searchUrl, String metadataUrl) {
        this(searchUrl, metadataUrl, DEFAULT_COOLDOWN, System::currentTimeMillis);
    }

    MavenCentralClient(String searchUrl, String metadataUrl, Duration cooldown, LongSupplier clock) {
        this.searchUrl = searchUrl == null || searchUrl.isBlank() ? SEARCH_URL : searchUrl;
        this.metadataUrl = trimSlash(metadataUrl == null || metadataUrl.isBlank()
                ? METADATA_URL : metadataUrl);
        this.cooldownMillis = cooldown == null ? DEFAULT_COOLDOWN.toMillis() : cooldown.toMillis();
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public void setRequestTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
            return;
        }
        if (timeout.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
            requestTimeout = MIN_REQUEST_TIMEOUT;
        } else if (timeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            requestTimeout = MAX_REQUEST_TIMEOUT;
        } else {
            requestTimeout = timeout;
        }
    }

    public boolean isCoolingDown() {
        return clock.getAsLong() < openUntil.get();
    }

    public record SearchResult(DependencyCoordinate coordinate, int versionCount, long lastUpdated) {
    }

    public List<SearchResult> search(String query) {
        return trySearch(query).orElseGet(List::of);
    }

    public Optional<List<SearchResult>> trySearch(String query) {
        if (query == null || query.isBlank()) {
            return Optional.of(List.of());
        }
        String trimmed = query.trim();
        String[] coordinate = splitCoordinate(trimmed);
        if (coordinate != null) {
            return searchByCoordinate(coordinate[0], coordinate[1]);
        }
        String solrQuery = toSolrQuery(trimmed);
        JsonNode docs = get(searchUrl + "?q=" + encode(solrQuery)
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

    private Optional<List<SearchResult>> searchByCoordinate(String groupId, String artifactId) {
        if (isCoolingDown()) {
            return Optional.empty();
        }
        List<String> found = versions(groupId, artifactId);
        if (found.isEmpty()) {
            return isCoolingDown() ? Optional.empty() : Optional.of(List.of());
        }
        String latest = found.stream()
                .filter(MavenCentralClient::isStable)
                .max(MavenVersionOrder::compare)
                .orElse(found.getFirst());
        return Optional.of(List.of(new SearchResult(
                DependencyCoordinate.of(groupId, artifactId, latest), found.size(), 0L)));
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
        String metadata = getText(metadataUrl + "/" + groupId.trim().replace('.', '/')
                + "/" + artifactId.trim() + "/maven-metadata.xml");
        if (metadata == null) {
            return List.of();
        }
        List<String> versions = parseVersions(metadata);
        versionCache.put(key, versions);
        return versions;
    }

    static List<String> parseVersions(String metadata) {
        List<String> found = new ArrayList<>();
        Matcher matcher = VERSION_TAG.matcher(metadata);
        while (matcher.find()) {
            String version = matcher.group(1);
            if (!found.contains(version)) {
                found.add(version);
            }
        }
        found.sort(MavenVersionOrder.DESCENDING);
        return List.copyOf(found.size() > VERSION_ROWS ? found.subList(0, VERSION_ROWS) : found);
    }

    public String latestStableVersion(String groupId, String artifactId) {
        return versions(groupId, artifactId).stream()
                .filter(MavenCentralClient::isStable)
                .max(MavenVersionOrder::compare)
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

    static String[] splitCoordinate(String query) {
        int colon = query.indexOf(':');
        if (colon <= 0 || colon >= query.length() - 1 || query.contains(" ")) {
            return null;
        }
        String groupId = query.substring(0, colon).trim();
        String artifactId = query.substring(colon + 1).trim();
        if (groupId.isBlank() || artifactId.isBlank() || artifactId.contains(":")) {
            return null;
        }
        return new String[] {groupId, artifactId};
    }

    static String toSolrQuery(String query) {
        String[] coordinate = splitCoordinate(query);
        if (coordinate != null) {
            return "g:" + coordinate[0] + " AND a:" + coordinate[1];
        }
        if (query.contains(" ") || query.contains("*") || query.contains(":")) {
            return query;
        }
        return query + " OR " + query + "*";
    }

    private JsonNode get(String url) {
        String body = getText(url);
        if (body == null) {
            return null;
        }
        try {
            JsonNode docs = mapper.readTree(body).path("response").path("docs");
            if (!docs.isArray()) {
                log.warn("Resposta inesperada do Maven Central para {}: sem a lista 'response.docs'",
                        url);
                startCooldown();
                return null;
            }
            return docs;
        } catch (Exception e) {
            log.warn("Falha ao consultar o Maven Central em {}: {}", url, reason(e));
            startCooldown();
            return null;
        }
    }

    private String getText(String url) {
        if (isCoolingDown()) {
            log.debug("Maven Central em espera apos falha recente; consulta ignorada: {}", url);
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                log.debug("Maven Central nao conhece {}", url);
                openUntil.set(0);
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("Maven Central respondeu {} para {}", response.statusCode(), url);
                startCooldown();
                return null;
            }
            openUntil.set(0);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Consulta ao Maven Central interrompida: {}", url);
            startCooldown();
            return null;
        } catch (Exception e) {
            log.warn("Falha ao consultar o Maven Central em {}: {}", url, reason(e));
            startCooldown();
            return null;
        }
    }

    private void startCooldown() {
        long until = clock.getAsLong() + cooldownMillis;
        openUntil.set(until);
        log.debug("Maven Central em espera por {}ms apos a falha", cooldownMillis);
    }

    private static String reason(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private HttpClient http() {
        HttpClient existing = http;
        if (existing != null) return existing;
        synchronized (this) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build();
            }
            return http;
        }
    }

    private static String trimSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
