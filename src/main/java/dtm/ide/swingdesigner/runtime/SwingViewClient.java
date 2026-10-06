package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SwingViewClient {

    private final DesignerHostProcess host;

    public SwingViewClient(DesignerHostProcess host) {
        this.host = host;
    }

    public DesignerHostProcess host() {
        return host;
    }

    public String init(Collection<Path> libraries, Collection<Path> workspace, String lookAndFeel) {
        ObjectNode params = host.params();
        paths(params.putArray("libraries"), libraries);
        paths(params.putArray("workspace"), workspace);
        if (lookAndFeel != null && !lookAndFeel.isBlank()) {
            params.put("lookAndFeel", lookAndFeel);
        }
        return host.call("init", params).result().path("lookAndFeel").asText(null);
    }

    public void reload(Collection<Path> workspace) {
        ObjectNode params = host.params();
        paths(params.putArray("workspace"), workspace);
        host.call("reload", params);
    }

    public ViewResult view(String className, ConstructorUse constructor, int width, int height) {
        ObjectNode params = host.params();
        params.put("className", className);
        if (constructor != null) {
            params.set("constructor", constructor.toJson());
        }
        if (width > 0 && height > 0) {
            params.put("width", width);
            params.put("height", height);
        }
        return result(host.call("view", params));
    }

    public ViewResult render() {
        return result(host.call("render", host.params()));
    }

    public ViewResult setProperty(String nodeId, String setter, List<String> parameterTypes,
                                  List<JsonNode> values) {
        ObjectNode params = host.params();
        params.put("node", nodeId);
        params.put("setter", setter);
        ArrayNode types = params.putArray("parameterTypes");
        parameterTypes.forEach(types::add);
        ArrayNode array = params.putArray("values");
        values.forEach(array::add);
        return result(host.call("setProperty", params));
    }

    public Inspection inspect(String nodeId, Collection<PropertyDescriptor> properties) {
        ObjectNode params = host.params();
        params.put("node", nodeId);
        ArrayNode array = params.putArray("properties");
        for (PropertyDescriptor property : properties) {
            if (property.getter() == null) {
                continue;
            }
            ObjectNode entry = array.addObject();
            entry.put("name", property.name());
            entry.put("getter", property.getter());
        }
        JsonNode result = host.call("inspect", params).result();
        Map<String, JsonNode> values = new LinkedHashMap<>();
        result.path("values").fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue()));
        Map<String, String> errors = new LinkedHashMap<>();
        result.path("errors").fields().forEachRemaining(field -> errors.put(field.getKey(), field.getValue().asText()));
        return new Inspection(result.path("className").asText(), values, errors);
    }

    public String preview(String className, ConstructorUse constructor) {
        ObjectNode params = host.params();
        params.put("className", className);
        if (constructor != null) {
            params.set("constructor", constructor.toJson());
        }
        JsonNode result = host.call("preview", params).result();
        return result.hasNonNull("error") ? result.get("error").asText() : null;
    }

    public void closePreview() {
        host.call("closePreview", host.params());
    }

    static ViewResult result(HostResponse response) {
        JsonNode result = response.result();
        BufferedImage image = null;
        if (response.hasBlob()) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(response.blob()));
            } catch (IOException e) {
                throw new DesignerHostException("Imagem invalida recebida do Swing Designer", e);
            }
        }
        List<String> attempts = new ArrayList<>();
        for (JsonNode attempt : result.path("attempts")) {
            attempts.add(attempt.asText());
        }
        return new ViewResult(image,
                result.path("width").asInt(),
                result.path("height").asInt(),
                result.has("root") ? SnapshotNode.parse(result.get("root")) : null,
                result.has("constructor") ? ConstructorUse.parse(result.get("constructor")) : null,
                attempts,
                result.hasNonNull("error") ? result.get("error").asText() : null,
                result.hasNonNull("stackTrace") ? result.get("stackTrace").asText() : null,
                result.path("window").asBoolean(false),
                result.hasNonNull("title") ? result.get("title").asText() : null);
    }

    private static void paths(ArrayNode array, Collection<Path> paths) {
        if (paths == null) {
            return;
        }
        for (Path path : paths) {
            array.add(path.toAbsolutePath().toString());
        }
    }

    public record Inspection(String className, Map<String, JsonNode> values, Map<String, String> errors) {
    }
}
