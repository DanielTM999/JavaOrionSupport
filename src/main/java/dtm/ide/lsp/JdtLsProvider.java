package dtm.ide.lsp;

import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.JavaLanguageServerProvider;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class JdtLsProvider implements JavaLanguageServerProvider {

    @Override
    public JavaLanguageServer create(JdkService jdks, SdkDownloader downloader,
                                     Consumer<Path> onDiagnosticsPublished) {
        JdtLsProvisioner provisioner = new JdtLsProvisioner(downloader, jdks.sdkRoot());
        JdtLsExtensionBundles extensionBundles = new JdtLsExtensionBundles(downloader, jdks.sdkRoot());
        return new JdtLsService(jdks, provisioner, extensionBundles, onDiagnosticsPublished);
    }
}
