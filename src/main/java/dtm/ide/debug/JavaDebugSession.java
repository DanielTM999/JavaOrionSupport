package dtm.ide.debug;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.project.editor.BreakpointIde;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public final class JavaDebugSession implements AutoCloseable, DebuggerCompletionSource {

    private static final long REQUEST_TIMEOUT_SECONDS = 10;
    private static final long INITIALIZE_TIMEOUT_SECONDS = 30;
    private static final long SETUP_TIMEOUT_SECONDS = 120;
    private static final long COMPLETION_TIMEOUT_MILLIS = 1_500;
    private static final int STACK_DEPTH = 50;

    public record BreakpointSpec(String condition, String hitCondition, String logMessage) {

        private static final BreakpointSpec PLAIN = new BreakpointSpec(null, null, null);

        public BreakpointSpec {
            condition = normalize(condition);
            hitCondition = positive(hitCondition);
            logMessage = normalize(logMessage);
        }

        public static BreakpointSpec plain() {
            return PLAIN;
        }

        public static BreakpointSpec ofCondition(String condition) {
            return new BreakpointSpec(condition, null, null);
        }

        public static BreakpointSpec of(BreakpointIde breakpoint) {
            if (breakpoint == null) {
                return PLAIN;
            }
            String condition = breakpoint.condition();
            try {
                return new BreakpointSpec(condition, breakpoint.hitCondition(), breakpoint.logMessage());
            } catch (LinkageError olderIde) {
                return ofCondition(condition);
            }
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static String positive(String value) {
            String trimmed = normalize(value);
            if (trimmed == null) {
                return null;
            }
            try {
                int count = Integer.parseInt(trimmed);
                return count > 0 ? String.valueOf(count) : null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
    }

    public record Completion(String label, String insertText, String type) {
    }

    private final int adapterPort;
    private final JavaAttachTarget attachTarget;
    private final Path projectRoot;
    private final Consumer<JavaDebugSnapshot> listener;
    private final Executor executor;
    private final Map<Path, Map<Integer, BreakpointSpec>> breakpoints = new ConcurrentHashMap<>();
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final AtomicBoolean configured = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile DapClient client;
    private volatile JavaDebugSnapshot snapshot = JavaDebugSnapshot.starting("Starting debugger...");
    private volatile int selectedFrameId;
    private volatile int selectedThreadId;
    private volatile boolean completionsSupported;
    private volatile String projectName;

    public JavaDebugSession(int adapterPort, int jdwpPort, Path projectRoot,
                            List<RunBreakpointData> initialBreakpoints,
                            Consumer<JavaDebugSnapshot> listener) {
        this(adapterPort, JavaAttachTarget.local(jdwpPort), projectRoot, initialBreakpoints,
                listener, command -> Thread.startVirtualThread(command));
    }

    public JavaDebugSession(int adapterPort, JavaAttachTarget attachTarget, Path projectRoot,
                            List<RunBreakpointData> initialBreakpoints,
                            Consumer<JavaDebugSnapshot> listener) {
        this(adapterPort, attachTarget, projectRoot, initialBreakpoints, listener,
                command -> Thread.startVirtualThread(command));
    }

    public JavaDebugSession(int adapterPort, JavaAttachTarget attachTarget, Path projectRoot,
                            List<RunBreakpointData> initialBreakpoints,
                            Consumer<JavaDebugSnapshot> listener, Executor executor) {
        this.adapterPort = adapterPort;
        this.attachTarget = attachTarget == null
                ? JavaAttachTarget.local(0) : attachTarget;
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.listener = listener == null ? value -> {
        } : listener;
        this.executor = executor == null
                ? command -> Thread.startVirtualThread(command) : executor;
        seedBreakpoints(initialBreakpoints);
    }

    private volatile boolean breakOnCaughtExceptions;

    static List<String> exceptionFilters(boolean caught) {
        return caught ? List.of("caught", "uncaught") : List.of("uncaught");
    }

    public JavaDebugSession breakOnCaughtExceptions(boolean value) {
        breakOnCaughtExceptions = value;
        return this;
    }

    public JavaDebugSession projectName(String name) {
        projectName = name == null || name.isBlank() ? null : name.trim();
        return this;
    }

    public void start() throws Exception {
        publish(JavaDebugSnapshot.starting("Connecting to Java debugger..."));
        DapClient connected = connectWithRetry();
        client = connected;
        connected.setEventListener(this::onEvent);
        JsonNode capabilities = connected.request("initialize", Map.of(
                "adapterID", "java",
                "clientID", "orion-java",
                "clientName", "Orion IDE",
                "linesStartAt1", true,
                "columnsStartAt1", true,
                "pathFormat", "path",
                "supportsVariableType", true,
                "supportsRunInTerminalRequest", false))
                .get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        completionsSupported = capabilities != null
                && capabilities.path("supportsCompletionsRequest").asBoolean(false);

        Map<String, Object> attach = new LinkedHashMap<>();
        attach.put("request", "attach");
        attach.put("hostName", attachTarget.host());
        attach.put("port", attachTarget.port());
        attach.put("timeout", attachTarget.timeoutMillis());
        String project = projectName;
        if (project != null && !project.isBlank()) {
            attach.put("projectName", project);
        }
        AtomicReference<Throwable> attachFailure = new AtomicReference<>();
        AtomicBoolean awaiting = new AtomicBoolean(true);
        connected.request("attach", attach).whenComplete((body, error) -> {
            if (error == null) {
                return;
            }
            attachFailure.set(error);
            initialized.countDown();
            if (!awaiting.get() && !closed.get()) {
                fail(error);
            }
        });

        long awaitSeconds = Math.max(5, attachTarget.timeoutMillis() / 1000);
        boolean ready = initialized.await(awaitSeconds, TimeUnit.SECONDS);
        awaiting.set(false);
        if (!ready) {
            throw new IllegalStateException("Java debugger did not initialize in time");
        }
        Throwable failure = attachFailure.get();
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
        configure();
    }

    public JavaAttachTarget attachTarget() {
        return attachTarget;
    }

    public JavaDebugSnapshot snapshot() {
        return snapshot;
    }

    public void continueExecution() {
        control("continue", Map.of("threadId", activeThreadId()));
    }

    public void pause() {
        int threadId = activeThreadId();
        if (threadId <= 0) {
            try {
                List<JavaDebugSnapshot.ThreadInfo> threads = parseThreads(
                        request("threads", Map.of()).path("threads"));
                threadId = threads.isEmpty() ? 0 : threads.getFirst().id();
                selectedThreadId = threadId;
            } catch (Exception error) {
                warn(error);
                return;
            }
        }
        control("pause", Map.of("threadId", threadId));
    }

    public void next() {
        control("next", Map.of("threadId", activeThreadId()));
    }

    public void stepIn() {
        control("stepIn", Map.of("threadId", activeThreadId()));
    }

    public void stepOut() {
        control("stepOut", Map.of("threadId", activeThreadId()));
    }

    public JavaDebugSnapshot.Variable evaluate(String expression, int frameId) throws Exception {
        int targetFrame = frameId > 0 ? frameId : selectedFrameId;
        JsonNode body = request("evaluate", Map.of(
                "expression", expression == null ? "" : expression,
                "frameId", targetFrame,
                "context", "watch"));
        return new JavaDebugSnapshot.Variable(expression, body.path("result").asText(""),
                body.path("type").asText(""), body.path("evaluateName").asText(expression),
                body.path("variablesReference").asInt(), body.path("namedVariables").asInt(),
                body.path("indexedVariables").asInt());
    }

    public List<JavaDebugSnapshot.Variable> variables(int reference) throws Exception {
        if (reference <= 0) {
            return List.of();
        }
        return parseVariables(request("variables", Map.of("variablesReference", reference))
                .path("variables"));
    }

    public List<JavaDebugSnapshot.Variable> variablesForFrame(int frameId) throws Exception {
        return loadFrameVariables(frameId);
    }

    public void selectThread(int threadId) {
        if (threadId <= 0) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<JavaDebugSnapshot.StackFrame> frames = parseFrames(request("stackTrace", Map.of(
                        "threadId", threadId, "startFrame", 0, "levels", STACK_DEPTH))
                        .path("stackFrames"));
                List<JavaDebugSnapshot.Scope> scopes = frames.isEmpty() ? List.of()
                        : loadFrameScopes(frames.getFirst().id());
                List<JavaDebugSnapshot.Variable> values = scopes.stream()
                        .flatMap(scope -> scope.variables().stream()).toList();
                selectedFrameId = frames.isEmpty() ? 0 : frames.getFirst().id();
                selectedThreadId = threadId;
                publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.PAUSED, "Paused", threadId,
                        snapshot.threads(), frames, values, scopes));
            } catch (Exception error) {
                warn(error);
            }
        }, executor);
    }

    public CompletableFuture<JsonNode> redefineClasses() {
        DapClient current = client;
        if (current == null || closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active debug session"));
        }
        return current.request("redefineClasses", Map.of());
    }

    public void updateBreakpoint(Path file, int line, boolean enabled, String condition) {
        updateBreakpoint(file, line, enabled, BreakpointSpec.ofCondition(condition));
    }

    public void updateBreakpoint(Path file, int line, boolean enabled, BreakpointSpec spec) {
        if (file == null || line < 0) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        Map<Integer, BreakpointSpec> lines = breakpoints.computeIfAbsent(normalized,
                ignored -> new ConcurrentHashMap<>());
        if (enabled) {
            lines.put(line, spec == null ? BreakpointSpec.plain() : spec);
        } else {
            lines.remove(line);
        }
        if (configured.get()) {
            sendBreakpoints(normalized, lines);
        }
    }

    private void configure() throws Exception {
        for (Map.Entry<Path, Map<Integer, BreakpointSpec>> entry : breakpoints.entrySet()) {
            sendBreakpoints(entry.getKey(), entry.getValue()).get(
                    SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        client.request("setExceptionBreakpoints", Map.of("filters", exceptionFilters(breakOnCaughtExceptions)))
                .get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        client.request("configurationDone", Map.of()).get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        configured.set(true);
        publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.RUNNING, "Running", 0,
                List.of(), List.of(), List.of()));
    }

    private CompletableFuture<JsonNode> sendBreakpoints(Path file, Map<Integer, BreakpointSpec> lines) {
        List<Map<String, Object>> values = new ArrayList<>();
        lines.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> breakpoint = new LinkedHashMap<>();
            breakpoint.put("line", entry.getKey() + 1);
            BreakpointSpec spec = entry.getValue();
            if (spec.condition() != null) {
                breakpoint.put("condition", spec.condition());
            }
            if (spec.hitCondition() != null) {
                breakpoint.put("hitCondition", spec.hitCondition());
            }
            if (spec.logMessage() != null) {
                breakpoint.put("logMessage", spec.logMessage());
            }
            values.add(breakpoint);
        });
        return client.request("setBreakpoints", Map.of(
                "source", Map.of("name", file.getFileName().toString(), "path", file.toString()),
                "breakpoints", values,
                "sourceModified", false));
    }

    private void control(String command, Map<String, Object> arguments) {
        DapClient current = client;
        if (current == null || closed.get()) {
            return;
        }
        JavaDebugSnapshot before = snapshot;
        boolean resumes = !"pause".equals(command);
        if (resumes) {
            publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.RUNNING, "Running", 0,
                    before.threads(), List.of(), List.of()));
        }
        current.request(command, arguments).whenComplete((body, error) -> {
            if (error == null) {
                return;
            }
            if (resumes && snapshot.state() == JavaDebugSnapshot.State.RUNNING) {
                String message = messageOf(error);
                log.warn("Comando {} do depurador Java falhou: {}", command, message);
                publish(new JavaDebugSnapshot(before.state(), message, before.threadId(),
                        before.threads(), before.frames(), before.variables(), before.scopes()));
            } else {
                warn(error);
            }
        });
    }

    private void onEvent(String name, JsonNode body) {
        switch (name) {
            case "initialized" -> initialized.countDown();
            case "stopped" -> loadStopped(body.path("threadId").asInt(),
                    "exception".equals(body.path("reason").asText()));
            case "continued" -> publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.RUNNING,
                    "Running", 0, snapshot.threads(), List.of(), List.of()));
            case "terminated", "exited" -> {
                if (snapshot.state() != JavaDebugSnapshot.State.ERROR) {
                    publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.TERMINATED,
                            "Debug session finished", 0, List.of(), List.of(), List.of()));
                }
            }
            case "output" -> listener.accept(new JavaDebugSnapshot(snapshot.state(),
                    body.path("output").asText(), snapshot.threadId(), snapshot.threads(),
                    snapshot.frames(), snapshot.variables(), snapshot.scopes()));
            case "connectionClosed" -> {
                if (!closed.get()) {
                    fail(new IllegalStateException(body.asText("DAP connection closed")));
                }
            }
            default -> {
            }
        }
    }

    private void loadStopped(int eventThreadId, boolean stoppedOnException) {
        CompletableFuture.runAsync(() -> {
            try {
                String reason = "Paused";
                List<JavaDebugSnapshot.ThreadInfo> threads = parseThreads(
                        request("threads", Map.of()).path("threads"));
                int threadId = eventThreadId > 0 ? eventThreadId
                        : threads.isEmpty() ? 0 : threads.getFirst().id();
                List<JavaDebugSnapshot.StackFrame> frames = threadId <= 0 ? List.of()
                        : parseFrames(request("stackTrace", Map.of(
                                "threadId", threadId, "startFrame", 0, "levels", STACK_DEPTH))
                                .path("stackFrames"));
                List<JavaDebugSnapshot.Scope> scopes = frames.isEmpty() ? List.of()
                        : loadFrameScopes(frames.getFirst().id());
                List<JavaDebugSnapshot.Variable> variables = scopes.stream()
                        .flatMap(scope -> scope.variables().stream()).toList();
                selectedFrameId = frames.isEmpty() ? 0 : frames.getFirst().id();
                selectedThreadId = threadId;
                if (stoppedOnException && threadId > 0) {
                    try {
                        JsonNode exception = request("exceptionInfo", Map.of("threadId", threadId));
                        String exceptionId = exception.path("exceptionId").asText("");
                        String description = exception.path("description").asText("");
                        if (!exceptionId.isBlank() || !description.isBlank()) {
                            reason = (exceptionId + (description.isBlank() ? "" : ": " + description)).trim();
                        }
                    } catch (Exception ignored) {
                    }
                }
                publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.PAUSED, reason, threadId,
                        threads, frames, variables, scopes));
            } catch (Exception error) {
                log.warn("Falha ao carregar o estado pausado do depurador Java", error);
                selectedThreadId = eventThreadId;
                publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.PAUSED, messageOf(error),
                        eventThreadId, List.of(), List.of(), List.of()));
            }
        }, executor);
    }

    private List<JavaDebugSnapshot.Variable> loadFrameVariables(int frameId) throws Exception {
        return loadFrameScopes(frameId).stream()
                .flatMap(scope -> scope.variables().stream())
                .toList();
    }

    public List<JavaDebugSnapshot.Scope> scopesForFrame(int frameId) throws Exception {
        int targetFrame = frameId > 0 ? frameId : selectedFrameId;
        selectedFrameId = targetFrame;
        return loadFrameScopes(targetFrame);
    }

    private List<JavaDebugSnapshot.Scope> loadFrameScopes(int frameId) throws Exception {
        JsonNode scopes = request("scopes", Map.of("frameId", frameId)).path("scopes");
        List<JavaDebugSnapshot.Scope> values = new ArrayList<>();
        for (JsonNode scope : scopes) {
            int reference = scope.path("variablesReference").asInt();
            values.add(new JavaDebugSnapshot.Scope(scope.path("name").asText("Scope"), reference,
                    variables(reference)));
        }
        return List.copyOf(values);
    }

    @Override
    public boolean isCompletionsSupported() {
        return completionsSupported;
    }

    @Override
    public Path pausedSource() {
        JavaDebugSnapshot current = snapshot;
        if (current.state() != JavaDebugSnapshot.State.PAUSED || current.frames() == null) {
            return null;
        }
        int frameId = selectedFrameId;
        return current.frames().stream()
                .filter(frame -> frameId <= 0 || frame.id() == frameId)
                .map(JavaDebugSnapshot.StackFrame::source)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Completion> completions(String text, int column) {
        DapClient current = client;
        int frameId = selectedFrameId;
        if (!completionsSupported || current == null || closed.get() || frameId <= 0 || text == null) {
            return List.of();
        }
        try {
            JsonNode body = current.request("completions", Map.of(
                            "frameId", frameId,
                            "text", text,
                            "column", Math.max(1, column)))
                    .get(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            List<Completion> values = new ArrayList<>();
            for (JsonNode target : body.path("targets")) {
                String label = target.path("label").asText("");
                if (label.isBlank()) {
                    continue;
                }
                values.add(new Completion(label, target.path("text").asText(label),
                        target.path("type").asText("")));
            }
            return List.copyOf(values);
        } catch (Exception error) {
            return List.of();
        }
    }

    private JsonNode request(String command, Object arguments) throws Exception {
        DapClient current = client;
        if (current == null) {
            throw new IllegalStateException("DAP client is not connected");
        }
        return current.request(command, arguments).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private int activeThreadId() {
        return snapshot.threadId() > 0 ? snapshot.threadId() : selectedThreadId;
    }

    private DapClient connectWithRetry() throws Exception {
        Exception last = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && !closed.get()) {
            try {
                return DapClient.connect("127.0.0.1", adapterPort, Duration.ofSeconds(2));
            } catch (Exception error) {
                last = error;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IllegalStateException("Java debug adapter unavailable") : last;
    }

    private void seedBreakpoints(List<RunBreakpointData> values) {
        if (values == null) {
            return;
        }
        for (RunBreakpointData data : values) {
            if (data == null || data.getFile() == null) {
                continue;
            }
            Map<Integer, BreakpointSpec> lines = new ConcurrentHashMap<>();
            if (data.getBreakpoints() != null && !data.getBreakpoints().isEmpty()) {
                for (BreakpointIde breakpoint : data.getBreakpoints()) {
                    if (breakpoint != null && breakpoint.active() && breakpoint.line() >= 0) {
                        lines.put(breakpoint.line(), BreakpointSpec.of(breakpoint));
                    }
                }
            } else if (data.getLines() != null) {
                data.getLines().stream().filter(line -> line != null && line >= 0)
                        .forEach(line -> lines.put(line, BreakpointSpec.plain()));
            }
            breakpoints.put(data.getFile().toAbsolutePath().normalize(), lines);
        }
    }

    private static List<JavaDebugSnapshot.ThreadInfo> parseThreads(JsonNode nodes) {
        List<JavaDebugSnapshot.ThreadInfo> values = new ArrayList<>();
        nodes.forEach(node -> values.add(new JavaDebugSnapshot.ThreadInfo(
                node.path("id").asInt(), node.path("name").asText("Thread"))));
        return List.copyOf(values);
    }

    private static List<JavaDebugSnapshot.StackFrame> parseFrames(JsonNode nodes) {
        List<JavaDebugSnapshot.StackFrame> values = new ArrayList<>();
        nodes.forEach(node -> {
            String raw = node.path("source").path("path").asText("");
            Path source = sourcePath(raw);
            values.add(new JavaDebugSnapshot.StackFrame(node.path("id").asInt(),
                    node.path("name").asText("Frame"), source,
                    source == null && raw.regionMatches(true, 0, "jdt://", 0, 6) ? raw : null,
                    Math.max(1, node.path("line").asInt(1))));
        });
        return List.copyOf(values);
    }

    static Path sourcePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.regionMatches(true, 0, "file:", 0, 5)) {
                return Path.of(URI.create(value));
            }
            if (value.matches("^[A-Za-z][A-Za-z0-9+.-]+:/.*")) {
                return null;
            }
            return Path.of(value);
        } catch (IllegalArgumentException | FileSystemNotFoundException invalid) {
            return null;
        }
    }

    private static List<JavaDebugSnapshot.Variable> parseVariables(JsonNode nodes) {
        List<JavaDebugSnapshot.Variable> values = new ArrayList<>();
        nodes.forEach(node -> values.add(new JavaDebugSnapshot.Variable(
                node.path("name").asText(""), node.path("value").asText(""),
                node.path("type").asText(""), node.path("evaluateName")
                        .asText(node.path("name").asText("")),
                node.path("variablesReference").asInt(), node.path("namedVariables").asInt(),
                node.path("indexedVariables").asInt())));
        return List.copyOf(values);
    }

    private void publish(JavaDebugSnapshot value) {
        snapshot = value;
        listener.accept(value);
    }

    private void fail(Throwable error) {
        String message = messageOf(error);
        log.warn("Sessao de debug Java falhou: {}", message);
        publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.ERROR, message, 0,
                snapshot.threads(), snapshot.frames(), snapshot.variables()));
    }

    private void warn(Throwable error) {
        String message = messageOf(error);
        log.warn("Requisicao ao depurador Java falhou: {}", message);
        JavaDebugSnapshot current = snapshot;
        publish(new JavaDebugSnapshot(current.state(), message, current.threadId(),
                current.threads(), current.frames(), current.variables(), current.scopes()));
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause == null || cause.getMessage() == null
                ? "Java debugger failed" : cause.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DapClient current = client;
        if (current != null) {
            try {
                // Uma JVM remota apenas perde o depurador; um processo iniciado pela IDE
                // e encerrado junto com a sessao.
                current.request("disconnect", Map.of(
                        "terminateDebuggee", attachTarget.terminateOnDisconnect(),
                        "restart", false)).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            current.close();
        }
        publish(new JavaDebugSnapshot(JavaDebugSnapshot.State.TERMINATED,
                "Debug session finished", 0, List.of(), List.of(), List.of()));
    }
}
