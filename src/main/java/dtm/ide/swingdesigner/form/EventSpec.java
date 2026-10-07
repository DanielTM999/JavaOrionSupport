package dtm.ide.swingdesigner.form;

import java.util.List;

public record EventSpec(String addMethod,
                        String listenerType,
                        String method,
                        boolean functional,
                        String adapterType,
                        List<MethodSig> listenerMethods) {

    public record MethodSig(String name, List<String> parameterTypes, String returnType) {

        public MethodSig {
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
            returnType = returnType == null ? "void" : returnType;
        }
    }

    public EventSpec {
        listenerMethods = listenerMethods == null ? List.of() : List.copyOf(listenerMethods);
    }

    public MethodSig signature() {
        return listenerMethods.stream().filter(sig -> sig.name().equals(method)).findFirst()
                .orElse(new MethodSig(method, List.of(), "void"));
    }
}
