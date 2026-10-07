package dtm.ide.lsp;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class JdtDialect implements ServerDialect {
    private volatile boolean debugBundleLoaded;
    private volatile boolean testBundleLoaded;

    @Override
    public Map<String, Object> initializationOptions(Map<String, Object> settings,
                                                      List<String> bundlePaths) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("settings", settings);
        options.put("extendedClientCapabilities", Map.of(
                "progressReportProvider", true,
                "classFileContentsSupport", true,
                "overrideMethodsPromptSupport", true,
                "advancedOrganizeImportsSupport", true,
                "advancedGenerateAccessorsSupport", true,
                "generateConstructorsPromptSupport", true,
                "generateToStringPromptSupport", true,
                "hashCodeEqualsPromptSupport", true,
                "generateDelegateMethodsPromptSupport", true));

        debugBundleLoaded = bundlePaths.stream().anyMatch(path ->
                path.contains("com.microsoft.java.debug.plugin"));
        testBundleLoaded = bundlePaths.stream().anyMatch(path ->
                path.contains("com.microsoft.java.test.plugin"));
        if (!bundlePaths.isEmpty()) {
            options.put("bundles", bundlePaths);
            log.info("Bundles carregados no jdtls: {}", bundlePaths.size());
        }
        return options;
    }

    boolean debugBundleLoaded() {
        return debugBundleLoaded;
    }

    boolean testBundleLoaded() {
        return testBundleLoaded;
    }
}
