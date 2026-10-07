package dtm.ide.swingdesigner.catalog;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class DescriptorSetReader {

    public static final String LIBRARY_RESOURCE = "META-INF/orion/swing-components.json";
    public static final String PROJECT_FILE = ".orion/swing-components.json";
    public static final String JDK_RESOURCE = "/swingdesigner/swing-jdk.json";
    public static final String BUNDLED_INDEX = "/swingdesigner/libraries/index.txt";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private DescriptorSetReader() {
    }

    public static DescriptorSet jdk() {
        try (InputStream in = DescriptorSetReader.class.getResourceAsStream(JDK_RESOURCE)) {
            if (in == null) {
                return empty("Swing", DescriptorSet.Layer.JDK);
            }
            return read(JSON.readTree(in), "Swing", DescriptorSet.Layer.JDK, null);
        } catch (IOException e) {
            log.warn("Could not read the built-in Swing descriptor: {}", e.toString());
            return empty("Swing", DescriptorSet.Layer.JDK);
        }
    }

    public static List<DescriptorSet> bundled() {
        List<DescriptorSet> sets = new ArrayList<>();
        try (InputStream index = DescriptorSetReader.class.getResourceAsStream(BUNDLED_INDEX)) {
            if (index == null) {
                return sets;
            }
            for (String line : new String(index.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim();
                if (name.isEmpty()) {
                    continue;
                }
                try (InputStream in = DescriptorSetReader.class.getResourceAsStream("/swingdesigner/libraries/" + name)) {
                    if (in != null) {
                        sets.add(read(JSON.readTree(in), name, DescriptorSet.Layer.BUNDLED, null));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the bundled library descriptors: {}", e.toString());
        }
        return sets;
    }

    public static Optional<DescriptorSet> project(Path projectRoot) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        Path file = projectRoot.resolve(PROJECT_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(JSON.readTree(file.toFile()), file.toString(),
                    DescriptorSet.Layer.PROJECT, null));
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring invalid {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public static List<DescriptorSet> libraries(ClasspathIndex index) {
        List<DescriptorSet> sets = new ArrayList<>();
        for (ClasspathIndex.Resource resource : index.resources(LIBRARY_RESOURCE)) {
            try {
                sets.add(read(JSON.readTree(resource.content()), resource.entry().label(),
                        DescriptorSet.Layer.LIBRARY, resource.entry().path()));
            } catch (IOException | RuntimeException e) {
                log.warn("Ignoring invalid {} in {}: {}", LIBRARY_RESOURCE,
                        resource.entry().path(), e.toString());
            }
        }
        return sets;
    }

    public static DescriptorSet parse(String json, String name, DescriptorSet.Layer layer) {
        try {
            return read(JSON.readTree(json), name, layer, null);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    static DescriptorSet read(JsonNode root, String fallbackName, DescriptorSet.Layer layer,
                              Path entry) {
        JsonNode library = root.path("library");
        String name = text(library, "name").orElse(fallbackName);
        String defaultCategory = text(library, "category").orElse(null);
        Map<String, ComponentDescriptor> components = new LinkedHashMap<>();
        fields(root.path("components")).forEach((className, node) -> {
            ComponentDescriptor component = component(className, node);
            components.put(className, component);
        });
        Map<String, LayoutDescriptor> layouts = new LinkedHashMap<>();
        fields(root.path("layouts")).forEach((className, node) ->
                layouts.put(className, layout(className, node)));
        return new DescriptorSet(name, layer, entry, defaultCategory, strings(root.path("hide")),
                components, layouts);
    }

    private static ComponentDescriptor component(String className, JsonNode node) {
        ComponentDescriptor.Builder builder = ComponentDescriptor.builder(className)
                .described(Boolean.TRUE)
                .displayName(text(node, "displayName").orElse(null))
                .category(text(node, "category").orElse(null))
                .icon(text(node, "icon").orElse(null))
                .description(text(node, "description").orElse(null))
                .hidden(bool(node, "hidden"))
                .window(bool(node, "window"))
                .typeParameters(node.has("typeParameters") ? strings(node.path("typeParameters")) : null)
                .designInit(node.has("designInit") ? strings(node.path("designInit")) : null);
        if (node.has("constructor")) {
            builder.preferredConstructor(constructor(node.path("constructor")));
        }
        if (node.has("properties")) {
            builder.properties(properties(node.path("properties")));
        }
        if (node.has("events")) {
            builder.events(events(node.path("events")));
        }
        if (node.has("container")) {
            builder.container(container(node.path("container")));
        }
        return builder.build();
    }

    private static ConstructorInfo constructor(JsonNode node) {
        List<ParameterInfo> parameters = parameters(node.path("parameters"));
        List<String> bound = strings(node.path("bind"));
        String factory = text(node, "factory").orElse(null);
        return new ConstructorInfo(parameters, bound, factory);
    }

    private static List<ParameterInfo> parameters(JsonNode node) {
        List<ParameterInfo> parameters = new ArrayList<>();
        int index = 0;
        for (JsonNode parameter : node) {
            if (parameter.isTextual()) {
                parameters.add(new ParameterInfo("arg" + index, parameter.asText()));
            } else {
                parameters.add(new ParameterInfo(text(parameter, "name").orElse("arg" + index),
                        text(parameter, "type").orElse("java.lang.Object")));
            }
            index++;
        }
        return parameters;
    }

    static Map<String, PropertyDescriptor> properties(JsonNode node) {
        Map<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        fields(node).forEach((name, value) -> properties.put(name, property(name, value)));
        return properties;
    }

    private static PropertyDescriptor property(String name, JsonNode node) {
        List<ParameterInfo> parameters = node.has("parameters")
                ? parameters(node.path("parameters")) : null;
        Boolean hidden = bool(node, "hidden");
        if (hidden == null && parameters != null && !parameters.isEmpty()) {
            hidden = Boolean.FALSE;
        }
        SetterStyle style = text(node, "setterStyle")
                .map(value -> SetterStyle.valueOf(value.toUpperCase(Locale.ROOT)))
                .orElse(null);
        List<String> enumValues = node.has("enumValues") ? strings(node.path("enumValues")) : null;
        return new PropertyDescriptor(name,
                text(node, "type").orElse(null),
                text(node, "setter").orElse(null),
                style,
                parameters,
                text(node, "getter").orElse(null),
                text(node, "editor").orElse(null),
                enumValues,
                text(node, "default").orElse(null),
                text(node, "category").orElse(null),
                text(node, "displayName").orElse(null),
                text(node, "description").orElse(null),
                hidden,
                bool(node, "preferred"),
                text(node, "code").orElse(null));
    }

    private static List<EventDescriptor> events(JsonNode node) {
        List<EventDescriptor> events = new ArrayList<>();
        for (JsonNode event : node) {
            Optional<String> listener = text(event, "listener");
            if (listener.isEmpty()) {
                continue;
            }
            String simple = listener.get().substring(listener.get().lastIndexOf('.') + 1);
            List<EventDescriptor.EventMethod> methods = new ArrayList<>();
            for (JsonNode method : event.path("methods")) {
                if (method.isTextual()) {
                    methods.add(new EventDescriptor.EventMethod(method.asText(), null));
                } else {
                    methods.add(new EventDescriptor.EventMethod(text(method, "name").orElse("handle"),
                            text(method, "event").orElse(null)));
                }
            }
            events.add(new EventDescriptor(listener.get(),
                    text(event, "add").orElse("add" + simple),
                    text(event, "remove").orElse("remove" + simple),
                    methods));
        }
        return events;
    }

    private static ContainerSpec container(JsonNode node) {
        ContainerKind kind = text(node, "kind")
                .map(value -> ContainerKind.valueOf(value.toUpperCase(Locale.ROOT)))
                .orElse(null);
        Map<String, String> slots = null;
        if (node.has("slots")) {
            slots = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> slot : fields(node.path("slots")).entrySet()) {
                slots.put(slot.getKey(), slot.getValue().asText());
            }
        }
        return new ContainerSpec(kind,
                text(node, "strategy").orElse(null),
                text(node, "target").orElse(null),
                slots,
                node.has("childProperties") ? properties(node.path("childProperties")) : null);
    }

    private static LayoutDescriptor layout(String className, JsonNode node) {
        return new LayoutDescriptor(className,
                text(node, "displayName").orElse(null),
                text(node, "constructor").orElse(null),
                text(node, "constraintsType").orElse(null),
                text(node, "dropPolicy").orElse(null),
                node.has("properties") ? properties(node.path("properties")) : null,
                node.has("constraints") ? properties(node.path("constraints")) : null);
    }

    private static DescriptorSet empty(String name, DescriptorSet.Layer layer) {
        return new DescriptorSet(name, layer, null, null, List.of(), Map.of(), Map.of());
    }

    private static Map<String, JsonNode> fields(JsonNode node) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return fields;
        }
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> field = iterator.next();
            fields.put(field.getKey(), field.getValue());
        }
        return fields;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value.isTextual()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asBoolean();
    }
}
