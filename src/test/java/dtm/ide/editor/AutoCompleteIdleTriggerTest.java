package dtm.ide.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCompleteIdleTriggerTest {

    private static final Path FILE = Path.of("Demo.java").toAbsolutePath();

    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private final AtomicReference<AutoCompleteIdleTrigger.Caret> caret = new AtomicReference<>();

    private AutoCompleteIdleTrigger trigger(long delayMs) {
        return new AutoCompleteIdleTrigger(delayMs,
                (task, delay) -> {
                    scheduled.add(task);
                    delays.add(delay);
                },
                path -> eligible.get(),
                ready::get,
                caret::get,
                requests::incrementAndGet);
    }

    private void runScheduled() {
        List<Runnable> pending = List.copyOf(scheduled);
        scheduled.clear();
        pending.forEach(Runnable::run);
    }

    @Test
    void firesOnceAfterTheUserStopsTyping() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 11));

        assertEquals(List.of(500L), delays);
        runScheduled();

        assertEquals(1, requests.get());
    }

    @Test
    void doesNotFireWhileTheUserKeepsTyping() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        idle.typed(FILE, 11, "y");
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 12));

        runScheduled();

        assertEquals(1, requests.get(), "apenas o ultimo agendamento pode disparar");
    }

    @Test
    void doesNotFireWhenTheCaretMovedAfterTyping() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 40));

        runScheduled();

        assertEquals(0, requests.get());
    }

    @Test
    void doesNotFireWhenTheEditorChanged() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        caret.set(new AutoCompleteIdleTrigger.Caret(Path.of("Other.java").toAbsolutePath(), 11));

        runScheduled();

        assertEquals(0, requests.get());
    }

    @Test
    void movingTheCaretWithoutTypingSchedulesNothing() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.cancel();
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 11));

        assertTrue(scheduled.isEmpty());
        assertEquals(0, requests.get());
    }

    @Test
    void deletingTextCancelsThePendingTrigger() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        idle.cancel();
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 11));

        runScheduled();

        assertEquals(0, requests.get());
    }

    @Test
    void pastedTextAndControlCharactersDoNotSchedule() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "System.out.println();");
        idle.typed(FILE, 10, ".");
        idle.typed(FILE, 10, "\n");
        idle.typed(FILE, 10, " ");

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void doesNotScheduleWhenTheFileIsNotEligible() {
        eligible.set(false);
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aZeroDelayTurnsTheIdleTriggerOff() {
        AutoCompleteIdleTrigger idle = trigger(0);
        idle.typed(FILE, 10, "s");

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void doesNotFireWhileThePopupIsAlreadyOpen() {
        AutoCompleteIdleTrigger idle = trigger(500);
        idle.typed(FILE, 10, "s");
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 11));
        ready.set(false);

        runScheduled();

        assertEquals(0, requests.get());
    }

    @Test
    void aTypingBurstProducesASingleRequest() {
        AutoCompleteIdleTrigger idle = trigger(500);
        for (int index = 0; index < 6; index++) {
            idle.typed(FILE, 10 + index, "a");
            runScheduled();
        }
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 16));
        idle.typed(FILE, 16, "a");
        caret.set(new AutoCompleteIdleTrigger.Caret(FILE, 17));
        runScheduled();

        assertEquals(1, requests.get());
    }
}
