package dtm.ide.lsp;

import java.util.List;
import java.util.Map;

interface ServerDialect {
    Map<String, Object> initializationOptions(Map<String, Object> settings, List<String> bundlePaths);
}
