package dtm.ide.spring;

import java.util.List;

public record SpringBeanTraits(
        String scope,
        boolean lazy,
        Integer order,
        List<String> dependsOn,
        List<String> qualifierAnnotations
) {

    public static final int DEFAULT_ORDER = Integer.MAX_VALUE;

    public SpringBeanTraits {
        scope = scope == null ? "" : scope.trim();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        qualifierAnnotations = qualifierAnnotations == null
                ? List.of() : List.copyOf(qualifierAnnotations);
    }

    public static SpringBeanTraits none() {
        return new SpringBeanTraits("", false, null, List.of(), List.of());
    }

    public boolean isSingleton() {
        return scope.isBlank() || "singleton".equals(scope);
    }

    public boolean isPrototype() {
        return "prototype".equals(scope);
    }

    public int orderValue() {
        return order == null ? DEFAULT_ORDER : order;
    }

    public boolean hasQualifierAnnotation(String simpleName) {
        return qualifierAnnotations.contains(simpleName);
    }
}
