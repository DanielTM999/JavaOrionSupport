package dtm.ide.swingdesigner.form;

public record Span(int start, int end) {

    public static final Span NONE = new Span(-1, -1);

    public boolean exists() {
        return start >= 0 && end >= start;
    }

    public String text(String source) {
        if (!exists() || source == null || end > source.length()) {
            return "";
        }
        return source.substring(start, end);
    }

    public boolean contains(int offset) {
        return exists() && start <= offset && offset < end;
    }
}
