package dtm.ide.lsp;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class LspWatchedFiles {
    private static final long EXTERNAL_RESYNC_DELAY_MS = 350;
    private static final long EXTERNAL_RESYNC_MAX_DELAY_MS = 2_000;
    private static final int MAX_PENDING_WATCHED_FILES = 50_000;
    private static final int WATCHED_FILES_PER_NOTIFICATION = 512;

    interface Host {
        LspJsonRpcClient client();
        boolean canSyncDocuments();
        void invalidateWorkspaceNavigation();
        void forgetSymbols(String uri);
        void discardCodeLenses(String uri);
        void forgetCompletion(String uri);
        void resynchronizeOpenDocuments();
    }

    private final ExecutorService executor;
    private final JdtDocumentStore documents;
    private final Host host;
    private final AtomicLong watchedFlushTicket = new AtomicLong();
    private final AtomicLong resyncTicket = new AtomicLong();
    private final WatchedFileBatch watchedFiles = new WatchedFileBatch(
            MAX_PENDING_WATCHED_FILES,
            TimeUnit.MILLISECONDS.toNanos(EXTERNAL_RESYNC_MAX_DELAY_MS));
    private volatile long externalResyncDelayMs = EXTERNAL_RESYNC_DELAY_MS;

    LspWatchedFiles(ExecutorService executor, JdtDocumentStore documents, Host host) {
        this.executor = executor;
        this.documents = documents;
        this.host = host;
    }

    void queue(Path path, int changeType) {
        if (path == null) {
            return;
        }
        Path target = normalizePath(path);
        String uri = LspConversions.toUri(target);
        host.forgetSymbols(uri);
        host.discardCodeLenses(uri);
        host.forgetCompletion(uri);
        host.invalidateWorkspaceNavigation();

        watchedFiles.add(target, changeType, System.nanoTime());
        if (host.canSyncDocuments()) {
            scheduleWatchedFlush();
        }
    }

    private void scheduleWatchedFlush() {
        long ticket = watchedFlushTicket.incrementAndGet();
        long delay = TimeUnit.NANOSECONDS.toMillis(watchedFiles.delayNanos(System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(externalResyncDelayMs)));
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor)
                .execute(() -> flushWatchedFiles(ticket));
    }

    private void flushWatchedFiles(long ticket) {
        if (ticket != watchedFlushTicket.get()) {
            return;
        }
        drainWatchedFiles();
    }

    private void drainWatchedFiles() {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || rpc.isClosed() || !host.canSyncDocuments()) {
            return;
        }
        if (!watchedFiles.isPending()) {
            return;
        }
        boolean overflowed = watchedFiles.isOverflowed();
        List<WatchedFileBatch.Entry> entries = watchedFiles.drain();
        if (!overflowed) {
            List<Map<String, Object>> changes = new ArrayList<>(entries.size());
            for (WatchedFileBatch.Entry entry : entries) {
                String uri = LspConversions.toUri(entry.path());
                if (entry.changeType() != WatchedFileBatch.DELETED && documents.isSynced(uri)) {
                    continue;
                }
                changes.add(Map.of("uri", uri, "type", entry.changeType()));
            }
            for (int from = 0; from < changes.size(); from += WATCHED_FILES_PER_NOTIFICATION) {
                List<Map<String, Object>> chunk = changes.subList(from,
                        Math.min(changes.size(), from + WATCHED_FILES_PER_NOTIFICATION));
                rpc.notify("workspace/didChangeWatchedFiles", Map.of("changes", List.copyOf(chunk)));
            }
        } else {
            log.debug("Lote de mudancas externas estourou; ressincronizando tudo");
        }
        scheduleExternalResync();
    }

    void scheduleExternalResync() {
        if (!host.canSyncDocuments()) {
            return;
        }
        long ticket = resyncTicket.incrementAndGet();
        CompletableFuture.delayedExecutor(externalResyncDelayMs, TimeUnit.MILLISECONDS, executor)
                .execute(() -> runExternalResync(ticket));
    }

    private void runExternalResync(long ticket) {
        if (ticket != resyncTicket.get() || !host.canSyncDocuments()) {
            return;
        }
        host.resynchronizeOpenDocuments();
    }

    void setExternalResyncDelayMs(long delayMs) {
        externalResyncDelayMs = Math.max(0, delayMs);
    }

    void drainPending() {
        if (!watchedFiles.isPending()) {
            return;
        }
        drainWatchedFiles();
    }

    void reset() {
        watchedFiles.clear();
        watchedFlushTicket.incrementAndGet();
        resyncTicket.incrementAndGet();
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
