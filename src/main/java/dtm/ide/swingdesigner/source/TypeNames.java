package dtm.ide.swingdesigner.source;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class TypeNames {

    public static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double");

    private final JavaSourceTree.Unit unit;
    private final String packageName;
    private final List<String> outerNames;
    private final List<String> explicitImports = new ArrayList<>();
    private final List<String> wildcardImports = new ArrayList<>();

    public TypeNames(JavaSourceTree.Unit unit, List<String> outerNames) {
        this.unit = unit;
        this.outerNames = List.copyOf(outerNames);
        this.packageName = unit.packageName();
        for (ImportTree importTree : unit.tree().getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            String name = importTree.getQualifiedIdentifier().toString();
            if (name.endsWith(".*")) {
                wildcardImports.add(name.substring(0, name.length() - 2));
            } else {
                explicitImports.add(name);
            }
        }
    }

    public String packageName() {
        return packageName;
    }

    public List<String> explicitImports() {
        return List.copyOf(explicitImports);
    }

    public List<String> wildcardImports() {
        return List.copyOf(wildcardImports);
    }

    public List<String> ofType(Tree type) {
        if (type == null) {
            return List.of();
        }
        String text = unit.text(type).replaceAll("<.*>", "").trim();
        if (text.isEmpty() || text.equals("var")) {
            return List.of();
        }
        if (PRIMITIVES.contains(text) || text.endsWith("[]")) {
            return List.of(text);
        }
        return candidates(text);
    }

    public List<String> candidates(String name) {
        Set<String> result = new LinkedHashSet<>();
        int dot = name.indexOf('.');
        String head = dot < 0 ? name : name.substring(0, dot);
        String tail = dot < 0 ? "" : name.substring(dot).replace('.', '$');
        if (dot > 0 && Character.isLowerCase(name.charAt(0))) {
            result.add(name);
        }
        StringBuilder outer = new StringBuilder(packageName.isEmpty() ? "" : packageName + ".");
        for (int i = 0; i < outerNames.size(); i++) {
            outer.append(i == 0 ? "" : "$").append(outerNames.get(i));
            result.add(outer + "$" + head + tail);
        }
        for (String imported : explicitImports) {
            if (imported.endsWith("." + head)) {
                result.add(imported + tail);
            }
        }
        result.add(packageName.isEmpty() ? head + tail : packageName + "." + head + tail);
        for (String wildcard : wildcardImports) {
            result.add(wildcard + "." + head + tail);
        }
        result.add("java.lang." + head + tail);
        return List.copyOf(result);
    }

    public Optional<String> qualifiedClass(MemberSelectTree select, Set<String> locals) {
        List<String> parts = new ArrayList<>();
        ExpressionTree current = select;
        while (current instanceof MemberSelectTree member) {
            parts.addFirst(member.getIdentifier().toString());
            current = member.getExpression();
        }
        if (!(current instanceof IdentifierTree head)) {
            return Optional.empty();
        }
        String first = head.getName().toString();
        if (locals.contains(first) || first.equals("this") || !Character.isLowerCase(first.charAt(0))) {
            return Optional.empty();
        }
        if (!Character.isUpperCase(parts.getLast().charAt(0))) {
            return Optional.empty();
        }
        for (int i = 0; i < parts.size() - 1; i++) {
            if (!Character.isLowerCase(parts.get(i).charAt(0))) {
                return Optional.empty();
            }
        }
        return Optional.of(first + "." + String.join(".", parts));
    }

    public boolean isVisible(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        if (qualifiedName.startsWith("java.lang.") && qualifiedName.indexOf('.', 10) < 0) {
            return true;
        }
        String owner = qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.lastIndexOf('.')) : "";
        if (owner.equals(packageName)) {
            return true;
        }
        if (explicitImports.contains(qualifiedName)) {
            return true;
        }
        for (String imported : explicitImports) {
            if (imported.endsWith("." + simple)) {
                return false;
            }
        }
        return wildcardImports.contains(owner);
    }
}
