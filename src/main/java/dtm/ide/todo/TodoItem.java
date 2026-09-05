package dtm.ide.todo;

import java.nio.file.Path;

public record TodoItem(String marker, String message, Path file, int line, int column,
                       String context) implements Comparable<TodoItem> {

    public TodoItem {
        marker = marker == null ? "" : marker;
        message = message == null ? "" : message.trim();
        context = context == null ? "" : context;
        line = Math.max(0, line);
        column = Math.max(0, column);
    }

    public String display() {
        return message.isBlank() ? marker : marker + ": " + message;
    }

    @Override
    public int compareTo(TodoItem other) {
        int byFile = file.compareTo(other.file);
        return byFile != 0 ? byFile : Integer.compare(line, other.line);
    }
}
