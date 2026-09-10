package dtm.ide.deps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralClientResilienceTest {

    private static final String PAYLOAD = """
            {"response":{"docs":[{"g":"com.acme","a":"widget",
            "latestVersion":"1.0.0","versionCount":2,"timestamp":100}]}}
            """;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicLong now = new AtomicLong(1_000_000);

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aFailureStopsEveryFollowingRequestUntilTheCooldownExpires() throws Exception {
        MavenCentralClient client = client(Duration.ofSeconds(60));
        status.set(503);

        assertTrue(client.trySearch("widget").isEmpty());
        assertEquals(1, hits.get());
        assertTrue(client.isCoolingDown());

        assertTrue(client.trySearch("widget").isEmpty());
        assertTrue(client.trySearch("other").isEmpty());
        assertEquals(List.of(), client.versions("com.acme", "widget"));

        assertEquals(1, hits.get(), "nenhuma requisicao deve sair durante o cool-off");
    }

    @Test
    void theCentralIsTriedAgainOnceTheCooldownElapses() throws Exception {
        MavenCentralClient client = client(Duration.ofSeconds(60));
        status.set(503);

        assertTrue(client.trySearch("widget").isEmpty());
        assertEquals(1, hits.get());

        now.addAndGet(Duration.ofSeconds(59).toMillis());
        assertTrue(client.trySearch("widget").isEmpty());
        assertEquals(1, hits.get());

        now.addAndGet(Duration.ofSeconds(2).toMillis());
        status.set(200);
        assertEquals(1, client.trySearch("widget").orElseThrow().size());
        assertEquals(2, hits.get());
        assertFalse(client.isCoolingDown());
    }

    @Test
    void aSuccessfulCallClearsAPreviousCooldown() throws Exception {
        MavenCentralClient client = client(Duration.ofMillis(1));
        status.set(500);

        assertTrue(client.trySearch("widget").isEmpty());
        assertTrue(client.isCoolingDown());

        now.addAndGet(10);
        status.set(200);
        assertEquals(1, client.trySearch("widget").orElseThrow().size());

        assertFalse(client.isCoolingDown());
        assertEquals(1, client.trySearch("widget").orElseThrow().size());
        assertEquals(3, hits.get());
    }

    @Test
    void aSlowEndpointFailsWithinTheConfiguredBudgetInsteadOfTheOldTwentySeconds() throws Exception {
        MavenCentralClient client = clientThatHangs();
        client.setRequestTimeout(Duration.ofMillis(600));

        long started = System.nanoTime();
        assertTrue(client.trySearch("widget").isEmpty());
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsed < 5_000, "a busca deveria desistir rapido, mas levou " + elapsed + "ms");
        assertTrue(client.isCoolingDown());
    }

    @Test
    void anOutOfRangeTimeoutFallsBackToTheSupportedBounds() throws Exception {
        MavenCentralClient client = client(Duration.ofSeconds(60));

        client.setRequestTimeout(Duration.ofMillis(-5));
        client.setRequestTimeout(null);
        client.setRequestTimeout(Duration.ofHours(3));

        assertEquals(1, client.trySearch("widget").orElseThrow().size());
    }

    private MavenCentralClient client(Duration cooldown) throws IOException {
        return new MavenCentralClient(serve(exchange -> {
            hits.incrementAndGet();
            byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }), null, cooldown, now::get);
    }

    private MavenCentralClient clientThatHangs() throws IOException {
        return new MavenCentralClient(serve(exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), null, Duration.ofSeconds(60), now::get);
    }

    private String serve(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("maven-central-test-", 0).factory()));
        server.createContext("/solrsearch/select", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/solrsearch/select";
    }
}
