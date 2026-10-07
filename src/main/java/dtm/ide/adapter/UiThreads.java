package dtm.ide.adapter;

import dtm.ide.navigation.JavaNavigation;
import dtm.stools.component.panels.editor.code.api.Location;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
public final class UiThreads {

    private UiThreads() {
    }

    public static <T> T onUi(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Falha ao executar acao na interface.", e);
            return null;
        }
        return result.get();
    }

    public static String locationKey(Location location) {
        return JavaNavigation.key(location);
    }
}
