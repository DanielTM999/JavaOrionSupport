package dtm.ide.swingdesigner.form;

import dtm.ide.swingdesigner.source.TypeNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FormModel {

    public static final String ROOT = "this";

    public record MethodInfo(String name,
                             List<String> parameterTypes,
                             Span declaration,
                             int bodyStart,
                             int bodyEnd,
                             boolean constructor,
                             int lastStatementEnd,
                             String statementIndent) {

        public MethodInfo {
            parameterTypes = List.copyOf(parameterTypes);
        }

        public boolean hasBody() {
            return bodyStart >= 0 && bodyEnd >= bodyStart;
        }
    }

    private final String source;
    private final String className;
    private final boolean window;
    private final Map<String, FormComponent> components;
    private final List<MethodInfo> methods;
    private final List<String> buildMethods;
    private final int classOpen;
    private final int classBodyEnd;
    private final int fieldInsert;
    private final String memberIndent;
    private final String indentUnit;
    private final boolean thisPrefix;
    private final TypeNames typeNames;
    private final List<String> notes;

    FormModel(String source, String className, boolean window, Map<String, FormComponent> components,
              List<MethodInfo> methods, List<String> buildMethods, int classOpen, int classBodyEnd,
              int fieldInsert, String memberIndent, String indentUnit, boolean thisPrefix, TypeNames typeNames,
              List<String> notes) {
        this.source = source;
        this.className = className;
        this.window = window;
        this.components = new LinkedHashMap<>(components);
        this.methods = List.copyOf(methods);
        this.buildMethods = List.copyOf(buildMethods);
        this.classOpen = classOpen;
        this.classBodyEnd = classBodyEnd;
        this.fieldInsert = fieldInsert;
        this.memberIndent = memberIndent;
        this.indentUnit = indentUnit;
        this.thisPrefix = thisPrefix;
        this.typeNames = typeNames;
        this.notes = List.copyOf(notes);
    }

    public String source() {
        return source;
    }

    public String className() {
        return className;
    }

    public boolean window() {
        return window;
    }

    public FormComponent root() {
        return components.get(ROOT);
    }

    public Collection<FormComponent> components() {
        return components.values();
    }

    public Optional<FormComponent> component(String id) {
        return Optional.ofNullable(id == null ? null : components.get(id));
    }

    public Optional<FormComponent> byName(String name) {
        return components.values().stream()
                .filter(component -> component.kind() == FormComponent.Kind.FIELD && name.equals(component.name()))
                .findFirst();
    }

    public List<FormComponent> children(String parentId) {
        List<FormComponent> children = new ArrayList<>();
        for (FormComponent component : components.values()) {
            if (parentId.equals(component.parentId())) {
                children.add(component);
            }
        }
        children.sort(Comparator.comparingInt(FormComponent::order));
        return children;
    }

    public Optional<String> contentId() {
        return components.values().stream().filter(component -> component.kind() == FormComponent.Kind.CONTENT)
                .map(FormComponent::id).findFirst();
    }

    public String containerFor(String id) {
        if (ROOT.equals(id) && window) {
            Optional<FormComponent> replaced = components.values().stream()
                    .filter(component -> component.attach() != null
                            && ROOT.equals(component.parentId())
                            && "setContentPane".equals(component.attach().method()))
                    .findFirst();
            if (replaced.isPresent()) {
                return replaced.get().id();
            }
            return contentId().orElse(id);
        }
        return id;
    }

    public List<MethodInfo> methods() {
        return methods;
    }

    public Optional<MethodInfo> method(String name) {
        return methods.stream().filter(method -> method.name().equals(name)).findFirst();
    }

    public List<MethodInfo> constructors() {
        return methods.stream().filter(MethodInfo::constructor).toList();
    }

    public List<String> buildMethods() {
        return buildMethods;
    }

    public boolean hasMember(String name) {
        return methods.stream().anyMatch(method -> method.name().equals(name))
                || components.values().stream().anyMatch(component -> name.equals(component.name()));
    }

    public int classOpen() {
        return classOpen;
    }

    public int classBodyEnd() {
        return classBodyEnd;
    }

    public int fieldInsert() {
        return fieldInsert;
    }

    public String memberIndent() {
        return memberIndent;
    }

    public String indentUnit() {
        return indentUnit;
    }

    public boolean thisPrefix() {
        return thisPrefix;
    }

    public TypeNames typeNames() {
        return typeNames;
    }

    public List<String> notes() {
        return notes;
    }

    public int line(int offset) {
        int line = 1;
        int bounded = Math.max(0, Math.min(offset, source.length()));
        for (int i = 0; i < bounded; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public String indentAt(int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int cursor = lineStart;
        while (cursor < source.length() && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) {
            cursor++;
        }
        return source.substring(lineStart, cursor);
    }
}
