package dtm.ide.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WatchedFileBatch {

    static final int CREATED = 1;
    static final int CHANGED = 2;
    static final int DELETED = 3;

    record Entry(Path path, int changeType) {
    }

    private final int capacity;
    private final long maxDelayNanos;
    private final Map<Path, Entry> changes = new LinkedHashMap<>();

    private boolean overflowed;
    private boolean waiting;
    private long firstQueuedNanos;

    WatchedFileBatch(int capacity, long maxDelayNanos) {
        this.capacity = Math.max(1, capacity);
        this.maxDelayNanos = Math.max(0, maxDelayNanos);
    }

    synchronized void add(Path path, int changeType, long nowNanos) {
        add(path, path, changeType, nowNanos);
    }

    synchronized void add(Path key, Path path, int changeType, long nowNanos) {
        if (key == null || path == null) {
            return;
        }
        if (!waiting) {
            waiting = true;
            firstQueuedNanos = nowNanos;
        }
        Entry previous = changes.get(key);
        if (previous == null && changes.size() >= capacity) {
            overflowed = true;
            changes.clear();
            return;
        }
        if (overflowed) {
            return;
        }
        changes.put(key, new Entry(path, merge(previous == null ? null : previous.changeType(), changeType)));
    }

    private static int merge(Integer previous, int changeType) {
        if (previous == null) {
            return changeType;
        }
        if (changeType == DELETED || changeType == CREATED) {
            return changeType;
        }
        return previous == DELETED ? CREATED : previous;
    }

    synchronized boolean isPending() {
        return waiting;
    }

    synchronized boolean isOverflowed() {
        return overflowed;
    }

    synchronized long delayNanos(long nowNanos, long preferredDelayNanos) {
        if (!waiting) {
            return preferredDelayNanos;
        }
        long remaining = maxDelayNanos - (nowNanos - firstQueuedNanos);
        if (remaining <= 0) {
            return 0;
        }
        return Math.min(preferredDelayNanos, remaining);
    }

    synchronized List<Entry> drain() {
        List<Entry> drained = new ArrayList<>(changes.values());
        reset();
        return drained;
    }

    synchronized void clear() {
        reset();
    }

    private void reset() {
        changes.clear();
        overflowed = false;
        waiting = false;
        firstQueuedNanos = 0;
    }
}
