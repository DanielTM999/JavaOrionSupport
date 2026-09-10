package dtm.ide.deps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsvClientTest {

    private final OsvClient client = new OsvClient(new ObjectMapper(), HttpClient.newHttpClient(),
            URI.create("https://example.invalid"));

    @Test
    void createsMavenBatchPayload() throws Exception {
        String json = client.payload(List.of(
                DependencyCoordinate.of("com.fasterxml.jackson.core", "jackson-databind", "2.18.2")));

        var query = new ObjectMapper().readTree(json).path("queries").get(0);
        assertEquals("2.18.2", query.path("version").asText());
        assertEquals("com.fasterxml.jackson.core:jackson-databind",
                query.path("package").path("name").asText());
        assertEquals("Maven", query.path("package").path("ecosystem").asText());
    }

    @Test
    void mapsBatchResultsBackToCoordinates() throws Exception {
        DependencyCoordinate jackson = DependencyCoordinate.of(
                "com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        DependencyCoordinate junit = DependencyCoordinate.of(
                "org.junit.jupiter", "junit-jupiter", "5.10.2");

        OsvClient.Result result = client.parseResponse("""
                {"results":[
                  {"vulns":[{"id":"GHSA-test-1234","modified":"2026-01-01T00:00:00Z"}]},
                  {}
                ]}
                """, List.of(jackson, junit));

        assertFalse(result.failed());
        assertEquals("GHSA-test-1234", result.vulnerabilities()
                .get(jackson.key()).getFirst().id());
        assertTrue(result.vulnerabilities().getOrDefault(junit.key(), List.of()).isEmpty());
    }

    @Test
    void rejectsMalformedResponseShape() throws Exception {
        assertTrue(client.parseResponse("{}", List.of()).failed());
    }
}
