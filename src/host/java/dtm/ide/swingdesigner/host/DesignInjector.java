package dtm.ide.swingdesigner.host;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DesignInjector {

    private final List<Rule> rules = new ArrayList<Rule>();
    private final Map<String, Object> designValues;
    private final boolean stubs;
    private final ClassLoader loader;

    DesignInjector(List<Object> injections, Map<String, Object> designValues, boolean stubs, ClassLoader loader) {
        for (Object entry : injections) {
            Map<String, Object> rule = Json.object(entry);
            String annotation = Json.string(rule, "annotation");
            if (annotation == null || annotation.trim().isEmpty()) {
                continue;
            }
            String attribute = Json.string(rule, "attribute");
            String pattern = Json.string(rule, "pattern");
            rules.add(new Rule(annotation.trim(), attribute == null ? "value" : attribute,
                    pattern == null || pattern.isEmpty() ? null : Pattern.compile(pattern)));
        }
        this.designValues = designValues;
        this.stubs = stubs;
        this.loader = loader;
    }

    void apply(Object instance, List<Object> warnings) {
        List<String> injected = new ArrayList<String>();
        List<String> stubbed = new ArrayList<String>();
        Class<?> type = instance.getClass();
        while (type != null && !Snapshots.isJdk(type)) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    applyTo(instance, field, injected, stubbed, warnings);
                } catch (Throwable error) {
                    warnings.add(Warnings.info("Nao foi possivel preparar o campo " + field.getName() + ": "
                            + Instantiator.describe(error)));
                }
            }
            type = type.getSuperclass();
        }
        if (!injected.isEmpty()) {
            warnings.add(Warnings.listing("Valores de design aplicados:", injected));
        }
        if (!stubbed.isEmpty()) {
            warnings.add(Warnings.listing("Dependencias simuladas para o design:", stubbed));
        }
    }

    private void applyTo(Object instance, Field field, List<String> injected, List<String> stubbed,
                         List<Object> warnings) throws Exception {
        String name = field.getName();
        Class<?> fieldType = field.getType();
        if (designValues.containsKey(name)) {
            Object value = designValues.get(name);
            Object converted = value instanceof String ? Values.fromText((String) value, fieldType, loader)
                    : Values.toJava(value, fieldType, loader);
            field.set(instance, converted);
            injected.add(name + "=" + Values.safeToString(converted));
            return;
        }
        Object current = field.get(instance);
        boolean unset = current == null || fieldType.isPrimitive();
        if (unset) {
            for (Rule rule : rules) {
                String raw = rule.defaultFor(field);
                if (raw == null) {
                    continue;
                }
                Object converted = Values.fromText(raw, fieldType, loader);
                if (converted != null || !fieldType.isPrimitive()) {
                    field.set(instance, converted);
                    injected.add(name + "=" + Values.safeToString(converted));
                    return;
                }
            }
        }
        if (current == null && stubs && !fieldType.isPrimitive()) {
            Object stub = DesignStubs.create(fieldType, loader);
            if (stub != null) {
                field.set(instance, stub);
                stubbed.add(name + " (" + fieldType.getSimpleName() + ")");
            }
        }
    }

    static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static final class Rule {
        private final String annotation;
        private final String attribute;
        private final Pattern pattern;

        Rule(String annotation, String attribute, Pattern pattern) {
            this.annotation = annotation;
            this.attribute = attribute;
            this.pattern = pattern;
        }

        String defaultFor(Field field) {
            for (Annotation present : field.getAnnotations()) {
                if (!present.annotationType().getName().equals(annotation)) {
                    continue;
                }
                try {
                    Method method = present.annotationType().getMethod(attribute);
                    method.setAccessible(true);
                    Object value = method.invoke(present);
                    if (value == null) {
                        return null;
                    }
                    String text = String.valueOf(value);
                    if (pattern == null) {
                        return text.isEmpty() && !field.getType().equals(String.class) ? null : text;
                    }
                    Matcher matcher = pattern.matcher(text);
                    return matcher.find() && matcher.groupCount() >= 1 ? matcher.group(1) : null;
                } catch (Throwable error) {
                    return null;
                }
            }
            return null;
        }
    }
}
