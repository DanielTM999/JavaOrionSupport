package dtm.ide.swingdesigner.form;

import java.util.List;
import java.util.Optional;

public record FormListener(String addMethod,
                           Style style,
                           String reference,
                           String anonymousType,
                           List<AnonymousMethod> methods,
                           Span target,
                           FormCall call,
                           int anonymousEnd) {

    public enum Style {
        METHOD_REF, LAMBDA, ANONYMOUS, OTHER
    }

    public record AnonymousMethod(String name, String delegate, Span declaration) {
    }

    public FormListener {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public Optional<AnonymousMethod> method(String name) {
        return methods.stream().filter(method -> method.name().equals(name)).findFirst();
    }

    public boolean handles(String listenerMethod, boolean functional) {
        return switch (style) {
            case METHOD_REF, LAMBDA, OTHER -> functional || methods.isEmpty();
            case ANONYMOUS -> method(listenerMethod).isPresent();
        };
    }

    public Optional<String> handlerFor(String listenerMethod) {
        return switch (style) {
            case METHOD_REF -> Optional.ofNullable(reference);
            case LAMBDA -> Optional.ofNullable(reference);
            case ANONYMOUS -> method(listenerMethod).map(AnonymousMethod::delegate);
            case OTHER -> Optional.empty();
        };
    }
}
