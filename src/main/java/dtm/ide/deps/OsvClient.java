package dtm.ide.deps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public final class OsvClient {

    private static final URI QUERY_BATCH = URI.create("https://api.osv.dev/v1/querybatch");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public record Result(Map<String, List<DependencyVulnerability>> vulnerabilities,
                         boolean failed) {

        public Result {
            vulnerabilities = vulnerabilities == null ? Map.of() : Map.copyOf(vulnerabilities);
        }

        public static Result failure() {
            return new Result(Map.of(), true);
        }
    }

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;

    public OsvClient() {
        this(new ObjectMapper(), HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT).build(), QUERY_BATCH);
    }

    OsvClient(ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.mapper = mapper;
        this.http = http;
        this.endpoint = endpoint;
    }

    public Result query(List<DependencyCoordinate> coordinates) {
        List<DependencyCoordinate> queryable = distinctVersioned(coordinates);
        if (queryable.isEmpty()) {
            return new Result(Map.of(), false);
        }
        try {
            String payload = payload(queryable);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "JavaOrionSupport/1.0")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("OSV respondeu {}", response.statusCode());
                return Result.failure();
            }
            return parseResponse(response.body(), queryable);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.failure();
        } catch (Exception error) {
            log.debug("Falha ao consultar OSV: {}", error.getMessage());
            return Result.failure();
        }
    }

    String payload(List<DependencyCoordinate> coordinates) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode queries = root.putArray("queries");
        for (DependencyCoordinate coordinate : coordinates) {
            ObjectNode query = queries.addObject();
            query.put("version", coordinate.version());
            ObjectNode pkg = query.putObject("package");
            pkg.put("name", coordinate.key());
            pkg.put("ecosystem", "Maven");
        }
        return mapper.writeValueAsString(root);
    }

    Result parseResponse(String body, List<DependencyCoordinate> coordinates) throws Exception {
        JsonNode results = mapper.readTree(body).path("results");
        if (!results.isArray()) {
            return Result.failure();
        }
        Map<String, List<DependencyVulnerability>> byKey = new LinkedHashMap<>();
        for (int index = 0; index < coordinates.size(); index++) {
            JsonNode result = index < results.size() ? results.get(index) : null;
            List<DependencyVulnerability> vulnerabilities = new ArrayList<>();
            if (result != null) {
                for (JsonNode vulnerability : result.path("vulns")) {
                    String id = vulnerability.path("id").asText("");
                    if (!id.isBlank()) {
                        vulnerabilities.add(new DependencyVulnerability(id,
                                vulnerability.path("modified").asText("")));
                    }
                }
            }
            if (!vulnerabilities.isEmpty()) {
                byKey.computeIfAbsent(coordinates.get(index).key(), ignored -> new ArrayList<>())
                        .addAll(vulnerabilities);
            }
        }
        byKey.replaceAll((key, value) -> List.copyOf(value));
        return new Result(byKey, false);
    }

    private static List<DependencyCoordinate> distinctVersioned(
            List<DependencyCoordinate> coordinates) {
        Map<String, DependencyCoordinate> distinct = new LinkedHashMap<>();
        if (coordinates != null) {
            for (DependencyCoordinate coordinate : coordinates) {
                if (coordinate != null && coordinate.isValid() && coordinate.hasVersion()) {
                    distinct.putIfAbsent(coordinate.notation(), coordinate);
                }
            }
        }
        return List.copyOf(distinct.values());
    }
}
