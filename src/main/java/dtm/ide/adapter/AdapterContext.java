package dtm.ide.adapter;

import dtm.ide.concurrent.PluginTaskExecutor;
import dtm.ide.editor.JavaSnippetCompletionProvider;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.List;

public interface AdapterContext {
    boolean debugActive();
    JavaLanguageServer interactiveServerFor(Path file);
    JavaProjectDescriptor descriptor();
    JavaSnippetCompletionProvider snippets();
    Path projectRoot();
    List<String> todoMarkers();
    PluginTaskExecutor background();
}
