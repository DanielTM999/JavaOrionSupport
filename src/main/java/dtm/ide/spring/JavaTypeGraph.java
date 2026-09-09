package dtm.ide.spring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JavaTypeGraph {

    private static final int MAX_DEPTH = 12;

    private final Map<String, JavaType> byQualifiedName;
    private final Map<String, List<JavaType>> bySimpleName;

    private JavaTypeGraph(Map<String, JavaType> byQualifiedName,
                          Map<String, List<JavaType>> bySimpleName) {
        this.byQualifiedName = byQualifiedName;
        this.bySimpleName = bySimpleName;
    }

    public static JavaTypeGraph of(List<JavaType> types) {
        Map<String, JavaType> qualified = new LinkedHashMap<>();
        Map<String, List<JavaType>> simple = new LinkedHashMap<>();
        if (types != null) {
            for (JavaType type : types) {
                if (type.qualifiedName().isBlank()) {
                    continue;
                }
                qualified.put(type.qualifiedName(), type);
                simple.computeIfAbsent(type.simpleName(), key -> new ArrayList<>()).add(type);
            }
        }
        return new JavaTypeGraph(Map.copyOf(qualified), Map.copyOf(simple));
    }

    public static JavaTypeGraph empty() {
        return new JavaTypeGraph(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return byQualifiedName.isEmpty();
    }

    public int size() {
        return byQualifiedName.size();
    }

    public Optional<JavaType> byQualifiedName(String qualifiedName) {
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    public List<JavaType> bySimpleName(String simpleName) {
        return bySimpleName.getOrDefault(simpleName, List.of());
    }

    public Optional<JavaType> resolve(String typeName, JavaType context) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        String bare = SpringBean.simpleNameOf(typeName);
        if (typeName.indexOf('.') >= 0) {
            JavaType direct = byQualifiedName.get(stripGenerics(typeName));
            if (direct != null) {
                return Optional.of(direct);
            }
        }
        List<JavaType> candidates = bySimpleName(bare);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1 || context == null) {
            return Optional.of(candidates.getFirst());
        }
        for (String imported : context.imports()) {
            if (imported.endsWith("." + bare)) {
                JavaType byImport = byQualifiedName.get(imported);
                if (byImport != null) {
                    return Optional.of(byImport);
                }
            }
        }
        String samePackage = context.packageName().isBlank() ? bare
                : context.packageName() + "." + bare;
        JavaType sibling = byQualifiedName.get(samePackage);
        if (sibling != null) {
            return Optional.of(sibling);
        }
        for (String imported : context.imports()) {
            if (!imported.endsWith(".*")) {
                continue;
            }
            String candidate = imported.substring(0, imported.length() - 1) + bare;
            JavaType byWildcard = byQualifiedName.get(candidate);
            if (byWildcard != null) {
                return Optional.of(byWildcard);
            }
        }
        return Optional.of(candidates.getFirst());
    }

    public boolean isAssignable(JavaType candidate, String requestedType, JavaType requestContext) {
        if (candidate == null || requestedType == null || requestedType.isBlank()) {
            return false;
        }
        String requestedBare = SpringBean.simpleNameOf(requestedType);
        Optional<JavaType> requested = resolve(requestedType, requestContext);
        String requestedQualified = requested.map(JavaType::qualifiedName).orElse("");
        return walk(candidate, requestedBare, requestedQualified, new HashSet<>(), 0);
    }

    private boolean walk(JavaType current, String requestedBare, String requestedQualified,
                         Set<String> visited, int depth) {
        if (current == null || depth > MAX_DEPTH || !visited.add(current.qualifiedName())) {
            return false;
        }
        if (!requestedQualified.isBlank() && current.qualifiedName().equals(requestedQualified)) {
            return true;
        }
        if (requestedQualified.isBlank() && current.simpleName().equals(requestedBare)) {
            return true;
        }
        for (String supertype : current.supertypes()) {
            String bare = SpringBean.simpleNameOf(supertype);
            Optional<JavaType> resolved = resolve(supertype, current);
            if (resolved.isPresent()) {
                if (walk(resolved.get(), requestedBare, requestedQualified, visited, depth + 1)) {
                    return true;
                }
                continue;
            }
            if (bare.equals(requestedBare)) {
                return true;
            }
        }
        return false;
    }

    public List<String> supertypeClosure(JavaType type) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collect(type, names, 0);
        return List.copyOf(names);
    }

    private void collect(JavaType type, Set<String> names, int depth) {
        if (type == null || depth > MAX_DEPTH) {
            return;
        }
        for (String supertype : type.supertypes()) {
            String bare = SpringBean.simpleNameOf(supertype);
            if (!names.add(bare)) {
                continue;
            }
            resolve(supertype, type).ifPresent(resolved -> collect(resolved, names, depth + 1));
        }
    }

    private static String stripGenerics(String type) {
        int generics = type.indexOf('<');
        return generics > 0 ? type.substring(0, generics).trim() : type.trim();
    }
}
