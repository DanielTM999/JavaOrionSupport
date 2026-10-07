package dtm.ide.adapter;

import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.todo.TodoItem;
import dtm.ide.todo.TodoScanner;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaTodoPanel;

import javax.swing.Icon;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TodoPanelHost implements JavaTodoPanel.Host {
    private static final long TODO_DEBOUNCE_MS = 400;

    public interface AdapterHost {
        Path projectRoot();
        JavaProjectDescriptor descriptor();
        List<String> todoMarkers();
        PluginTaskExecutor background();
        String registerPanel(JavaTodoPanel panel, Icon icon);
        void requestOpenToolPanel(String panelId);
        void openAt(Path file, int line, int column);
    }

    private final AdapterHost host;
    private final TodoScanner todoScanner = new TodoScanner();
    private final AtomicLong todoRefreshTicket = new AtomicLong();
    private volatile JavaTodoPanel todoPanel;
    private volatile String todoPanelId;

    public TodoPanelHost(AdapterHost host) {
        this.host = host;
    }

    public String panelId() {
        return todoPanelId;
    }

    public void clearPanelId() {
        todoPanelId = null;
    }

    public void reset() {
        todoScanner.clear();
        if (todoPanel != null) {
            todoPanel.setItems(List.of(), null);
        }
    }

    public void openPanel() {
        ensurePanel();
        if (todoPanelId != null) {
            host.requestOpenToolPanel(todoPanelId);
        }
    }

    private void ensurePanel() {
        if (todoPanel != null) {
            return;
        }
        JavaTodoPanel panel = new JavaTodoPanel(this);
        todoPanel = panel;
        panel.setProjectRoot(host.projectRoot());
        Icon icon = JavaIcons.todo(JavaIcons.SMALL);
        todoPanelId = host.registerPanel(panel, icon);
        rescan();
    }

    @Override
    public void rescan() {
        JavaTodoPanel panel = todoPanel;
        JavaProjectDescriptor current = host.descriptor();
        if (panel == null || current == null) {
            return;
        }
        panel.beginScan();
        todoScanner.setMarkers(host.todoMarkers());
        Path root = host.projectRoot();
        host.background().submit(() -> {
            List<TodoItem> found = todoScanner.scan(current);
            panel.setItems(found, root);
        });
    }

    public void refresh(Path filePath, String content) {
        JavaTodoPanel panel = todoPanel;
        if (panel == null || filePath == null || !JavaProjectConventions.isJava(filePath)) {
            return;
        }
        long ticket = todoRefreshTicket.incrementAndGet();
        host.background().schedule(() -> {
            if (ticket != todoRefreshTicket.get()) {
                return;
            }
            String source = content != null ? content
                    : JavaProjectConventions.readOrEmpty(filePath);
            if (todoScanner.refreshFile(filePath, source)) {
                panel.setItems(todoScanner.items(), host.projectRoot());
            }
        }, TODO_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    public void forget(Path file) {
        if (todoScanner.forget(file) && todoPanel != null) {
            todoPanel.setItems(todoScanner.items(), host.projectRoot());
        }
    }

    @Override
    public void open(TodoItem item) {
        if (item != null) {
            host.openAt(item.file(), item.line(), item.column());
        }
    }
}
