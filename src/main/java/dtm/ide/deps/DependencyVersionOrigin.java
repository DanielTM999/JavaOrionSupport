package dtm.ide.deps;

public enum DependencyVersionOrigin {
    DIRECT,
    LOCAL_PROPERTY,
    LOCAL_MANAGEMENT,
    EXTERNAL_MANAGEMENT,
    UNRESOLVED;

    public boolean editable() {
        return this == DIRECT || this == LOCAL_PROPERTY || this == LOCAL_MANAGEMENT;
    }
}
