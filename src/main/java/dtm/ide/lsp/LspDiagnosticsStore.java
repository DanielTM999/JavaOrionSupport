package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.inspection.DiagnosticRanges;
import dtm.ide.inspection.JavaDiagnosticEdits;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
final class LspDiagnosticsStore {
    private static final long SETTLE_QUIET_MS = 2_000;
    private static final long SETTLE_TIMEOUT_MS = 90_000;

    interface Host {
        boolean isReady();
        boolean progressIdle();
        void resynchronizeAfterSettle();
    }

    private final JdtDocumentStore documents;
    private final Consumer<Path> onPublished;
    private final ExecutorService executor;
    private final Object lock;
    private final Host host;
    private final Map<Path, List<Diagnostic>> byPath = new ConcurrentHashMap<>();
    private final Map<Path, List<JsonNode>> rawByPath = new ConcurrentHashMap<>();
    private final AtomicLong settleTicket = new AtomicLong();
    private volatile boolean settled = true;
    private volatile long settleDeadline;

    LspDiagnosticsStore(JdtDocumentStore documents, Consumer<Path> onPublished,
                        ExecutorService executor, Object lock, Host host) {
        this.documents = documents;
        this.onPublished = onPublished;
        this.executor = executor;
        this.lock = lock;
        this.host = host;
    }

    void beginImport() {
        settled = false;
        settleTicket.incrementAndGet();
    }

    void startSettleTimer() {
        settleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MS);
        scheduleSettle();
    }

    void reset() {
        settleTicket.incrementAndGet();
        Set<Path> diagnosed = new java.util.LinkedHashSet<>(byPath.keySet());
        byPath.clear();
        rawByPath.clear();
        diagnosed.forEach(onPublished);
    }

    void publish(JsonNode params) {
        if (params == null) {
            return;
        }
        String uri = params.path("uri").asText("");
        if (uri.isBlank()) {
            return;
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<JsonNode> rawDiagnostics = new ArrayList<>();
        JsonNode array = params.get("diagnostics");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                rawDiagnostics.add(node.deepCopy());
                Diagnostic diagnostic = LspConversions.diagnostic(node);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
        }
        Path changed = record(uri, params.get("version"), diagnostics, rawDiagnostics);
        if (changed != null) {
            onPublished.accept(changed);
        }
    }

    private Path record(String uri, JsonNode publishedVersion, List<Diagnostic> diagnostics,
                        List<JsonNode> rawDiagnostics) {
        synchronized (lock) {
            int currentVersion = documents.version(uri);
            if (publishedVersion != null && publishedVersion.isIntegralNumber()
                    && currentVersion != JavaLanguageServer.ANY_VERSION
                    && publishedVersion.asInt() != currentVersion) {
                log.debug("Diagnosticos descartados para {}: versao {} recebida, {} atual",
                        uri, publishedVersion.asInt(), currentVersion);
                return null;
            }
            Path path = LspConversions.toPath(uri);
            if (path == null) {
                return null;
            }
            Path key = normalizePath(path);
            String content = documents.content(uri);
            List<Diagnostic> latest = content == null
                    ? List.copyOf(diagnostics)
                    : DiagnosticRanges.compactMultiline(diagnostics, content);
            List<Diagnostic> previous = byPath.put(key, latest);
            rawByPath.put(key, List.copyOf(rawDiagnostics));
            return settled && !latest.equals(previous) ? path : null;
        }
    }

    boolean isSettled() {
        return settled;
    }

    void scheduleSettle() {
        if (settled || !host.isReady()) {
            return;
        }
        long ticket = settleTicket.incrementAndGet();
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(settleDeadline - System.nanoTime());
        long delay = Math.max(0, Math.min(SETTLE_QUIET_MS, remainingMs));
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor)
                .execute(() -> trySettle(ticket));
    }

    private void trySettle(long ticket) {
        if (ticket != settleTicket.get() || settled || !host.isReady()) {
            return;
        }
        boolean expired = System.nanoTime() - settleDeadline >= 0;
        if (!expired && !host.progressIdle()) {
            scheduleSettle();
            return;
        }
        settle();
    }

    void settle() {
        Set<Path> affected = new java.util.LinkedHashSet<>();
        synchronized (lock) {
            if (settled) {
                return;
            }
            documents.uris().forEach(uri -> {
                Path path = LspConversions.toPath(uri);
                if (path != null) {
                    byPath.remove(normalizePath(path));
                    rawByPath.remove(normalizePath(path));
                    affected.add(path);
                }
            });
            affected.addAll(byPath.keySet());
            settled = true;
        }
        host.resynchronizeAfterSettle();
        affected.forEach(onPublished);
    }

    List<JsonNode> raw(Path path) {
        return rawByPath.getOrDefault(normalizePath(path), List.of());
    }

    Collection<Diagnostic> diagnostics(Path path) {
        if (path == null || !settled) {
            return List.of();
        }
        return byPath.getOrDefault(normalizePath(path), List.of());
    }

    void clear() {
        Set<Path> affected = new java.util.LinkedHashSet<>(byPath.keySet());
        affected.addAll(rawByPath.keySet());
        byPath.clear();
        rawByPath.clear();
        affected.forEach(onPublished);
    }

    void removeAndPublish(Path path) {
        byPath.remove(normalizePath(path));
        rawByPath.remove(normalizePath(path));
        onPublished.accept(path);
    }

    void removeBelow(Path deleted) {
        List<Path> diagnosed = byPath.keySet().stream().filter(path -> path.startsWith(deleted)).toList();
        diagnosed.forEach(this::removeAndPublish);
    }

    void retainAfterEdit(Path path, String previous, String content) {
        Path key = normalizePath(path);
        List<Diagnostic> oldDiagnostics = byPath.get(key);
        List<Diagnostic> nextDiagnostics = oldDiagnostics != null
                ? JavaDiagnosticEdits.retainUnaffected(oldDiagnostics, previous, content) : null;
        if (nextDiagnostics == null) {
            byPath.remove(key);
        } else {
            byPath.put(key, nextDiagnostics);
        }
        rawByPath.remove(key);
        if (oldDiagnostics != null && !Objects.equals(oldDiagnostics, nextDiagnostics)) {
            onPublished.accept(path);
        }
    }

    Diagnostic diagnosticAt(Path path, int line, int col) {
        Diagnostic first = null;
        for (Diagnostic diagnostic : diagnostics(path)) {
            if (!contains(diagnostic, line, col)) {
                continue;
            }
            if (diagnostic.severity()
                    == dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.ERROR) {
                return diagnostic;
            }
            if (first == null) {
                first = diagnostic;
            }
        }
        return first;
    }

    HoverInfo diagnosticHover(Path path, int line, int col) {
        Diagnostic diagnostic = diagnosticAt(path, line, col);
        if (diagnostic == null || diagnostic.message() == null || diagnostic.message().isBlank()) {
            return null;
        }
        return new HoverInfo(diagnostic.message(), true,
                diagnostic.startLine(), diagnostic.startCol(),
                diagnostic.endLine(), diagnostic.endCol());
    }

    private static boolean contains(Diagnostic diagnostic, int line, int col) {
        if (diagnostic == null || line < diagnostic.startLine() || line > diagnostic.endLine()) {
            return false;
        }
        if (line == diagnostic.startLine() && col < diagnostic.startCol()) {
            return false;
        }
        return line != diagnostic.endLine() || col <= diagnostic.endCol();
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return Path.of(normalized.toString().toLowerCase(java.util.Locale.ROOT));
        }
        return normalized;
    }
}
