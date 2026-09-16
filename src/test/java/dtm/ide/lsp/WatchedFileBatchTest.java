package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchedFileBatchTest {

    private static final Path A = Path.of("/projeto/src/A.java");
    private static final Path B = Path.of("/projeto/src/B.java");

    @Test
    void aCreationSurvivesTheModificationsThatFollowIt() {
        WatchedFileBatch batch = new WatchedFileBatch(16, TimeUnit.SECONDS.toNanos(2));
        batch.add(A, WatchedFileBatch.CREATED, 0);
        batch.add(A, WatchedFileBatch.CHANGED, 1);
        batch.add(A, WatchedFileBatch.CHANGED, 2);

        List<WatchedFileBatch.Entry> drained = batch.drain();

        assertEquals(1, drained.size());
        assertEquals(WatchedFileBatch.CREATED, drained.getFirst().changeType());
    }

    @Test
    void aDeletionOverridesWhatWasQueuedBefore() {
        WatchedFileBatch batch = new WatchedFileBatch(16, TimeUnit.SECONDS.toNanos(2));
        batch.add(A, WatchedFileBatch.CREATED, 0);
        batch.add(A, WatchedFileBatch.DELETED, 1);

        assertEquals(WatchedFileBatch.DELETED, batch.drain().getFirst().changeType());
    }

    @Test
    void aFileRecreatedAfterADeletionIsReportedAsCreated() {
        WatchedFileBatch batch = new WatchedFileBatch(16, TimeUnit.SECONDS.toNanos(2));
        batch.add(A, WatchedFileBatch.DELETED, 0);
        batch.add(A, WatchedFileBatch.CHANGED, 1);

        assertEquals(WatchedFileBatch.CREATED, batch.drain().getFirst().changeType());
    }

    @Test
    void keepsTheOrderInWhichThePathsArrived() {
        WatchedFileBatch batch = new WatchedFileBatch(16, TimeUnit.SECONDS.toNanos(2));
        batch.add(B, WatchedFileBatch.CHANGED, 0);
        batch.add(A, WatchedFileBatch.CHANGED, 1);

        assertEquals(List.of(B, A), batch.drain().stream().map(WatchedFileBatch.Entry::path).toList());
    }

    @Test
    void overflowsIntoAFullResynchronizationWhenTooManyFilesChange() {
        WatchedFileBatch batch = new WatchedFileBatch(2, TimeUnit.SECONDS.toNanos(2));
        batch.add(A, WatchedFileBatch.CHANGED, 0);
        batch.add(B, WatchedFileBatch.CHANGED, 1);
        batch.add(Path.of("/projeto/src/C.java"), WatchedFileBatch.CHANGED, 2);

        assertTrue(batch.isOverflowed());
        assertTrue(batch.isPending());
        assertTrue(batch.drain().isEmpty());
        assertFalse(batch.isOverflowed());
    }

    @Test
    void aContinuousStreamOfEventsStillFlushesAtTheCeiling() {
        long ceiling = TimeUnit.MILLISECONDS.toNanos(2_000);
        long preferred = TimeUnit.MILLISECONDS.toNanos(350);
        WatchedFileBatch batch = new WatchedFileBatch(16, ceiling);
        batch.add(A, WatchedFileBatch.CHANGED, 0);

        assertEquals(preferred, batch.delayNanos(0, preferred));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(100),
                batch.delayNanos(TimeUnit.MILLISECONDS.toNanos(1_900), preferred));
        assertEquals(0, batch.delayNanos(TimeUnit.MILLISECONDS.toNanos(2_500), preferred));
    }

    @Test
    void anEmptyBatchIsNotPending() {
        WatchedFileBatch batch = new WatchedFileBatch(16, TimeUnit.SECONDS.toNanos(2));

        assertFalse(batch.isPending());
        assertTrue(batch.drain().isEmpty());
    }
}
