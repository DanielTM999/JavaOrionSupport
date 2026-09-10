package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtDocumentStoreTest {

    @Test
    void ownsContentVersionsAndSynchronizationAsOneLifecycle() {
        JdtDocumentStore store = new JdtDocumentStore();

        assertEquals(null, store.put("file:///App.java", "class App {}"));
        assertEquals(1, store.version("file:///App.java"));
        assertTrue(store.markSynced("file:///App.java"));
        assertFalse(store.markSynced("file:///App.java"));

        assertEquals(2, store.incrementVersion("file:///App.java"));
        assertEquals("class App {}", store.content("file:///App.java"));

        store.remove("file:///App.java");
        assertEquals(JdtLsService.ANY_VERSION, store.version("file:///App.java"));
        assertFalse(store.isSynced("file:///App.java"));
    }

    @Test
    void iterationUsesTheCurrentDocumentSnapshot() {
        JdtDocumentStore store = new JdtDocumentStore();
        store.put("file:///A.java", "A");
        store.put("file:///B.java", "B");
        Map<String, String> visited = new LinkedHashMap<>();

        store.forEach(visited::put);

        assertEquals(Map.of("file:///A.java", "A", "file:///B.java", "B"), visited);
        assertEquals(2, store.uris().size());
    }
}
