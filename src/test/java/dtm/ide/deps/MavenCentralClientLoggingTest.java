package dtm.ide.deps;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralClientLoggingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private HttpServer server;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(MavenCentralClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseResources() {
        logger.detachAppender(appender);
        appender.stop();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aRejectedSearchIsReportedAsAWarning() throws Exception {
        MavenCentralClient client = new MavenCentralClient(serve(503, "service unavailable"));

        Optional<List<MavenCentralClient.SearchResult>> result = client.trySearch("jackson");

        assertTrue(result.isEmpty());
        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().contains("503"),
                "the status code belongs in the log: " + warnings().getFirst());
    }

    @Test
    void anUnreachableHostIsReportedAsAWarningWithItsCause() {
        MavenCentralClient client = new MavenCentralClient("http://127.0.0.1:1/solrsearch/select");

        Optional<List<MavenCentralClient.SearchResult>> result = client.trySearch("jackson");

        assertTrue(result.isEmpty());
        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().contains("Exception"),
                "the failure cause belongs in the log: " + warnings().getFirst());
    }

    @Test
    void aMalformedPayloadIsReportedInsteadOfFailingSilently() throws Exception {
        MavenCentralClient client = new MavenCentralClient(serve(200, "{\"response\":{}}"));

        Optional<List<MavenCentralClient.SearchResult>> result = client.trySearch("jackson");

        assertTrue(result.isEmpty());
        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().contains("response.docs"),
                "the missing field belongs in the log: " + warnings().getFirst());
    }

    @Test
    void aSuccessfulSearchLogsNothing() throws Exception {
        MavenCentralClient client = new MavenCentralClient(serve(200, """
                {"response":{"docs":[{"g":"com.acme","a":"widget",
                "latestVersion":"1.0.0","versionCount":2,"timestamp":100}]}}
                """));

        Optional<List<MavenCentralClient.SearchResult>> result = client.trySearch("widget");

        assertEquals(1, result.orElseThrow().size());
        assertEquals(List.of(), warnings());
    }

    @Test
    void aFailedVersionLookupIsAlsoReported() throws Exception {
        MavenCentralClient client = new MavenCentralClient(serve(500, "boom"));

        assertEquals(List.of(), client.versions("com.acme", "widget"));
        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().contains("500"));
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/solrsearch/select", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/solrsearch/select";
    }
}
