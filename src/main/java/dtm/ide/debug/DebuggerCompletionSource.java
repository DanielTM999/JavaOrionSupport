package dtm.ide.debug;

import java.nio.file.Path;
import java.util.List;

public interface DebuggerCompletionSource {

    boolean isCompletionsSupported();

    Path pausedSource();

    List<JavaDebugSession.Completion> completions(String text, int column);
}
