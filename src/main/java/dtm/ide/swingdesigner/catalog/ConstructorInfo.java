package dtm.ide.swingdesigner.catalog;

import java.util.List;

public record ConstructorInfo(List<ParameterInfo> parameters,
                              List<String> boundProperties,
                              String factoryMethod) {

    public ConstructorInfo {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        boundProperties = boundProperties == null ? List.of() : List.copyOf(boundProperties);
    }

    public static ConstructorInfo of(List<ParameterInfo> parameters) {
        return new ConstructorInfo(parameters, List.of(), null);
    }

    public static ConstructorInfo factory(String method, List<ParameterInfo> parameters) {
        return new ConstructorInfo(parameters, List.of(), method);
    }

    public boolean isFactory() {
        return factoryMethod != null;
    }

    public boolean isNoArg() {
        return parameters.isEmpty() && !isFactory();
    }

    public List<String> parameterTypes() {
        return parameters.stream().map(ParameterInfo::type).toList();
    }

    public ConstructorInfo withBoundProperties(List<String> properties) {
        return new ConstructorInfo(parameters, properties, factoryMethod);
    }
}
