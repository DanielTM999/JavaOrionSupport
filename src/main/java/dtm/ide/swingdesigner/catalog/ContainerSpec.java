package dtm.ide.swingdesigner.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ContainerSpec(ContainerKind kind,
                            String childStrategy,
                            String layoutTarget,
                            Map<String, String> slots,
                            Map<String, PropertyDescriptor> childProperties) {

    public static final String DEFAULT_LAYOUT_STRATEGY = "add(${child}, ${constraints})";

    public static final ContainerSpec NONE = new ContainerSpec(ContainerKind.NONE, null, null,
            null, null);

    public ContainerSpec {
        slots = slots == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        childProperties = childProperties == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(childProperties));
    }

    public static ContainerSpec layout() {
        return new ContainerSpec(ContainerKind.LAYOUT, DEFAULT_LAYOUT_STRATEGY, null, null, null);
    }

    public ContainerSpec overlay(ContainerSpec top) {
        if (top == null) {
            return this;
        }
        Map<String, PropertyDescriptor> mergedChildren = new LinkedHashMap<>();
        if (childProperties != null) {
            mergedChildren.putAll(childProperties);
        }
        if (top.childProperties != null) {
            top.childProperties.forEach((key, value) ->
                    mergedChildren.merge(key, value, PropertyDescriptor::overlay));
        }
        ContainerKind mergedKind = PropertyDescriptor.pick(top.kind, kind);
        boolean kindChanged = top.kind != null && top.kind != kind;
        return new ContainerSpec(mergedKind,
                kindChanged ? top.childStrategy : PropertyDescriptor.pick(top.childStrategy, childStrategy),
                kindChanged ? top.layoutTarget : PropertyDescriptor.pick(top.layoutTarget, layoutTarget),
                kindChanged ? top.slots : PropertyDescriptor.pick(top.slots, slots),
                mergedChildren.isEmpty() ? null : mergedChildren);
    }

    public boolean acceptsChildren() {
        return kind != null && kind != ContainerKind.NONE;
    }

    public String effectiveChildStrategy() {
        if (childStrategy != null) {
            return childStrategy;
        }
        return kind == ContainerKind.LAYOUT ? DEFAULT_LAYOUT_STRATEGY : null;
    }
}
