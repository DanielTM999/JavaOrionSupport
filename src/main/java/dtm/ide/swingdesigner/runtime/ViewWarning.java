package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ViewWarning(String kind,
                          String origin,
                          String method,
                          String message,
                          String exception,
                          String hint,
                          List<Frame> frames,
                          String stack) {

    public static final String ERROR = "error";
    public static final String INFO = "info";

    public ViewWarning {
        kind = kind == null ? INFO : kind;
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

    public static ViewWarning info(String message) {
        return new ViewWarning(INFO, null, null, message, null, null, List.of(), null);
    }

    public static ViewWarning parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return info("");
        }
        if (node.isTextual()) {
            return info(node.asText());
        }
        List<Frame> frames = new ArrayList<>();
        for (JsonNode frame : node.path("frames")) {
            frames.add(new Frame(frame.path("className").asText(""), frame.path("method").asText(""),
                    frame.hasNonNull("file") ? frame.get("file").asText() : null,
                    frame.path("line").asInt(-1)));
        }
        return new ViewWarning(text(node, "kind"), text(node, "origin"), text(node, "method"),
                text(node, "message"), text(node, "exception"), text(node, "hint"), frames,
                text(node, "stack"));
    }

    public boolean isError() {
        return ERROR.equals(kind);
    }

    public Optional<Frame> firstFrame() {
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.getFirst());
    }

    public String text() {
        StringBuilder text = new StringBuilder(message == null ? "" : message);
        firstFrame().ifPresent(frame -> text.append(" em ").append(frame.label()));
        return text.toString();
    }

    public ViewWarning withMessage(String value) {
        return new ViewWarning(kind, origin, method, value, exception, hint, frames, stack);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    public record Frame(String className, String method, String file, int line) {

        public String simpleClassName() {
            int dot = className.lastIndexOf('.');
            return dot < 0 ? className : className.substring(dot + 1);
        }

        public String outerClassName() {
            int dollar = className.indexOf('$');
            return dollar < 0 ? className : className.substring(0, dollar);
        }

        public String sourceMethod() {
            if (method.startsWith("lambda$")) {
                int end = method.lastIndexOf('$');
                return end > 7 ? method.substring(7, end) : method;
            }
            return method;
        }

        public String label() {
            String location = file == null ? "" : "(" + file + (line > 0 ? ":" + line : "") + ")";
            return simpleClassName() + "." + method + location;
        }
    }
}
