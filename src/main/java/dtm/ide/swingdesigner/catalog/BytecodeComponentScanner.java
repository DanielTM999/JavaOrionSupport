package dtm.ide.swingdesigner.catalog;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BytecodeComponentScanner {

    private static final String DEPRECATED = "Ljava/lang/Deprecated;";
    private static final String EVENT_LISTENER = "java.util.EventListener";
    private static final int PUBLIC = AccessFlag.PUBLIC.mask();
    private static final int STATIC = AccessFlag.STATIC.mask();
    private static final int SKIPPED = AccessFlag.SYNTHETIC.mask() | AccessFlag.BRIDGE.mask();

    private final ClasspathIndex index;
    private final Map<String, Optional<ComponentDescriptor>> scanned = new ConcurrentHashMap<>();
    private final Map<String, Optional<ClassModel>> models = new ConcurrentHashMap<>();

    public BytecodeComponentScanner(ClasspathIndex index) {
        this.index = index;
    }

    public Optional<ComponentDescriptor> scan(String className) {
        return scanned.computeIfAbsent(className, this::scanNow);
    }

    public List<String> enumConstants(String className) {
        Optional<ClassHeader> header = index.header(className);
        if (header.isEmpty() || !header.get().isEnum()) {
            return List.of();
        }
        Optional<ClassModel> model = model(className);
        if (model.isEmpty()) {
            return List.of();
        }
        List<String> constants = new ArrayList<>();
        for (FieldModel field : model.get().fields()) {
            if ((field.flags().flagsMask() & AccessFlag.ENUM.mask()) != 0) {
                constants.add(field.fieldName().stringValue());
            }
        }
        return constants;
    }

    private Optional<ComponentDescriptor> scanNow(String className) {
        Optional<ClassHeader> header = index.header(className);
        if (header.isEmpty() || !index.isDrawable(className)) {
            return Optional.empty();
        }
        Optional<ClassModel> model = model(className);
        if (model.isEmpty()) {
            return Optional.empty();
        }
        ClassHeader classHeader = header.get();
        Set<String> selfTypes = new LinkedHashSet<>(index.superChain(className));
        List<ConstructorInfo> constructors = new ArrayList<>();
        Map<String, List<Accessor>> setters = new LinkedHashMap<>();
        Map<String, Accessor> getters = new HashMap<>();
        Map<String, Accessor> adders = new LinkedHashMap<>();
        Map<String, Accessor> removers = new HashMap<>();

        for (MethodModel method : model.get().methods()) {
            int flags = method.flags().flagsMask();
            if ((flags & PUBLIC) == 0 || (flags & SKIPPED) != 0 || isDeprecated(method)) {
                continue;
            }
            String name = method.methodName().stringValue();
            MethodTypeDesc type = method.methodTypeSymbol();
            boolean isStatic = (flags & STATIC) != 0;
            if (name.equals("<init>")) {
                if (!classHeader.isAbstract()) {
                    constructors.add(ConstructorInfo.of(parameters(method, type, false)));
                }
                continue;
            }
            if (isStatic) {
                if (!classHeader.isAbstract()
                        && ClassHeaders.typeName(type.returnType()).equals(className)) {
                    constructors.add(ConstructorInfo.factory(name, parameters(method, type, true)));
                }
                continue;
            }
            Accessor accessor = new Accessor(name, type, method);
            if (isSetterName(name) && type.parameterCount() >= 1
                    && isSetterReturn(type.returnType(), selfTypes)) {
                setters.computeIfAbsent(propertyName(name.substring(3)), key -> new ArrayList<>())
                        .add(accessor);
            } else if (type.parameterCount() == 0) {
                getterName(name, type).ifPresent(property -> getters.putIfAbsent(property, accessor));
            }
            if (type.parameterCount() == 1 && name.startsWith("add") && name.endsWith("Listener")
                    && type.returnType().descriptorString().equals("V")) {
                adders.put(name.substring(3), accessor);
            }
            if (type.parameterCount() == 1 && name.startsWith("remove") && name.endsWith("Listener")) {
                removers.put(name.substring(6), accessor);
            }
        }

        Map<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        setters.forEach((property, candidates) ->
                property(property, candidates, getters.get(property), selfTypes)
                        .ifPresent(descriptor -> properties.put(property, descriptor)));

        List<EventDescriptor> events = new ArrayList<>();
        adders.forEach((key, adder) -> event(key, adder, removers.get(key)).ifPresent(events::add));

        ComponentOrigin origin = index.entryOf(classHeader).map(ClasspathEntry::origin)
                .orElse(ComponentOrigin.JDK);
        String source = index.entryOf(classHeader).map(entry -> entry.path().toString()).orElse(null);
        ComponentDescriptor descriptor = ComponentDescriptor.builder(className)
                .superClass(classHeader.superName())
                .typeParameters(typeParameters(model.get()))
                .origin(origin)
                .source(source)
                .abstractType(classHeader.isAbstract() || classHeader.isInterface())
                .window(selfTypes.contains(ClasspathIndex.WINDOW) ? Boolean.TRUE : null)
                .constructors(constructors)
                .properties(properties)
                .events(events)
                .beanInfo(index.contains(className + "BeanInfo") ? Boolean.TRUE : null)
                .build();
        return Optional.of(descriptor);
    }

    private Optional<PropertyDescriptor> property(String property, List<Accessor> candidates,
                                                  Accessor getter, Set<String> selfTypes) {
        Accessor chosen = null;
        String getterType = getter == null ? null
                : ClassHeaders.typeName(getter.type().returnType());
        for (Accessor candidate : candidates) {
            if (candidate.type().parameterCount() != 1) {
                continue;
            }
            String parameterType = ClassHeaders.typeName(candidate.type().parameterType(0));
            if (getterType != null && getterType.equals(parameterType)) {
                chosen = candidate;
                break;
            }
            if (chosen == null) {
                chosen = candidate;
            }
        }
        boolean multi = false;
        if (chosen == null) {
            chosen = candidates.getFirst();
            multi = true;
        }
        MethodTypeDesc type = chosen.type();
        String propertyType = multi ? null : ClassHeaders.typeName(type.parameterType(0));
        SetterStyle style = type.returnType().descriptorString().equals("V")
                ? SetterStyle.VOID : SetterStyle.FLUENT;
        List<ParameterInfo> parameters = multi ? parameters(chosen.method(), type, false) : null;
        Accessor matchingGetter = getter != null && (propertyType == null
                || propertyType.equals(getterType)) ? getter : null;
        List<String> enumValues = propertyType == null ? null : enumOrNull(propertyType);
        return Optional.of(new PropertyDescriptor(property, propertyType, chosen.name(), style,
                parameters, matchingGetter == null ? null : matchingGetter.name(),
                enumValues == null ? null : "enum", enumValues, null, null, null, null,
                multi ? Boolean.TRUE : null, null, null));
    }

    private List<String> enumOrNull(String type) {
        List<String> constants = enumConstants(type);
        return constants.isEmpty() ? null : constants;
    }

    private Optional<EventDescriptor> event(String key, Accessor adder, Accessor remover) {
        String listenerType = ClassHeaders.typeName(adder.type().parameterType(0));
        Optional<ClassHeader> listener = index.header(listenerType);
        if (listener.isEmpty() || !listener.get().isInterface()) {
            return Optional.empty();
        }
        List<EventDescriptor.EventMethod> methods = new ArrayList<>();
        collectListenerMethods(listenerType, methods, new LinkedHashSet<>());
        return Optional.of(new EventDescriptor(listenerType, adder.name(),
                remover == null ? null : remover.name(), methods, adapterOf(listenerType, methods), null, null));
    }

    private String adapterOf(String listenerType, List<EventDescriptor.EventMethod> methods) {
        if (methods.size() < 2 || !listenerType.endsWith("Listener")) {
            return null;
        }
        String candidate = listenerType.substring(0, listenerType.length() - "Listener".length()) + "Adapter";
        Optional<ClassHeader> header = index.header(candidate);
        if (header.isEmpty() || header.get().isInterface()) {
            return null;
        }
        for (String type : index.superChain(candidate)) {
            Optional<ClassHeader> current = index.header(type);
            if (current.isPresent() && current.get().interfaces().contains(listenerType)) {
                return candidate;
            }
        }
        return null;
    }

    private void collectListenerMethods(String type, List<EventDescriptor.EventMethod> methods,
                                        Set<String> visited) {
        if (!visited.add(type) || type.equals(EVENT_LISTENER)) {
            return;
        }
        Optional<ClassModel> model = model(type);
        if (model.isEmpty()) {
            return;
        }
        for (MethodModel method : model.get().methods()) {
            int flags = method.flags().flagsMask();
            if ((flags & AccessFlag.ABSTRACT.mask()) == 0 || (flags & STATIC) != 0) {
                continue;
            }
            MethodTypeDesc methodType = method.methodTypeSymbol();
            String eventType = methodType.parameterCount() == 1
                    ? ClassHeaders.typeName(methodType.parameterType(0)) : null;
            List<String> parameterTypes = new ArrayList<>();
            for (int i = 0; i < methodType.parameterCount(); i++) {
                parameterTypes.add(ClassHeaders.typeName(methodType.parameterType(i)));
            }
            String name = method.methodName().stringValue();
            if (methods.stream().anyMatch(existing -> existing.name().equals(name)
                    && existing.parameterTypes().equals(parameterTypes))) {
                continue;
            }
            methods.add(new EventDescriptor.EventMethod(name, eventType, parameterTypes,
                    ClassHeaders.typeName(methodType.returnType())));
        }
        index.header(type).ifPresent(header -> header.interfaces()
                .forEach(parent -> collectListenerMethods(parent, methods, visited)));
    }

    private Optional<ClassModel> model(String className) {
        return models.computeIfAbsent(className, name -> index.bytes(name).flatMap(bytes -> {
            try {
                return Optional.of(ClassFile.of().parse(bytes));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }));
    }

    private static List<String> typeParameters(ClassModel model) {
        return model.findAttribute(Attributes.signature())
                .map(signature -> signature.asClassSignature().typeParameters().stream()
                        .map(Signature.TypeParam::identifier).toList())
                .filter(list -> !list.isEmpty())
                .orElse(null);
    }

    private static List<ParameterInfo> parameters(MethodModel method, MethodTypeDesc type,
                                                  boolean isStatic) {
        String[] names = new String[type.parameterCount()];
        Optional<MethodParametersAttribute> declared =
                method.findAttribute(Attributes.methodParameters());
        if (declared.isPresent()) {
            List<MethodParameterInfo> infos = declared.get().parameters();
            for (int i = 0; i < names.length && i < infos.size(); i++) {
                names[i] = infos.get(i).name().map(entry -> entry.stringValue()).orElse(null);
            }
        }
        if (method.code().isPresent() && hasMissing(names)) {
            Map<Integer, Integer> slotToParameter = new HashMap<>();
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < names.length; i++) {
                slotToParameter.put(slot, i);
                ClassDesc parameter = type.parameterType(i);
                String descriptor = parameter.descriptorString();
                slot += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
            }
            for (CodeElement element : method.code().get()) {
                if (element instanceof LocalVariable variable) {
                    Integer parameter = slotToParameter.get(variable.slot());
                    if (parameter != null && names[parameter] == null) {
                        names[parameter] = variable.name().stringValue();
                    }
                }
            }
        }
        List<ParameterInfo> parameters = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            String name = names[i] == null || names[i].isBlank() ? "arg" + i : names[i];
            parameters.add(new ParameterInfo(name, ClassHeaders.typeName(type.parameterType(i))));
        }
        return parameters;
    }

    private static boolean hasMissing(String[] names) {
        for (String name : names) {
            if (name == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeprecated(MethodModel method) {
        if (method.findAttribute(Attributes.deprecated()).isPresent()) {
            return true;
        }
        Optional<RuntimeVisibleAnnotationsAttribute> annotations =
                method.findAttribute(Attributes.runtimeVisibleAnnotations());
        return annotations.isPresent() && annotations.get().annotations().stream()
                .anyMatch(annotation -> annotation.className().stringValue().equals(DEPRECATED));
    }

    private static boolean isSetterName(String name) {
        return name.length() > 3 && name.startsWith("set") && Character.isUpperCase(name.charAt(3));
    }

    private static boolean isSetterReturn(ClassDesc returnType, Set<String> selfTypes) {
        String descriptor = returnType.descriptorString();
        if (descriptor.equals("V")) {
            return true;
        }
        if (returnType.isPrimitive() || returnType.isArray()) {
            return false;
        }
        return selfTypes.contains(ClassHeaders.typeName(returnType));
    }

    private static Optional<String> getterName(String name, MethodTypeDesc type) {
        String descriptor = type.returnType().descriptorString();
        if (descriptor.equals("V")) {
            return Optional.empty();
        }
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            return Optional.of(propertyName(name.substring(3)));
        }
        if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))
                && (descriptor.equals("Z") || descriptor.equals("Ljava/lang/Boolean;"))) {
            return Optional.of(propertyName(name.substring(2)));
        }
        return Optional.empty();
    }

    static String propertyName(String suffix) {
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0))
                && Character.isUpperCase(suffix.charAt(1))) {
            return suffix;
        }
        return suffix.substring(0, 1).toLowerCase(Locale.ROOT) + suffix.substring(1);
    }

    private record Accessor(String name, MethodTypeDesc type, MethodModel method) {
    }
}
