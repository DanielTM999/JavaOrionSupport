package dtm.ide.spring;

import java.util.List;
import java.util.Set;

public record InjectionTarget(String type, Multiplicity multiplicity, boolean optional) {

    public enum Multiplicity {
        ONE,
        MANY
    }

    private static final Set<String> COLLECTIONS =
            Set.of("List", "Set", "Collection", "Iterable", "SortedSet", "NavigableSet", "Stream");

    private static final Set<String> MAPS = Set.of("Map", "SortedMap", "NavigableMap");

    private static final Set<String> OPTIONALS =
            Set.of("Optional", "ObjectProvider", "Provider", "ObjectFactory");

    public InjectionTarget {
        type = type == null ? "" : type.trim();
        multiplicity = multiplicity == null ? Multiplicity.ONE : multiplicity;
    }

    public static InjectionTarget of(String declaredType) {
        return unwrap(declaredType, false, 0);
    }

    private static InjectionTarget unwrap(String declaredType, boolean optional, int depth) {
        if (declaredType == null || declaredType.isBlank() || depth > 4) {
            return new InjectionTarget(declaredType, Multiplicity.ONE, optional);
        }
        String bare = SpringBean.simpleNameOf(declaredType);
        List<String> arguments = JavaSourceLexer.typeArgumentsOf(declaredType.trim());

        if (COLLECTIONS.contains(bare) && arguments.size() == 1) {
            InjectionTarget inner = unwrap(arguments.getFirst(), optional, depth + 1);
            return new InjectionTarget(inner.type(), Multiplicity.MANY, inner.optional());
        }
        if (MAPS.contains(bare) && arguments.size() == 2) {
            InjectionTarget inner = unwrap(arguments.getLast(), optional, depth + 1);
            return new InjectionTarget(inner.type(), Multiplicity.MANY, inner.optional());
        }
        if (OPTIONALS.contains(bare) && arguments.size() == 1) {
            InjectionTarget inner = unwrap(arguments.getFirst(), true, depth + 1);
            return new InjectionTarget(inner.type(), inner.multiplicity(), true);
        }
        return new InjectionTarget(declaredType.trim(), Multiplicity.ONE, optional);
    }

    public boolean expectsMany() {
        return multiplicity == Multiplicity.MANY;
    }

    public String simpleName() {
        return SpringBean.simpleNameOf(type);
    }
}
