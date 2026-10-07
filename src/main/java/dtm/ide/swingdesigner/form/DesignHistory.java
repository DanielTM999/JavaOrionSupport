package dtm.ide.swingdesigner.form;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

public final class DesignHistory {

    private static final int LIMIT = 100;

    public record Step(String label, String before, String after) {
    }

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    public synchronized void record(String label, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        undo.push(new Step(label, before, after));
        while (undo.size() > LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    public synchronized Optional<Step> undo(String current) {
        Step step = undo.peek();
        if (step == null) {
            return Optional.empty();
        }
        if (!Objects.equals(step.after(), current)) {
            clear();
            return Optional.empty();
        }
        undo.pop();
        redo.push(step);
        return Optional.of(step);
    }

    public synchronized Optional<Step> redo(String current) {
        Step step = redo.peek();
        if (step == null) {
            return Optional.empty();
        }
        if (!Objects.equals(step.before(), current)) {
            clear();
            return Optional.empty();
        }
        redo.pop();
        undo.push(step);
        return Optional.of(step);
    }

    public synchronized void restore(Step step, boolean wasUndo) {
        if (wasUndo) {
            redo.remove(step);
            undo.push(step);
        } else {
            undo.remove(step);
            redo.push(step);
        }
    }

    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    public synchronized Optional<String> nextUndoLabel() {
        return Optional.ofNullable(undo.peek()).map(Step::label);
    }

    public synchronized Optional<String> nextRedoLabel() {
        return Optional.ofNullable(redo.peek()).map(Step::label);
    }

    public synchronized void clear() {
        undo.clear();
        redo.clear();
    }
}
