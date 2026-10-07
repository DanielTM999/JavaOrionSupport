package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public record SnapshotNode(String id,
                           String className,
                           String field,
                           String name,
                           Rectangle bounds,
                           boolean visible,
                           List<SnapshotNode> children,
                           boolean ownField,
                           String role,
                           int index,
                           JsonNode constraints,
                           JsonNode layout,
                           Dimension preferred) {

    public SnapshotNode {
        children = children == null ? List.of() : List.copyOf(children);
        bounds = bounds == null ? new Rectangle() : new Rectangle(bounds);
        constraints = constraints == null ? MissingNode.getInstance() : constraints;
        layout = layout == null ? MissingNode.getInstance() : layout;
        preferred = preferred == null ? new Dimension() : new Dimension(preferred);
    }

    public SnapshotNode(String id, String className, String field, String name, Rectangle bounds,
                        boolean visible, List<SnapshotNode> children) {
        this(id, className, field, name, bounds, visible, children, field != null, null, -1, null, null, null);
    }

    public static SnapshotNode parse(JsonNode node) {
        List<SnapshotNode> children = new ArrayList<>();
        for (JsonNode child : node.path("children")) {
            children.add(parse(child));
        }
        return new SnapshotNode(node.path("id").asText(),
                node.path("className").asText(),
                node.hasNonNull("field") ? node.get("field").asText() : null,
                node.hasNonNull("name") ? node.get("name").asText() : null,
                new Rectangle(node.path("x").asInt(), node.path("y").asInt(),
                        node.path("width").asInt(), node.path("height").asInt()),
                node.path("visible").asBoolean(true),
                children,
                node.path("ownField").asBoolean(false),
                node.hasNonNull("role") ? node.get("role").asText() : null,
                node.path("index").asInt(-1),
                node.path("constraints"),
                node.path("layout"),
                new Dimension(node.path("prefWidth").asInt(), node.path("prefHeight").asInt()));
    }

    public String simpleClassName() {
        int dot = className.lastIndexOf('.');
        return (dot < 0 ? className : className.substring(dot + 1)).replace('$', '.');
    }

    public String label() {
        if (field != null) {
            return field + " : " + simpleClassName();
        }
        if (name != null && !name.isBlank()) {
            return name + " : " + simpleClassName();
        }
        return simpleClassName();
    }

    public Rectangle boundsCopy() {
        return new Rectangle(bounds);
    }

    public String layoutClass() {
        return layout.path("className").asText(null);
    }

    public boolean hasLayout() {
        return layout.has("className");
    }

    public Optional<SnapshotNode> find(String nodeId) {
        if (id.equals(nodeId)) {
            return Optional.of(this);
        }
        for (SnapshotNode child : children) {
            Optional<SnapshotNode> found = child.find(nodeId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    public Optional<SnapshotNode> parentOf(String nodeId) {
        for (SnapshotNode child : children) {
            if (child.id.equals(nodeId)) {
                return Optional.of(this);
            }
            Optional<SnapshotNode> found = child.parentOf(nodeId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    public Optional<SnapshotNode> deepestAt(int x, int y) {
        if (!visible || !bounds.contains(x, y)) {
            return Optional.empty();
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            Optional<SnapshotNode> found = children.get(i).deepestAt(x, y);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.of(this);
    }

    public void forEach(Consumer<SnapshotNode> action) {
        action.accept(this);
        children.forEach(child -> child.forEach(action));
    }
}
