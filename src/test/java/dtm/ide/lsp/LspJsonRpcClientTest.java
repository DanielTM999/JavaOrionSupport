package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspJsonRpcClientTest {

    @Test
    void slowNotificationDoesNotBlockRpcResponses() throws Exception {
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        LspJsonRpcClient client = new LspJsonRpcClient(clientInput, clientOutput, "lsp-test");
        CountDownLatch notificationStarted = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        client.onNotification("test/slow", ignored -> {
            notificationStarted.countDown();
            try {
                releaseNotification.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var response = client.request("test/request", Map.of());
        writeFrame(serverOutput, """
                {"jsonrpc":"2.0","method":"test/slow","params":{}}
                """);
        assertTrue(notificationStarted.await(1, TimeUnit.SECONDS));
        writeFrame(serverOutput, """
                {"jsonrpc":"2.0","id":1,"result":{"value":42}}
                """);

        JsonNode result = response.get(1, TimeUnit.SECONDS);
        assertEquals(42, result.path("value").asInt());
        releaseNotification.countDown();
        client.close();
    }

    @Test
    void cancellingARequestNotifiesTheLanguageServer() throws Exception {
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        LspJsonRpcClient client = new LspJsonRpcClient(clientInput, clientOutput, "lsp-cancel-test");

        var request = client.request("textDocument/completion", Map.of());
        assertTrue(request.cancel(false));

        String frames = awaitOutput(clientOutput, "$/cancelRequest");
        assertTrue(frames.contains("\"method\":\"$/cancelRequest\""));
        assertTrue(frames.contains("\"params\":{\"id\":1}"));
        assertFalse(client.isClosed());
        client.close();
        serverOutput.close();
    }

    @Test
    void blockedServerPipeDoesNotBlockCallingThread() throws Exception {
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        OutputStream blockedOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                awaitRelease();
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                awaitRelease();
            }

            private void awaitRelease() throws IOException {
                writeStarted.countDown();
                try {
                    if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("test output remained blocked");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        };
        LspJsonRpcClient client = new LspJsonRpcClient(
                clientInput, blockedOutput, "lsp-blocked-output-test");

        long firstStarted = System.nanoTime();
        client.notify("textDocument/didOpen", Map.of("text", "class App {}"));
        long firstElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStarted);
        assertTrue(firstElapsedMs < 500, "a primeira notificacao bloqueou por " + firstElapsedMs + " ms");
        assertTrue(writeStarted.await(1, TimeUnit.SECONDS));

        long secondStarted = System.nanoTime();
        client.notify("textDocument/didChange", Map.of("text", "class App { int n; }"));
        long secondElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - secondStarted);
        assertTrue(secondElapsedMs < 500, "a EDT simulada bloqueou por " + secondElapsedMs + " ms");

        releaseWrite.countDown();
        client.close();
        serverOutput.close();
    }

    private static String awaitOutput(ByteArrayOutputStream output, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        String value;
        do {
            value = output.toString(StandardCharsets.UTF_8);
            if (value.contains(expected)) return value;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return value;
    }

    private static void writeFrame(PipedOutputStream output, String json) throws Exception {
        byte[] body = json.strip().getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
