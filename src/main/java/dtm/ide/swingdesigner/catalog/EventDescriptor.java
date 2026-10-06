package dtm.ide.swingdesigner.catalog;

import java.util.List;
import java.util.Objects;

public record EventDescriptor(String listenerType,
                              String addMethod,
                              String removeMethod,
                              List<EventMethod> methods) {

    public EventDescriptor {
        Objects.requireNonNull(listenerType, "listenerType");
        Objects.requireNonNull(addMethod, "addMethod");
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public record EventMethod(String name, String eventType) {
    }
}
