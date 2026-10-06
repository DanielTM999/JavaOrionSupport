package dtm.ide.swingdesigner.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record LayoutDescriptor(String className,
                               String displayName,
                               String constructorTemplate,
                               String constraintsType,
                               String dropPolicy,
                               Map<String, PropertyDescriptor> properties,
                               Map<String, PropertyDescriptor> constraints) {

    public LayoutDescriptor {
        Objects.requireNonNull(className, "className");
        properties = properties == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        constraints = constraints == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
    }

    public LayoutDescriptor overlay(LayoutDescriptor top) {
        if (top == null) {
            return this;
        }
        return new LayoutDescriptor(className,
                PropertyDescriptor.pick(top.displayName, displayName),
                PropertyDescriptor.pick(top.constructorTemplate, constructorTemplate),
                PropertyDescriptor.pick(top.constraintsType, constraintsType),
                PropertyDescriptor.pick(top.dropPolicy, dropPolicy),
                merge(properties, top.properties),
                merge(constraints, top.constraints));
    }

    public String label() {
        if (displayName != null) {
            return displayName;
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static Map<String, PropertyDescriptor> merge(Map<String, PropertyDescriptor> base,
                                                         Map<String, PropertyDescriptor> top) {
        if (base == null) {
            return top;
        }
        if (top == null) {
            return base;
        }
        Map<String, PropertyDescriptor> merged = new LinkedHashMap<>(base);
        top.forEach((key, value) -> merged.merge(key, value, PropertyDescriptor::overlay));
        return merged;
    }
}
