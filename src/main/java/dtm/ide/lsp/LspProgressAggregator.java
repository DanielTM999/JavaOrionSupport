package dtm.ide.lsp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class LspProgressAggregator {

    static final int INITIALIZED_PERCENT = 10;
    static final int MAX_BEFORE_READY = 95;

    enum Phase {
        IMPORT(15, 60),
        BUILD(60, MAX_BEFORE_READY),
        OTHER(-1, -1);

        private final int from;
        private final int to;

        Phase(int from, int to) {
            this.from = from;
            this.to = to;
        }

        int at(double fraction) {
            return from + (int) Math.round((to - from) * Math.max(0, Math.min(1, fraction)));
        }
    }

    record Snapshot(String label, int percent, int workPercent, int active, boolean visibleWork) {

        boolean idle() {
            return active == 0;
        }
    }

    private static final class Task {
        private final String title;
        private final Phase phase;
        private String message;
        private int percent;
        private boolean done;
        private long touched;

        private Task(String title, Phase phase, long touched) {
            this.title = title;
            this.phase = phase;
            this.percent = -1;
            this.touched = touched;
        }
    }

    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private String status = "";
    private int floor;
    private long clock;

    synchronized void reset() {
        tasks.clear();
        status = "";
        floor = 0;
        clock = 0;
    }

    synchronized Snapshot initialized(String label) {
        status = label == null ? "" : label;
        floor = Math.max(floor, INITIALIZED_PERCENT);
        return snapshot();
    }

    synchronized Snapshot status(String message) {
        if (message != null && !message.isBlank()) {
            status = message;
        }
        return snapshot();
    }

    synchronized Snapshot begin(String token, String title, String message, int percent) {
        String name = title == null || title.isBlank() ? message : title;
        Task task = new Task(name == null ? "" : name,
                phaseOf((title == null ? "" : title) + " " + (message == null ? "" : message)),
                ++clock);
        tasks.put(token, task);
        update(task, message, percent);
        return snapshot();
    }

    synchronized Snapshot report(String token, String title, String message, int percent) {
        Task task = tasks.get(token);
        if (task == null) {
            return begin(token, title, message, percent);
        }
        update(task, message, percent);
        return snapshot();
    }

    synchronized Snapshot end(String token) {
        Task task = tasks.get(token);
        if (task != null) {
            task.done = true;
            task.percent = 100;
            task.touched = ++clock;
        }
        return snapshot();
    }

    synchronized Snapshot restartBackgroundWork() {
        tasks.values().removeIf(task -> task.done);
        floor = 0;
        return snapshot();
    }

    synchronized Snapshot snapshot() {
        int active = 0;
        int known = 0;
        int knownSum = 0;
        boolean visibleWork = false;
        Task dominant = null;
        Phase furthest = null;
        for (Task task : tasks.values()) {
            if (!task.done) {
                active++;
                visibleWork |= task.phase == Phase.IMPORT || task.phase == Phase.BUILD;
                if (task.percent >= 0) {
                    known++;
                    knownSum += task.percent;
                }
                if (dominant == null || task.touched > dominant.touched) {
                    dominant = task;
                }
            }
            if (task.phase != Phase.OTHER && (furthest == null || task.phase.ordinal() > furthest.ordinal())) {
                furthest = task.phase;
            }
        }
        int percent = floor;
        if (furthest != null) {
            double sum = 0;
            int count = 0;
            for (Task task : tasks.values()) {
                if (task.phase == furthest) {
                    sum += task.done ? 100 : Math.max(0, task.percent);
                    count++;
                }
            }
            percent = Math.max(percent, furthest.at(count == 0 ? 0 : sum / count / 100.0));
        }
        percent = Math.min(MAX_BEFORE_READY, percent);
        floor = percent;
        int workPercent = known == 0 ? -1 : knownSum / known;
        return new Snapshot(labelOf(dominant, active), percent, workPercent, active, visibleWork);
    }

    private String labelOf(Task dominant, int active) {
        if (dominant == null) {
            return status;
        }
        String title = dominant.title == null ? "" : dominant.title.strip();
        String message = dominant.message == null ? "" : dominant.message.strip();
        String label;
        if (title.isEmpty()) {
            label = message;
        } else if (message.isEmpty() || message.equalsIgnoreCase(title)) {
            label = title;
        } else {
            label = title + " - " + message;
        }
        if (dominant.percent >= 0 && dominant.percent < 100) {
            label += " (" + dominant.percent + "%)";
        }
        if (active > 1) {
            label += " +" + (active - 1);
        }
        return label.isBlank() ? status : label;
    }

    private void update(Task task, String message, int percent) {
        if (message != null && !message.isBlank()) {
            task.message = message;
        }
        if (percent >= 0) {
            task.percent = Math.max(task.percent, Math.min(100, percent));
        }
        task.touched = ++clock;
    }

    static Phase phaseOf(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (value.contains("publish") || value.contains("validat")
                || value.contains("diagnostic") || value.contains("reconcil")) {
            return Phase.OTHER;
        }
        if (value.contains("build") || value.contains("compil") || value.contains("index")
                || value.contains("refresh") || value.contains("search")) {
            return Phase.BUILD;
        }
        if (value.contains("import") || value.contains("synchroniz") || value.contains("maven")
                || value.contains("gradle") || value.contains("resolv") || value.contains("download")
                || value.contains("init") || value.contains("configur") || value.contains("classpath")) {
            return Phase.IMPORT;
        }
        return Phase.OTHER;
    }
}
