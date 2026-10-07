package dtm.ide.lsp.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ImportLookup(boolean diagnosed, Map<String, List<String>> candidates, Set<String> queried) {
    public static final ImportLookup PENDING = new ImportLookup(false, Map.of(), Set.of());

    public ImportLookup {
        candidates = candidates == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
        queried = queried == null ? Set.of() : Set.copyOf(queried);
    }
}
