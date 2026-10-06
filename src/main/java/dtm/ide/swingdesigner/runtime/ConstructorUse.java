package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public record ConstructorUse(List<String> types, List<JsonNode> values, String factory) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ConstructorUse {
        types = types == null ? List.of() : List.copyOf(types);
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static ConstructorUse parse(JsonNode node) {
        List<String> types = new ArrayList<>();
        for (JsonNode type : node.path("types")) {
            types.add(type.asText());
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode value : node.path("values")) {
            values.add(value);
        }
        return new ConstructorUse(types, values, node.hasNonNull("factory") ? node.get("factory").asText() : null);
    }

    public boolean hasParameters() {
        return !types.isEmpty();
    }

    public ConstructorUse withValue(int index, JsonNode value) {
        List<JsonNode> updated = new ArrayList<>(values);
        while (updated.size() < types.size()) {
            updated.add(JSON.nullNode());
        }
        updated.set(index, value);
        return new ConstructorUse(types, updated, factory);
    }

    ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode typeArray = node.putArray("types");
        types.forEach(typeArray::add);
        ArrayNode valueArray = node.putArray("values");
        values.forEach(valueArray::add);
        if (factory != null) {
            node.put("factory", factory);
        }
        return node;
    }
}
