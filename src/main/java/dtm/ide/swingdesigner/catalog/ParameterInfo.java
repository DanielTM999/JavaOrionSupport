package dtm.ide.swingdesigner.catalog;

import java.util.Objects;

public record ParameterInfo(String name, String type) {

    public ParameterInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
