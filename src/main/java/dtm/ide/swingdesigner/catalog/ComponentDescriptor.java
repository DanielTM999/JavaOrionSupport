package dtm.ide.swingdesigner.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ComponentDescriptor(String className,
                                  String superClass,
                                  List<String> typeParameters,
                                  String displayName,
                                  String category,
                                  String icon,
                                  String description,
                                  ComponentOrigin origin,
                                  String source,
                                  Boolean abstractType,
                                  Boolean window,
                                  Boolean hidden,
                                  Boolean described,
                                  List<ConstructorInfo> constructors,
                                  ConstructorInfo preferredConstructor,
                                  Map<String, PropertyDescriptor> properties,
                                  List<EventDescriptor> events,
                                  ContainerSpec container,
                                  Boolean beanInfo,
                                  Boolean spi,
                                  List<String> designInit) {

    public ComponentDescriptor {
        Objects.requireNonNull(className, "className");
        typeParameters = typeParameters == null ? null : List.copyOf(typeParameters);
        constructors = constructors == null ? null : List.copyOf(constructors);
        properties = properties == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        events = events == null ? null : List.copyOf(events);
        designInit = designInit == null ? null : List.copyOf(designInit);
    }

    public static ComponentDescriptor named(String className) {
        return builder(className).build();
    }

    public static Builder builder(String className) {
        return new Builder(className);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(className);
        builder.superClass = superClass;
        builder.typeParameters = typeParameters;
        builder.displayName = displayName;
        builder.category = category;
        builder.icon = icon;
        builder.description = description;
        builder.origin = origin;
        builder.source = source;
        builder.abstractType = abstractType;
        builder.window = window;
        builder.hidden = hidden;
        builder.described = described;
        builder.constructors = constructors;
        builder.preferredConstructor = preferredConstructor;
        builder.properties = properties;
        builder.events = events;
        builder.container = container;
        builder.beanInfo = beanInfo;
        builder.spi = spi;
        builder.designInit = designInit;
        return builder;
    }

    public ComponentDescriptor overlay(ComponentDescriptor top) {
        if (top == null) {
            return this;
        }
        Builder merged = toBuilder();
        merged.superClass = PropertyDescriptor.pick(top.superClass, superClass);
        merged.typeParameters = PropertyDescriptor.pick(top.typeParameters, typeParameters);
        merged.displayName = PropertyDescriptor.pick(top.displayName, displayName);
        merged.category = PropertyDescriptor.pick(top.category, category);
        merged.icon = PropertyDescriptor.pick(top.icon, icon);
        merged.description = PropertyDescriptor.pick(top.description, description);
        merged.origin = PropertyDescriptor.pick(top.origin, origin);
        merged.source = PropertyDescriptor.pick(top.source, source);
        merged.abstractType = PropertyDescriptor.pick(top.abstractType, abstractType);
        merged.window = PropertyDescriptor.pick(top.window, window);
        merged.hidden = PropertyDescriptor.pick(top.hidden, hidden);
        merged.described = Boolean.TRUE.equals(top.described) || Boolean.TRUE.equals(described)
                ? Boolean.TRUE : null;
        merged.constructors = PropertyDescriptor.pick(top.constructors, constructors);
        merged.preferredConstructor = PropertyDescriptor.pick(top.preferredConstructor,
                preferredConstructor);
        merged.properties = mergeProperties(properties, top.properties);
        merged.events = mergeEvents(events, top.events);
        merged.container = container == null ? top.container : container.overlay(top.container);
        merged.beanInfo = PropertyDescriptor.pick(top.beanInfo, beanInfo);
        merged.spi = PropertyDescriptor.pick(top.spi, spi);
        merged.designInit = PropertyDescriptor.pick(top.designInit, designInit);
        return merged.build();
    }

    public ComponentDescriptor inherit(ComponentDescriptor parent) {
        if (parent == null) {
            return this;
        }
        Builder merged = toBuilder();
        merged.properties = mergeProperties(parent.properties, properties);
        merged.events = mergeEvents(parent.events, events);
        if (container == null) {
            merged.container = parent.container;
        } else if (parent.container != null && container.kind() == null) {
            merged.container = parent.container.overlay(container);
        }
        merged.window = PropertyDescriptor.pick(window, parent.window);
        merged.designInit = PropertyDescriptor.pick(designInit, parent.designInit);
        return merged.build();
    }

    public boolean isHidden() {
        return Boolean.TRUE.equals(hidden);
    }

    public boolean isAbstract() {
        return Boolean.TRUE.equals(abstractType);
    }

    public boolean isWindow() {
        return Boolean.TRUE.equals(window);
    }

    public boolean isDescribed() {
        return Boolean.TRUE.equals(described);
    }

    public boolean hasBeanInfo() {
        return Boolean.TRUE.equals(beanInfo);
    }

    public boolean isContainer() {
        return container != null && container.acceptsChildren();
    }

    public String simpleName() {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        return simple.replace('$', '.');
    }

    public String label() {
        return displayName != null ? displayName : simpleName();
    }

    public String packageName() {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    public Map<String, PropertyDescriptor> propertiesOrEmpty() {
        return properties == null ? Map.of() : properties;
    }

    public List<EventDescriptor> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }

    public List<String> designInitOrEmpty() {
        return designInit == null ? List.of() : designInit;
    }

    public List<ConstructorInfo> constructorsOrEmpty() {
        return constructors == null ? List.of() : constructors;
    }

    private static Map<String, PropertyDescriptor> mergeProperties(
            Map<String, PropertyDescriptor> base, Map<String, PropertyDescriptor> top) {
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

    private static List<EventDescriptor> mergeEvents(List<EventDescriptor> base,
                                                     List<EventDescriptor> top) {
        if (base == null) {
            return top;
        }
        if (top == null) {
            return base;
        }
        Map<String, EventDescriptor> merged = new LinkedHashMap<>();
        for (EventDescriptor event : base) {
            merged.put(event.listenerType(), event);
        }
        for (EventDescriptor event : top) {
            merged.put(event.listenerType(), event);
        }
        return new ArrayList<>(merged.values());
    }

    public static final class Builder {
        private final String className;
        private String superClass;
        private List<String> typeParameters;
        private String displayName;
        private String category;
        private String icon;
        private String description;
        private ComponentOrigin origin;
        private String source;
        private Boolean abstractType;
        private Boolean window;
        private Boolean hidden;
        private Boolean described;
        private List<ConstructorInfo> constructors;
        private ConstructorInfo preferredConstructor;
        private Map<String, PropertyDescriptor> properties;
        private List<EventDescriptor> events;
        private ContainerSpec container;
        private Boolean beanInfo;
        private Boolean spi;
        private List<String> designInit;

        private Builder(String className) {
            this.className = className;
        }

        public Builder superClass(String value) {
            superClass = value;
            return this;
        }

        public Builder typeParameters(List<String> value) {
            typeParameters = value;
            return this;
        }

        public Builder displayName(String value) {
            displayName = value;
            return this;
        }

        public Builder category(String value) {
            category = value;
            return this;
        }

        public Builder icon(String value) {
            icon = value;
            return this;
        }

        public Builder description(String value) {
            description = value;
            return this;
        }

        public Builder origin(ComponentOrigin value) {
            origin = value;
            return this;
        }

        public Builder source(String value) {
            source = value;
            return this;
        }

        public Builder abstractType(Boolean value) {
            abstractType = value;
            return this;
        }

        public Builder window(Boolean value) {
            window = value;
            return this;
        }

        public Builder hidden(Boolean value) {
            hidden = value;
            return this;
        }

        public Builder described(Boolean value) {
            described = value;
            return this;
        }

        public Builder constructors(List<ConstructorInfo> value) {
            constructors = value;
            return this;
        }

        public Builder preferredConstructor(ConstructorInfo value) {
            preferredConstructor = value;
            return this;
        }

        public Builder properties(Map<String, PropertyDescriptor> value) {
            properties = value;
            return this;
        }

        public Builder events(List<EventDescriptor> value) {
            events = value;
            return this;
        }

        public Builder container(ContainerSpec value) {
            container = value;
            return this;
        }

        public Builder beanInfo(Boolean value) {
            beanInfo = value;
            return this;
        }

        public Builder spi(Boolean value) {
            spi = value;
            return this;
        }

        public Builder designInit(List<String> value) {
            designInit = value;
            return this;
        }

        public ComponentDescriptor build() {
            return new ComponentDescriptor(className, superClass, typeParameters, displayName,
                    category, icon, description, origin, source, abstractType, window, hidden,
                    described, constructors, preferredConstructor, properties, events, container,
                    beanInfo, spi, designInit);
        }
    }
}
