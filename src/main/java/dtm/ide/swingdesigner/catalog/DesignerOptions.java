package dtm.ide.swingdesigner.catalog;

public record DesignerOptions(Boolean stubs, Boolean recovery) {

    public static final DesignerOptions DEFAULTS = new DesignerOptions(Boolean.TRUE, Boolean.TRUE);
    public static final DesignerOptions UNSET = new DesignerOptions(null, null);

    public DesignerOptions overlay(DesignerOptions top) {
        if (top == null) {
            return this;
        }
        return new DesignerOptions(PropertyDescriptor.pick(top.stubs, stubs),
                PropertyDescriptor.pick(top.recovery, recovery));
    }

    public boolean stubsEnabled() {
        return !Boolean.FALSE.equals(stubs);
    }

    public boolean recoveryEnabled() {
        return !Boolean.FALSE.equals(recovery);
    }
}
