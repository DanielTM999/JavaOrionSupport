package dtm.ide.editor;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AutoCompleteIdleTrigger {

    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    public record Caret(Path file, int offset) {
    }

    private final long delayMs;
    private final Scheduler scheduler;
    private final Predicate<Path> eligible;
    private final BooleanSupplier ready;
    private final Supplier<Caret> currentCaret;
    private final Runnable request;
    private final AtomicLong ticket = new AtomicLong();

    public AutoCompleteIdleTrigger(long delayMs,
                                   Scheduler scheduler,
                                   Predicate<Path> eligible,
                                   BooleanSupplier ready,
                                   Supplier<Caret> currentCaret,
                                   Runnable request) {
        this.delayMs = delayMs;
        this.scheduler = scheduler;
        this.eligible = eligible;
        this.ready = ready;
        this.currentCaret = currentCaret;
        this.request = request;
    }

    public void cancel() {
        ticket.incrementAndGet();
    }

    public void typed(Path file, int offset, String inserted) {
        long id = ticket.incrementAndGet();
        if (delayMs <= 0 || file == null || offset < 0 || !isTypedCharacter(inserted)
                || !eligible.test(file)) {
            return;
        }
        Caret armed = new Caret(file, offset + inserted.length());
        scheduler.schedule(() -> fire(id, armed), delayMs);
    }

    public boolean fire(long id, Caret armed) {
        if (id != ticket.get() || !ready.getAsBoolean()) {
            return false;
        }
        Caret now = currentCaret.get();
        if (now == null || !now.equals(armed)) {
            return false;
        }
        request.run();
        return true;
    }

    static boolean isTypedCharacter(String inserted) {
        return inserted != null
                && inserted.length() == 1
                && Character.isJavaIdentifierPart(inserted.charAt(0));
    }
}
