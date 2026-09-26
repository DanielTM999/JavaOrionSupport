package dtm.ide.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.project.editor.BreakpointIde;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDebugSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void configuresConditionalBreakpointsBeforeReleasingTheJvm() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<List<JsonNode>> captured = new CompletableFuture<>();
            Thread adapter = new Thread(() -> serve(server, captured));
            adapter.setDaemon(true);
            adapter.start();
            Path source = root.resolve("Demo.java");
            RunBreakpointData breakpoint = RunBreakpointData.builder()
                    .file(source)
                    .breakpoints(List.of(new BreakpointIde(6, true, "value > 3")))
                    .build();
            JavaDebugSession session = new JavaDebugSession(server.getLocalPort(), 51001,
                    root, List.of(breakpoint), value -> {
                    });

            session.start();
            List<JsonNode> requests = captured.get(2, TimeUnit.SECONDS);

            assertEquals(List.of("initialize", "attach", "setBreakpoints",
                            "setExceptionBreakpoints", "configurationDone"),
                    requests.stream().map(node -> node.path("command").asText()).toList());
            JsonNode sent = requests.get(2).path("arguments").path("breakpoints").get(0);
            assertEquals(7, sent.path("line").asInt());
            assertEquals("value > 3", sent.path("condition").asText());
            assertTrue(session.snapshot().state() == JavaDebugSnapshot.State.RUNNING);
            session.close();
        }
    }

    @Test
    void sendsHitCountsAndLogMessagesAndDropsInvalidHitCounts() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<List<JsonNode>> captured = new CompletableFuture<>();
            Thread adapter = new Thread(() -> serve(server, captured));
            adapter.setDaemon(true);
            adapter.start();
            RunBreakpointData breakpoint = RunBreakpointData.builder()
                    .file(root.resolve("Demo.java"))
                    .breakpoints(List.of(
                            new BreakpointIde(6, true, "value > 3", "3", "value={value}"),
                            new BreakpointIde(9, true, null, "abc", null)))
                    .build();
            JavaDebugSession session = new JavaDebugSession(server.getLocalPort(), 51003,
                    root, List.of(breakpoint), value -> {
                    });

            session.start();
            JsonNode sent = captured.get(2, TimeUnit.SECONDS).get(2).path("arguments").path("breakpoints");

            assertEquals("value > 3", sent.get(0).path("condition").asText());
            assertEquals("3", sent.get(0).path("hitCondition").asText());
            assertEquals("value={value}", sent.get(0).path("logMessage").asText());
            assertEquals(10, sent.get(1).path("line").asInt());
            assertTrue(sent.get(1).path("hitCondition").isMissingNode());
            assertTrue(sent.get(1).path("condition").isMissingNode());
            session.close();
        }
    }

    @Test
    void completionsAskTheDebuggerForThePausedFrame() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Path source = root.resolve("Demo.java");
            CompletableFuture<JsonNode> captured = new CompletableFuture<>();
            Thread adapter = new Thread(() -> servePausedWithCompletions(server, source, captured));
            adapter.setDaemon(true);
            adapter.start();
            JavaDebugSession session = new JavaDebugSession(server.getLocalPort(), 51004,
                    root, List.of(), value -> {
                    });

            session.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (session.snapshot().state() != JavaDebugSnapshot.State.PAUSED
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertTrue(session.isCompletionsSupported());
            assertEquals(source, session.pausedSource());
            List<JavaDebugSession.Completion> completions = session.completions("user.get", 9);
            JsonNode request = captured.get(2, TimeUnit.SECONDS);

            assertEquals(11, request.path("arguments").path("frameId").asInt());
            assertEquals("user.get", request.path("arguments").path("text").asText());
            assertEquals(9, request.path("arguments").path("column").asInt());
            assertEquals(List.of(new JavaDebugSession.Completion("getName", "getName()", "method")),
                    completions);
            session.close();
        }
    }

    @Test
    void pauseResolvesAnActiveThreadBeforeSendingTheCommand() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<JsonNode> captured = new CompletableFuture<>();
            Thread adapter = new Thread(() -> servePause(server, captured));
            adapter.setDaemon(true);
            adapter.start();
            JavaDebugSession session = new JavaDebugSession(server.getLocalPort(), 51002,
                    root, List.of(), value -> {
                    });

            session.start();
            session.pause();
            JsonNode pause = captured.get(2, TimeUnit.SECONDS);

            assertEquals("pause", pause.path("command").asText());
            assertEquals(77, pause.path("arguments").path("threadId").asInt());
            session.close();
        }
    }

    private static void serve(ServerSocket server, CompletableFuture<List<JsonNode>> captured) {
        List<JsonNode> requests = new ArrayList<>();
        try (Socket socket = server.accept()) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            for (int index = 0; index < 6; index++) {
                JsonNode request = read(input);
                requests.add(request);
                write(output, """
                        {"seq":%d,"type":"response","request_seq":%d,"command":"%s","success":true,"body":{}}
                        """.formatted(index + 1, request.path("seq").asInt(),
                        request.path("command").asText()));
                if ("attach".equals(request.path("command").asText())) {
                    write(output, """
                            {"seq":20,"type":"event","event":"initialized","body":{}}
                            """);
                }
                if ("configurationDone".equals(request.path("command").asText())) {
                    captured.complete(List.copyOf(requests));
                }
            }
        } catch (Exception error) {
            captured.completeExceptionally(error);
        }
    }

    private static void servePause(ServerSocket server, CompletableFuture<JsonNode> captured) {
        try (Socket socket = server.accept()) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            int sequence = 1;
            while (true) {
                JsonNode request = read(input);
                String command = request.path("command").asText();
                String body = "threads".equals(command)
                        ? "{\"threads\":[{\"id\":77,\"name\":\"main\"}]}" : "{}";
                write(output, """
                        {"seq":%d,"type":"response","request_seq":%d,"command":"%s","success":true,"body":%s}
                        """.formatted(sequence++, request.path("seq").asInt(), command, body));
                if ("attach".equals(command)) {
                    write(output, """
                            {"seq":50,"type":"event","event":"initialized","body":{}}
                            """);
                }
                if ("pause".equals(command)) {
                    captured.complete(request);
                    return;
                }
            }
        } catch (Exception error) {
            captured.completeExceptionally(error);
        }
    }

    private static void servePausedWithCompletions(ServerSocket server, Path source,
                                                   CompletableFuture<JsonNode> captured) {
        try (Socket socket = server.accept()) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            int sequence = 1;
            while (true) {
                JsonNode request = read(input);
                String command = request.path("command").asText();
                String body = switch (command) {
                    case "initialize" -> "{\"supportsCompletionsRequest\":true}";
                    case "threads" -> "{\"threads\":[{\"id\":1,\"name\":\"main\"}]}";
                    case "stackTrace" -> "{\"stackFrames\":[{\"id\":11,\"name\":\"run\",\"line\":5,"
                            + "\"source\":{\"path\":" + JSON.writeValueAsString(source.toString()) + "}}]}";
                    case "scopes" -> "{\"scopes\":[]}";
                    case "completions" -> "{\"targets\":[{\"label\":\"getName\",\"text\":\"getName()\","
                            + "\"type\":\"method\"}]}";
                    default -> "{}";
                };
                write(output, """
                        {"seq":%d,"type":"response","request_seq":%d,"command":"%s","success":true,"body":%s}
                        """.formatted(sequence++, request.path("seq").asInt(), command, body));
                if ("attach".equals(command)) {
                    write(output, """
                            {"seq":90,"type":"event","event":"initialized","body":{}}
                            """);
                }
                if ("configurationDone".equals(command)) {
                    write(output, """
                            {"seq":91,"type":"event","event":"stopped","body":{"reason":"breakpoint","threadId":1}}
                            """);
                }
                if ("completions".equals(command)) {
                    captured.complete(request);
                    return;
                }
            }
        } catch (Exception error) {
            captured.completeExceptionally(error);
        }
    }

    private static JsonNode read(BufferedInputStream input) throws Exception {
        int length = -1;
        while (true) {
            String line = readLine(input);
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return JSON.readTree(input.readNBytes(length));
    }

    private static String readLine(BufferedInputStream input) throws Exception {
        StringBuilder value = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new java.io.EOFException();
            if (previous == '\r' && current == '\n') {
                value.setLength(Math.max(0, value.length() - 1));
                return value.toString();
            }
            value.append((char) current);
            previous = current;
        }
    }

    private static void write(BufferedOutputStream output, String json) throws Exception {
        byte[] body = json.strip().getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
