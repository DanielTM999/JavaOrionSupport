package dtm.ide.swingdesigner.form;

import java.util.List;

public record FormCall(String method,
                       List<String> arguments,
                       Span invocation,
                       Span argumentsSpan,
                       Span statement,
                       Span removal,
                       String buildMethod) {

    public FormCall {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public boolean chained() {
        return removal.exists() && !removal.equals(statement);
    }

    public String argumentText() {
        return String.join(", ", arguments);
    }
}
