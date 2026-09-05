package dtm.ide.spring.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class SpringActuatorClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile HttpClient http;

    public record LiveBean(String name, String type, String scope, List<String> dependencies) {

        public LiveBean {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public String simpleType() {
            int lastDot = type.lastIndexOf('.');
            return lastDot >= 0 ? type.substring(lastDot + 1) : type;
        }
    }

    public record LiveProperty(String key, String value, String source) {
    }

    public record LiveMapping(String method, String path, String handler) {
    }

    public boolean isAvailable(String baseUrl) {
        return get(baseUrl, "/health").isPresent();
    }

    public String health(String baseUrl) {
        return get(baseUrl, "/health")
                .map(node -> node.path("status").asText(""))
                .orElse("");
    }

    public List<LiveBean> beans(String baseUrl) {
        Optional<JsonNode> response = get(baseUrl, "/beans");
        if (response.isEmpty()) {
            return List.of();
        }
        List<LiveBean> beans = new ArrayList<>();
        for (JsonNode context : response.get().path("contexts")) {
            JsonNode beansNode = context.path("beans");
            Iterator<Map.Entry<String, JsonNode>> fields = beansNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode bean = entry.getValue();
                beans.add(new LiveBean(
                        entry.getKey(),
                        bean.path("type").asText(""),
                        bean.path("scope").asText("singleton"),
                        textList(bean.path("dependencies"))));
            }
        }
        return beans;
    }

    public List<LiveProperty> environment(String baseUrl) {
        Optional<JsonNode> response = get(baseUrl, "/env");
        if (response.isEmpty()) {
            return List.of();
        }
        List<LiveProperty> properties = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (JsonNode source : response.get().path("propertySources")) {
            String sourceName = source.path("name").asText("");
            Iterator<Map.Entry<String, JsonNode>> fields = source.path("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (seen.contains(entry.getKey())) {
                    continue;
                }
                seen.add(entry.getKey());
                properties.add(new LiveProperty(entry.getKey(),
                        entry.getValue().path("value").asText(""), sourceName));
            }
        }
        return properties;
    }

    public List<LiveMapping> mappings(String baseUrl) {
        Optional<JsonNode> response = get(baseUrl, "/mappings");
        if (response.isEmpty()) {
            return List.of();
        }
        List<LiveMapping> mappings = new ArrayList<>();
        for (JsonNode context : response.get().path("contexts")) {
            collectMappings(context.path("mappings"), mappings);
        }
        return mappings;
    }

    private static void collectMappings(JsonNode node, List<LiveMapping> mappings) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.has("details") || element.has("predicate")) {
                    readMapping(element, mappings);
                } else {
                    collectMappings(element, mappings);
                }
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectMappings(entry.getValue(), mappings));
        }
    }

    private static void readMapping(JsonNode element, List<LiveMapping> mappings) {
        String handler = element.path("handler").asText("");
        JsonNode conditions = element.path("details").path("requestMappingConditions");

        List<String> patterns = textList(conditions.path("patterns"));
        List<String> methods = textList(conditions.path("methods"));

        if (patterns.isEmpty()) {
            String predicate = element.path("predicate").asText("");
            if (!predicate.isBlank()) {
                mappings.add(new LiveMapping("", predicate, handler));
            }
            return;
        }
        for (String pattern : patterns) {
            if (methods.isEmpty()) {
                mappings.add(new LiveMapping("ANY", pattern, handler));
            } else {
                methods.forEach(method -> mappings.add(new LiveMapping(method, pattern, handler)));
            }
        }
    }

    private Optional<JsonNode> get(String baseUrl, String endpoint) {
        String url = actuatorUrl(baseUrl) + endpoint;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Actuator indisponivel em {}: {}", url, e.getMessage());
            return Optional.empty();
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

    static String actuatorUrl(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/actuator") ? base : base + "/actuator";
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            String value = element.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }
}
