package dtm.ide.lsp;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class JdtDocumentStore {

    private final Map<String, AtomicInteger> versions = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final Set<String> synced = ConcurrentHashMap.newKeySet();

    String put(String uri, String content) {
        versions.computeIfAbsent(uri, ignored -> new AtomicInteger(1));
        return documents.put(uri, content);
    }

    String content(String uri) {
        return documents.get(uri);
    }

    void remove(String uri) {
        documents.remove(uri);
        versions.remove(uri);
        synced.remove(uri);
    }

    int version(String uri) {
        AtomicInteger version = versions.get(uri);
        return version == null ? JdtLsService.ANY_VERSION : version.get();
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
        synced.clear();
    }
}
