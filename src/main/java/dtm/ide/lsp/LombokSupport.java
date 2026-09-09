package dtm.ide.lsp;

import java.util.concurrent.atomic.AtomicReference;

public final class LombokSupport {

    public interface Listener {
        void onLombokStatusChanged(LombokSupportStatus status, String detail);
    }

    private final AtomicReference<LombokSupportStatus> status =
            new AtomicReference<>(LombokSupportStatus.NOT_USED);
    private volatile String detail = "";
    private volatile Listener listener;

    public LombokSupport() {
        this(null);
    }

    public LombokSupport(Listener listener) {
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public LombokSupportStatus status() {
        return status.get();
    }

    public String detail() {
        return detail;
    }

    public boolean isActive() {
        return status.get() == LombokSupportStatus.ACTIVE;
    }

    public boolean update(LombokSupportStatus next, String detail) {
        LombokSupportStatus target = next == null ? LombokSupportStatus.NOT_USED : next;
        String description = detail == null ? "" : detail;
        LombokSupportStatus previous = status.getAndSet(target);
        boolean changed = previous != target || !description.equals(this.detail);
        this.detail = description;
        Listener current = listener;
        if (changed && current != null) {
            current.onLombokStatusChanged(target, description);
        }
        return changed;
    }
}
