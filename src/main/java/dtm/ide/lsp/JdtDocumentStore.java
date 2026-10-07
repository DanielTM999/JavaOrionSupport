package dtm.ide.lsp;

import dtm.ide.lsp.api.JavaLanguageServer;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class JdtDocumentStore {

    private final Map<String, AtomicInteger> versions = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final Set<String> synced = ConcurrentHashMap.newKeySet();
    private final Map<String, Deque<SnapshotId>> history = new ConcurrentHashMap<>();
    private static final int HISTORY_LIMIT = 16;

    private record SnapshotId(int length, int hash) {}

    String put(String uri, String content) {
        remember(uri, content);
        versions.computeIfAbsent(uri, ignored -> new AtomicInteger(1));
        return documents.put(uri, content);
    }

    String content(String uri) {
        return documents.get(uri);
    }

    void remove(String uri) {
        history.remove(uri);
        documents.remove(uri);
        versions.remove(uri);
        synced.remove(uri);
    }

    int version(String uri) {
        AtomicInteger version = versions.get(uri);
        return version == null ? JavaLanguageServer.ANY_VERSION : version.get();
    }

    int ensureVersion(String uri) {
        return versions.computeIfAbsent(uri, ignored -> new AtomicInteger(1)).get();
    }

    int incrementVersion(String uri) {
        return versions.computeIfAbsent(uri, ignored -> new AtomicInteger()).incrementAndGet();
    }

    boolean markSynced(String uri) {
        return synced.add(uri);
    }

    boolean unmarkSynced(String uri) {
        return synced.remove(uri);
    }

    boolean hasSeen(String uri, String content) {
        Deque<SnapshotId> previous = history.get(uri);
        if (previous == null) return false;
        synchronized (previous) {
            return previous.contains(snapshotId(content));
        }
    }

    private void remember(String uri, String content) {
        Deque<SnapshotId> previous = history.computeIfAbsent(uri, ignored -> new ArrayDeque<>());
        SnapshotId snapshot = snapshotId(content);
        synchronized (previous) {
            if (!previous.isEmpty() && previous.getLast().equals(snapshot)) {
                return;
            }
            previous.addLast(snapshot);
            while (previous.size() > HISTORY_LIMIT) {
                previous.removeFirst();
            }
        }
    }

    private static SnapshotId snapshotId(String content) {
        return new SnapshotId(content == null ? 0 : content.length(), content == null ? 0 : content.hashCode());
    }

    boolean isSynced(String uri) {
        return synced.contains(uri);
    }

    Set<String> uris() {
        return Set.copyOf(documents.keySet());
    }

    void forEach(BiConsumer<String, String> consumer) {
        documents.forEach(consumer);
    }

    int size() {
        return documents.size();
    }

    void clearSyncState() {
        synced.clear();
    }

    void clear() {
        documents.clear();
        versions.clear();
        history.clear();
        synced.clear();
    }
}
