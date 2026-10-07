package dtm.ide.swingdesigner.form;

import java.util.List;
import java.util.Optional;

public record FormComponent(String id,
                            Kind kind,
                            String name,
                            String className,
                            Span field,
                            Span declaration,
                            Span creation,
                            String creationText,
                            String creationMethod,
                            List<FormCall> properties,
                            FormCall layout,
                            String parentId,
                            FormCall attach,
                            int order,
                            List<FormListener> listeners,
                            List<FormCall> others,
                            int anchor,
                            String anchorMethod,
                            String lockReason) {

    public enum Kind {
        ROOT, CONTENT, FIELD, LOCAL
    }

    public FormComponent {
        properties = properties == null ? List.of() : List.copyOf(properties);
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
        others = others == null ? List.of() : List.copyOf(others);
    }

    public boolean locked() {
        return lockReason != null;
    }

    public boolean isRoot() {
        return kind == Kind.ROOT;
    }

    public String simpleClassName() {
        if (className == null) {
            return "?";
        }
        int dot = className.lastIndexOf('.');
        return (dot < 0 ? className : className.substring(dot + 1)).replace('$', '.');
    }

    public Optional<FormCall> property(String setter) {
        FormCall found = null;
        for (FormCall call : properties) {
            if (call.method().equals(setter)) {
                found = call;
            }
        }
        return Optional.ofNullable(found);
    }

    public List<FormListener> listeners(String addMethod) {
        return listeners.stream().filter(listener -> listener.addMethod().equals(addMethod)).toList();
    }

    public String reference(boolean thisPrefix) {
        return switch (kind) {
            case ROOT -> "this";
            case CONTENT -> "getContentPane()";
            case FIELD -> thisPrefix ? "this." + name : name;
            case LOCAL -> name;
        };
    }
}
