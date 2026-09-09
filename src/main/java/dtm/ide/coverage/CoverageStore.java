package dtm.ide.coverage;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class CoverageStore {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile CoverageReport report = CoverageReport.EMPTY;

    public CoverageReport report() {
        return report;
    }

    public boolean hasData() {
        return !report.isEmpty();
    }

    public void set(CoverageReport value) {
        report = value == null ? CoverageReport.EMPTY : value;
        notifyListeners();
    }

    public void clear() {
        if (report.isEmpty()) {
            return;
        }
        report = CoverageReport.EMPTY;
        notifyListeners();
    }

    public Optional<FileCoverage> forFile(Path file) {
        return report.forFile(file);
    }

    public Optional<FileCoverage> forClass(String qualifiedName) {
        return report.forClass(qualifiedName);
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
