package dtm.ide.swingdesigner.catalog;

import java.util.List;
import java.util.Objects;

public record EventDescriptor(String listenerType,
                              String addMethod,
                              String removeMethod,
                              List<EventMethod> methods,
                              String adapterType,
                              String category,
                              Boolean hidden) {

    public EventDescriptor {
        Objects.requireNonNull(listenerType, "listenerType");
        Objects.requireNonNull(addMethod, "addMethod");
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public EventDescriptor(String listenerType, String addMethod, String removeMethod, List<EventMethod> methods) {
        this(listenerType, addMethod, removeMethod, methods, null, null, null);
    }

    public boolean functional() {
        return methods.size() == 1;
    }

    public boolean isHidden() {
        return Boolean.TRUE.equals(hidden);
    }

    public String simpleListenerName() {
        String clean = listenerType.replace('$', '.');
        return clean.substring(clean.lastIndexOf('.') + 1);
    }

    public EventDescriptor overlay(EventDescriptor top) {
        if (top == null) {
            return this;
        }
        return new EventDescriptor(listenerType, addMethod,
                PropertyDescriptor.pick(top.removeMethod, removeMethod),
                top.methods.isEmpty() || top.methods.stream().allMatch(method -> method.eventType() == null)
                        && !methods.isEmpty() ? methods : top.methods,
                PropertyDescriptor.pick(top.adapterType, adapterType),
                PropertyDescriptor.pick(top.category, category),
                PropertyDescriptor.pick(top.hidden, hidden));
    }

    public record EventMethod(String name, String eventType, List<String> parameterTypes, String returnType) {

        public EventMethod {
            parameterTypes = parameterTypes == null ? (eventType == null ? List.of() : List.of(eventType))
                    : List.copyOf(parameterTypes);
            returnType = returnType == null ? "void" : returnType;
        }

        public EventMethod(String name, String eventType) {
            this(name, eventType, null, null);
        }
    }
}
