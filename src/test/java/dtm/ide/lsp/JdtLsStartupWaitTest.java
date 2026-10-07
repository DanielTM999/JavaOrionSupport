package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsStartupWaitTest {

    @Test
    void keepsWaitingWhileTheServerIsAliveAndReportsProgress() throws Exception {
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        List<Long> waits = new ArrayList<>();

        JsonNode result = LspSession.awaitWhileAlive(response, () -> true, 20, 5_000, elapsed -> {
            waits.add(elapsed);
            if (waits.size() == 3) {
                response.complete(TextNode.valueOf("ok"));
            }
        });

        assertEquals("ok", result.asText());
        assertEquals(3, waits.size());
        assertTrue(waits.get(2) >= waits.get(0));
    }

    @Test
    void stopsAsSoonAsTheServerDies() {
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        AtomicBoolean alive = new AtomicBoolean(true);
        long started = System.nanoTime();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                LspSession.awaitWhileAlive(response, alive::get, 20, 60_000, elapsed -> alive.set(false)));

        assertTrue(failure.getMessage().contains("encerrou"));
        assertTrue(response.isCancelled());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000);
    }

    @Test
    void givesUpOnlyAfterTheCeiling() {
        CompletableFuture<JsonNode> response = new CompletableFuture<>();

        assertThrows(TimeoutException.class, () ->
                LspSession.awaitWhileAlive(response, () -> true, 10, 50, elapsed -> { }));
        assertTrue(response.isCancelled());
    }

    @Test
    void anAnswerThatIsAlreadyThereComesBackImmediately() throws Exception {
        JsonNode result = LspSession.awaitWhileAlive(
                CompletableFuture.completedFuture(TextNode.valueOf("pronto")), () -> false, 20, 50, elapsed -> { });

        assertEquals("pronto", result.asText());
    }
}
