package dtm.ide.lsp.api;

import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface JavaLanguageServerProvider {

    JavaLanguageServer create(JdkService jdks, SdkDownloader downloader, Consumer<Path> onDiagnosticsPublished);
}
