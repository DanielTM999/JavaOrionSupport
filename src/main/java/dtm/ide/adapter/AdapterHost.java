package dtm.ide.adapter;

import dtm.ide.ui.JavaTodoPanel;

import javax.swing.Icon;
import java.nio.file.Path;

public interface AdapterHost extends AdapterContext {
    String registerTodoPanel(JavaTodoPanel panel, Icon icon);
    void requestOpenToolPanel(String panelId);
    void openAt(Path file, int line, int column);
}
