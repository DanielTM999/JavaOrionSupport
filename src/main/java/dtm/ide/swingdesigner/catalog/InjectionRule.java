package dtm.ide.swingdesigner.catalog;

import java.util.Objects;

public record InjectionRule(String annotation, String attribute, String pattern) {

    public InjectionRule {
        Objects.requireNonNull(annotation, "annotation");
        attribute = attribute == null || attribute.isBlank() ? "value" : attribute;
        pattern = pattern == null || pattern.isBlank() ? null : pattern;
    }
}
