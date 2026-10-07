package dtm.ide.swingdesigner.form;

import dtm.ide.swingdesigner.runtime.SnapshotNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FormLinks {

    public static final FormLinks EMPTY = new FormLinks(Map.of(), Map.of());

    private final Map<String, String> components;
    private final Map<String, String> nodes;

    private FormLinks(Map<String, String> components, Map<String, String> nodes) {
        this.components = Map.copyOf(components);
        this.nodes = Map.copyOf(nodes);
    }

    public static FormLinks link(SnapshotNode root, FormModel model) {
        if (root == null || model == null) {
            return EMPTY;
        }
        Map<String, String> components = new HashMap<>();
        Map<String, String> nodes = new HashMap<>();
        Set<String> used = new HashSet<>();
        components.put(root.id(), FormModel.ROOT);
        nodes.put(FormModel.ROOT, root.id());
        used.add(FormModel.ROOT);
        walk(root, FormModel.ROOT, model, components, nodes, used);
        return new FormLinks(components, nodes);
    }

    private static void walk(SnapshotNode node, String parent, FormModel model, Map<String, String> components,
                             Map<String, String> nodes, Set<String> used) {
        for (SnapshotNode child : node.children()) {
            String id = match(child, parent, model, used);
            if (id != null) {
                used.add(id);
                components.put(child.id(), id);
                nodes.put(id, child.id());
            }
            walk(child, id, model, components, nodes, used);
        }
    }

    private static String match(SnapshotNode child, String parent, FormModel model, Set<String> used) {
        if (child.field() != null && child.ownField()) {
            Optional<FormComponent> byField = model.byName(child.field());
            if (byField.isPresent() && !used.contains(byField.get().id())) {
                return byField.get().id();
            }
        }
        if ("contentPane".equals(child.role()) && FormModel.ROOT.equals(parent)) {
            String content = model.containerFor(FormModel.ROOT);
            if (!FormModel.ROOT.equals(content) && !used.contains(content)) {
                return content;
            }
        }
        if (parent == null) {
            return null;
        }
        List<FormComponent> candidates = model.children(parent);
        for (FormComponent candidate : candidates) {
            if (used.contains(candidate.id()) || candidate.kind() == FormComponent.Kind.CONTENT) {
                continue;
            }
            if (candidate.className() != null && candidate.className().equals(child.className())
                    && (candidate.kind() != FormComponent.Kind.FIELD || !child.ownField())) {
                return candidate.id();
            }
        }
        return null;
    }

    public Optional<String> componentOf(String nodeId) {
        return Optional.ofNullable(nodeId == null ? null : components.get(nodeId));
    }

    public Optional<String> nodeOf(String componentId) {
        return Optional.ofNullable(componentId == null ? null : nodes.get(componentId));
    }

    public boolean isEmpty() {
        return components.isEmpty();
    }
}
