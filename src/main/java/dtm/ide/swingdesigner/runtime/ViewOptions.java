package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.swingdesigner.catalog.InjectionRule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ViewOptions(List<String> designInit,
                          List<InjectionRule> injections,
                          Map<String, JsonNode> designValues,
                          boolean stubs) {

    public static final ViewOptions NONE = new ViewOptions(List.of(), List.of(), Map.of(), true);

    public ViewOptions {
        designInit = designInit == null ? List.of() : List.copyOf(designInit);
        injections = injections == null ? List.of() : List.copyOf(injections);
        designValues = designValues == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(designValues));
    }

    public static ViewOptions of(List<String> designInit) {
        return new ViewOptions(designInit, List.of(), Map.of(), true);
    }

    void writeTo(ObjectNode params) {
        ArrayNode hooks = params.putArray("designInit");
        designInit.forEach(hooks::add);
        ArrayNode rules = params.putArray("injections");
        for (InjectionRule rule : injections) {
            ObjectNode entry = rules.addObject();
            entry.put("annotation", rule.annotation());
            entry.put("attribute", rule.attribute());
            if (rule.pattern() != null) {
                entry.put("pattern", rule.pattern());
            }
        }
        ObjectNode values = params.putObject("designValues");
        designValues.forEach(values::set);
        params.put("stubs", stubs);
    }
}
