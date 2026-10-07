package dtm.ide.lsp;

import dtm.ide.lsp.api.JavaLanguageServerProvider;

public final class LanguageServers {

    private static final JavaLanguageServerProvider DEFAULT = new JdtLsProvider();

    private LanguageServers() {
    }

    public static JavaLanguageServerProvider defaultProvider() {
        return DEFAULT;
    }
}
