package dtm.ide.spring;

import java.nio.file.Path;
import java.util.List;

public record SpringBean(
        String name,
        String type,
        String simpleName,
        SpringStereotype stereotype,
        Path file,
        int line,
        List<String> supertypes,
        List<String> profiles,
        boolean primary,
        String qualifier,
        boolean conditional
) {

    public SpringBean {
        name = name == null ? "" : name.trim();
        type = type == null ? "" : type.trim();
        simpleName = simpleName == null ? "" : simpleName.trim();
        supertypes = supertypes == null ? List.of() : List.copyOf(supertypes);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        qualifier = qualifier == null ? "" : qualifier.trim();
        line = Math.max(1, line);
    }

    public static String defaultBeanName(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return "";
        }
        String name = simpleName.trim();
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    public boolean provides(String requestedType) {
        if (requestedType == null || requestedType.isBlank()) {
            return false;
        }
        String requested = simpleNameOf(requestedType);
        if (simpleName.equals(requested)) {
            return true;
        }
        return supertypes.stream().anyMatch(supertype -> simpleNameOf(supertype).equals(requested));
    }

    public boolean hasQualifier() {
        return !qualifier.isBlank();
    }

    public boolean isProfileSpecific() {
        return !profiles.isEmpty();
    }

    public static String simpleNameOf(String type) {
        if (type == null) {
            return "";
        }
        String name = type.trim();
        int generics = name.indexOf('<');
        if (generics > 0) {
            name = name.substring(0, generics);
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }
}
