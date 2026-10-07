package dtm.ide.swingdesigner.host;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Interpreter {

    private final Object self;
    private final ClassLoader loader;
    private final boolean stubs;
    private final Map<String, Object> locals = new LinkedHashMap<String, Object>();
    private final Map<String, List<Object>> priorLocals = new LinkedHashMap<String, List<Object>>();
    private final List<Object> warnings;
    private final List<String> synthesized = new ArrayList<String>();

    Interpreter(Object self, ClassLoader loader, boolean stubs, List<Object> warnings) {
        this.self = self;
        this.loader = loader;
        this.stubs = stubs;
        this.warnings = warnings;
    }

    void bind(String name, Object value) {
        locals.put(name, value);
    }

    void declarePrior(String name, List<Object> typeCandidates) {
        priorLocals.put(name, typeCandidates);
    }

    List<String> synthesized() {
        return synthesized;
    }

    Map<String, Object> run(Map<String, Object> statement) {
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        String kind = Json.string(statement, "s");
        String text = Json.string(statement, "text");
        outcome.put("line", Json.integer(statement, "line", -1));
        outcome.put("text", text);
        try {
            if ("local".equals(kind)) {
                runLocal(statement);
            } else if ("assign".equals(kind)) {
                assign(Json.object(statement.get("target")), eval(statement.get("value")));
            } else if ("expr".equals(kind)) {
                eval(statement.get("expr"));
            } else {
                outcome.put("status", "skipped");
                return outcome;
            }
            outcome.put("status", "ok");
        } catch (Throwable error) {
            Throwable cause = Instantiator.rootCause(error);
            if ("local".equals(kind)) {
                String name = Json.string(statement, "name");
                Object fallback = designDefault(Json.array(statement.get("type")));
                locals.put(name, fallback);
                synthesized.add(name + " (" + typeLabel(fallback, statement) + ")");
                outcome.put("status", "synthesized");
            } else {
                outcome.put("status", "failed");
            }
            Map<String, Object> warning = Warnings.error("interpret", "Linha " + Json.integer(statement, "line", -1)
                    + ": " + text + " lancou " + Instantiator.describe(cause), cause, self);
            warnings.add(warning);
        }
        return outcome;
    }

    private void runLocal(Map<String, Object> statement) throws Throwable {
        String name = Json.string(statement, "name");
        if (Json.bool(statement, "synthetic", false) || !statement.containsKey("init")
                || statement.get("init") == null) {
            Object fallback = designDefault(Json.array(statement.get("type")));
            locals.put(name, fallback);
            synthesized.add(name + " (" + typeLabel(fallback, statement) + ")");
            return;
        }
        locals.put(name, eval(statement.get("init")));
    }

    private String typeLabel(Object value, Map<String, Object> statement) {
        if (value != null) {
            return value.getClass().getSimpleName();
        }
        List<Object> types = Json.array(statement.get("type"));
        return types.isEmpty() ? "null" : simple(String.valueOf(types.get(0))) + " = null";
    }

    private void assign(Map<String, Object> target, Object value) throws Throwable {
        String local = Json.string(target, "local");
        if (local != null) {
            locals.put(local, value);
            return;
        }
        String fieldName = Json.string(target, "field");
        Field field = DesignInjector.field(self.getClass(), fieldName);
        if (field == null) {
            locals.put(fieldName, value);
            return;
        }
        field.setAccessible(true);
        field.set(self, coerce(value, field.getType()));
    }

    private Object eval(Object node) throws Throwable {
        Map<String, Object> expr = Json.object(node);
        String kind = Json.string(expr, "e");
        if ("lit".equals(kind)) {
            return literal(expr);
        }
        if ("this".equals(kind)) {
            return self;
        }
        if ("name".equals(kind)) {
            return name(expr);
        }
        if ("select".equals(kind)) {
            return select(expr);
        }
        if ("call".equals(kind)) {
            return call(expr);
        }
        if ("new".equals(kind)) {
            return construct(expr);
        }
        if ("cast".equals(kind)) {
            return eval(expr.get("x"));
        }
        if ("unary".equals(kind)) {
            Object value = eval(expr.get("x"));
            String op = Json.string(expr, "op");
            if ("-".equals(op) && value instanceof Number) {
                return negate((Number) value);
            }
            if ("!".equals(op) && value instanceof Boolean) {
                return !((Boolean) value);
            }
            throw new UnsupportedOperationException("operador " + op);
        }
        if ("binary".equals(kind)) {
            return binary(Json.string(expr, "op"), eval(expr.get("l")), eval(expr.get("r")));
        }
        throw new UnsupportedOperationException("expressao " + kind);
    }

    private Object literal(Map<String, Object> expr) {
        Object value = expr.get("v");
        String type = Json.string(expr, "t");
        if (value instanceof Number && type != null) {
            Number number = (Number) value;
            if ("double".equals(type)) {
                return number.doubleValue();
            }
            if ("float".equals(type)) {
                return number.floatValue();
            }
            if ("long".equals(type)) {
                return number.longValue();
            }
            return number.intValue();
        }
        if ("char".equals(type) && value instanceof String && !((String) value).isEmpty()) {
            return ((String) value).charAt(0);
        }
        return value;
    }

    private Object name(Map<String, Object> expr) throws Throwable {
        String name = Json.string(expr, "name");
        if (locals.containsKey(name)) {
            return locals.get(name);
        }
        if (priorLocals.containsKey(name)) {
            Object fallback = designDefault(priorLocals.get(name));
            locals.put(name, fallback);
            synthesized.add(name + " (" + (fallback == null ? "null" : fallback.getClass().getSimpleName()) + ")");
            return fallback;
        }
        Field field = DesignInjector.field(self.getClass(), name);
        if (field != null) {
            field.setAccessible(true);
            return field.get(Modifier.isStatic(field.getModifiers()) ? null : self);
        }
        Class<?> type = resolve(Json.array(expr.get("classes")));
        if (type != null) {
            return new ClassRef(type);
        }
        throw new IllegalStateException("Nome desconhecido no design-time: " + name);
    }

    private Object select(Map<String, Object> expr) throws Throwable {
        Object target = eval(expr.get("target"));
        String name = Json.string(expr, "name");
        if (target instanceof ClassRef) {
            Class<?> owner = ((ClassRef) target).type;
            for (Class<?> nested : owner.getClasses()) {
                if (nested.getSimpleName().equals(name)) {
                    return new ClassRef(nested);
                }
            }
            Field field = findField(owner, name);
            field.setAccessible(true);
            return field.get(null);
        }
        if (target == null) {
            throw new NullPointerException("Acesso a " + name + " em valor null");
        }
        if (target.getClass().isArray() && "length".equals(name)) {
            return Array.getLength(target);
        }
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private Object call(Map<String, Object> expr) throws Throwable {
        String name = Json.string(expr, "name");
        Object[] arguments = arguments(Json.array(expr.get("args")));
        Object target;
        boolean statically = false;
        Class<?> owner;
        if (expr.get("target") == null) {
            target = self;
            owner = self.getClass();
        } else {
            target = eval(expr.get("target"));
            if (target instanceof ClassRef) {
                owner = ((ClassRef) target).type;
                statically = true;
            } else if (target == null) {
                throw new NullPointerException("Chamada " + name + "() em valor null");
            } else {
                owner = target.getClass();
            }
        }
        Method method = findMethod(owner, name, arguments, statically);
        Object[] actual = adapt(method.getParameterTypes(), method.isVarArgs(), arguments);
        method.setAccessible(true);
        try {
            return method.invoke(statically ? null : target, actual);
        } catch (InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    private Object construct(Map<String, Object> expr) throws Throwable {
        Class<?> type = resolve(Json.array(expr.get("type")));
        if (type == null) {
            throw new ClassNotFoundException(String.valueOf(expr.get("type")));
        }
        Object[] arguments = arguments(Json.array(expr.get("args")));
        Constructor<?> chosen = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (accepts(constructor.getParameterTypes(), constructor.isVarArgs(), arguments)) {
                chosen = constructor;
                break;
            }
        }
        if (chosen == null) {
            throw new NoSuchMethodException("Construtor de " + type.getName() + " com " + arguments.length
                    + " argumento(s)");
        }
        chosen.setAccessible(true);
        try {
            return chosen.newInstance(adapt(chosen.getParameterTypes(), chosen.isVarArgs(), arguments));
        } catch (InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    private Object[] arguments(List<Object> nodes) throws Throwable {
        Object[] values = new Object[nodes.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = eval(nodes.get(i));
            if (values[i] instanceof ClassRef) {
                throw new IllegalArgumentException("Classe usada como valor");
            }
        }
        return values;
    }

    private Object designDefault(List<Object> typeCandidates) {
        Class<?> type = resolve(typeCandidates);
        if (type == null) {
            return null;
        }
        Object value = Values.syntheticDefault(type, loader, 0);
        if (value == null && stubs && DesignStubs.canStub(type)) {
            value = DesignStubs.create(type, loader);
        }
        return value;
    }

    private Class<?> resolve(List<Object> candidates) {
        for (Object candidate : candidates) {
            try {
                return Values.type(String.valueOf(candidate), loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> owner, String name, Object[] arguments, boolean statically)
            throws NoSuchMethodException {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method method : candidates(owner, name)) {
            if (statically && !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            int score = score(method.getParameterTypes(), method.isVarArgs(), arguments);
            if (score >= 0 && score < bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " com " + arguments.length
                    + " argumento(s)");
        }
        return best;
    }

    private static List<Method> candidates(Class<?> owner, String name) {
        List<Method> methods = new ArrayList<Method>();
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && !method.isBridge()) {
                methods.add(method);
            }
        }
        Class<?> current = owner;
        while (current != null && current != Object.class) {
            if (!Snapshots.isJdk(current)) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals(name) && !method.isBridge()) {
                        methods.add(method);
                    }
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException ignored) {
        }
        Field field = DesignInjector.field(owner, name);
        if (field == null) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
        return field;
    }

    private static boolean accepts(Class<?>[] types, boolean varArgs, Object[] arguments) {
        return score(types, varArgs, arguments) >= 0;
    }

    private static int score(Class<?>[] types, boolean varArgs, Object[] arguments) {
        if (types.length == arguments.length) {
            int total = 0;
            boolean fits = true;
            for (int i = 0; i < types.length; i++) {
                int distance = distance(types[i], arguments[i]);
                if (distance < 0) {
                    fits = false;
                    break;
                }
                total += distance;
            }
            if (fits) {
                return total;
            }
        }
        if (varArgs && arguments.length >= types.length - 1) {
            int total = 100;
            for (int i = 0; i < types.length - 1; i++) {
                int distance = distance(types[i], arguments[i]);
                if (distance < 0) {
                    return -1;
                }
                total += distance;
            }
            Class<?> component = types[types.length - 1].getComponentType();
            for (int i = types.length - 1; i < arguments.length; i++) {
                int distance = distance(component, arguments[i]);
                if (distance < 0) {
                    return -1;
                }
                total += distance;
            }
            return total;
        }
        return -1;
    }

    private static int distance(Class<?> parameter, Object argument) {
        if (argument == null) {
            return parameter.isPrimitive() ? -1 : 1;
        }
        Class<?> wrapped = wrap(parameter);
        if (wrapped.isInstance(argument)) {
            return wrapped == argument.getClass() ? 0 : 2;
        }
        if (argument instanceof Number && isNumeric(wrapped)) {
            return widening(argument.getClass(), wrapped);
        }
        if (argument instanceof Character && (wrapped == Integer.class || wrapped == Long.class)) {
            return 3;
        }
        return -1;
    }

    private static int widening(Class<?> from, Class<?> to) {
        List<Class<?>> order = new ArrayList<Class<?>>();
        order.add(Byte.class);
        order.add(Short.class);
        order.add(Integer.class);
        order.add(Long.class);
        order.add(Float.class);
        order.add(Double.class);
        int source = order.indexOf(from);
        int target = order.indexOf(to);
        if (source < 0 || target < 0) {
            return -1;
        }
        return target >= source ? 1 + target - source : -1;
    }

    private static Object[] adapt(Class<?>[] types, boolean varArgs, Object[] arguments) {
        boolean direct = types.length == arguments.length;
        if (direct) {
            for (int i = 0; i < types.length; i++) {
                if (distance(types[i], arguments[i]) < 0) {
                    direct = false;
                    break;
                }
            }
        }
        if (direct || !varArgs) {
            Object[] adapted = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                adapted[i] = coerce(arguments[i], types[i]);
            }
            return adapted;
        }
        Object[] adapted = new Object[types.length];
        for (int i = 0; i < types.length - 1; i++) {
            adapted[i] = coerce(arguments[i], types[i]);
        }
        Class<?> component = types[types.length - 1].getComponentType();
        Object rest = Array.newInstance(component, arguments.length - types.length + 1);
        for (int i = types.length - 1; i < arguments.length; i++) {
            Array.set(rest, i - types.length + 1, coerce(arguments[i], component));
        }
        adapted[types.length - 1] = rest;
        return adapted;
    }

    private static Object coerce(Object value, Class<?> type) {
        if (!(value instanceof Number)) {
            return value;
        }
        Number number = (Number) value;
        Class<?> wrapped = wrap(type);
        if (wrapped == Double.class) {
            return number.doubleValue();
        }
        if (wrapped == Float.class) {
            return number.floatValue();
        }
        if (wrapped == Long.class) {
            return number.longValue();
        }
        if (wrapped == Integer.class) {
            return number.intValue();
        }
        if (wrapped == Short.class) {
            return number.shortValue();
        }
        if (wrapped == Byte.class) {
            return number.byteValue();
        }
        return value;
    }

    private static Object binary(String op, Object left, Object right) {
        if ("+".equals(op) && (left instanceof String || right instanceof String)) {
            return String.valueOf(left) + String.valueOf(right);
        }
        if (left instanceof Number && right instanceof Number) {
            Number a = (Number) left;
            Number b = (Number) right;
            boolean decimal = a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float;
            boolean wide = a instanceof Long || b instanceof Long;
            if ("+".equals(op)) {
                return decimal ? (Object) (a.doubleValue() + b.doubleValue())
                        : wide ? (Object) (a.longValue() + b.longValue()) : (Object) (a.intValue() + b.intValue());
            }
            if ("-".equals(op)) {
                return decimal ? (Object) (a.doubleValue() - b.doubleValue())
                        : wide ? (Object) (a.longValue() - b.longValue()) : (Object) (a.intValue() - b.intValue());
            }
            if ("*".equals(op)) {
                return decimal ? (Object) (a.doubleValue() * b.doubleValue())
                        : wide ? (Object) (a.longValue() * b.longValue()) : (Object) (a.intValue() * b.intValue());
            }
            if ("/".equals(op)) {
                return decimal ? (Object) (a.doubleValue() / b.doubleValue())
                        : wide ? (Object) (a.longValue() / b.longValue()) : (Object) (a.intValue() / b.intValue());
            }
        }
        throw new UnsupportedOperationException("operador " + op);
    }

    private static Number negate(Number value) {
        if (value instanceof Double) {
            return -value.doubleValue();
        }
        if (value instanceof Float) {
            return -value.floatValue();
        }
        if (value instanceof Long) {
            return -value.longValue();
        }
        return -value.intValue();
    }

    private static boolean isNumeric(Class<?> type) {
        return Number.class.isAssignableFrom(type) && type != Number.class
                && type.getName().startsWith("java.lang.");
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        return Void.class;
    }

    private static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    static final class ClassRef {
        final Class<?> type;

        ClassRef(Class<?> type) {
            this.type = type;
        }
    }
}
