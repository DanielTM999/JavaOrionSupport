package dtm.ide.swingdesigner.catalog;

import java.util.List;
import java.util.Objects;

public record PropertyDescriptor(String name,
                                 String type,
                                 String setter,
                                 SetterStyle setterStyle,
                                 List<ParameterInfo> setterParameters,
                                 String getter,
                                 String editor,
                                 List<String> enumValues,
                                 String defaultValue,
                                 String category,
                                 String displayName,
                                 String description,
                                 Boolean hidden,
                                 Boolean preferred,
                                 String codeTemplate) {

    public PropertyDescriptor {
        Objects.requireNonNull(name, "name");
        setterParameters = setterParameters == null ? null : List.copyOf(setterParameters);
        enumValues = enumValues == null ? null : List.copyOf(enumValues);
    }

    public static PropertyDescriptor named(String name) {
        return new PropertyDescriptor(name, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public PropertyDescriptor overlay(PropertyDescriptor top) {
        if (top == null) {
            return this;
        }
        return new PropertyDescriptor(name,
                pick(top.type, type),
                pick(top.setter, setter),
                pick(top.setterStyle, setterStyle),
                pick(top.setterParameters, setterParameters),
                pick(top.getter, getter),
                pick(top.editor, editor),
                pick(top.enumValues, enumValues),
                pick(top.defaultValue, defaultValue),
                pick(top.category, category),
                pick(top.displayName, displayName),
                pick(top.description, description),
                pick(top.hidden, hidden),
                pick(top.preferred, preferred),
                pick(top.codeTemplate, codeTemplate));
    }

    public int arity() {
        return setterParameters == null ? 1 : setterParameters.size();
    }

    public boolean isHidden() {
        return Boolean.TRUE.equals(hidden);
    }

    public boolean isPreferred() {
        return Boolean.TRUE.equals(preferred);
    }

    public boolean isReadable() {
        return getter != null;
    }

    public boolean isWritable() {
        return setter != null || codeTemplate != null;
    }

    public String label() {
        return displayName != null ? displayName : name;
    }

    public PropertyDescriptor withHidden(Boolean value) {
        return new PropertyDescriptor(name, type, setter, setterStyle, setterParameters, getter,
                editor, enumValues, defaultValue, category, displayName, description, value,
                preferred, codeTemplate);
    }

    static <T> T pick(T top, T base) {
        return top != null ? top : base;
    }
}
