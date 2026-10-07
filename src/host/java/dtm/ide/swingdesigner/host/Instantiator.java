package dtm.ide.swingdesigner.host;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class Instantiator {

    private final ClassLoader loader;
    private final boolean stubs;

    Instantiator(ClassLoader loader, boolean stubs) {
        this.loader = loader;
        this.stubs = stubs;
    }

    Result instantiate(Class<?> type, List<String> explicitTypes, List<Object> explicitValues,
                       String explicitFactory) {
        Result result = new Result();
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
            result.attempts.add(type.getName() + " e abstrata e nao pode ser instanciada");
            return result;
        }
        if (explicitTypes != null) {
            if (tryExplicit(type, explicitTypes, explicitValues, explicitFactory, result)) {
                return result;
            }
        }
        for (Constructor<?> constructor : constructors(type)) {
            Object[] arguments = synthesize(constructor.getParameterTypes());
            if (tryInvoke(constructor, null, arguments, result)) {
                return result;
            }
        }
        for (Method factory : factories(type)) {
            Object[] arguments = synthesize(factory.getParameterTypes());
            if (tryInvoke(null, factory, arguments, result)) {
                return result;
            }
        }
        if (result.attempts.isEmpty()) {
            result.attempts.add(type.getName() + " nao tem construtor publico nem factory estatico");
        }
        return result;
    }

    private boolean tryExplicit(Class<?> type, List<String> typeNames, List<Object> values,
                                String factoryName, Result result) {
        try {
            Class<?>[] parameterTypes = new Class<?>[typeNames.size()];
            Object[] arguments = new Object[typeNames.size()];
            for (int i = 0; i < parameterTypes.length; i++) {
                parameterTypes[i] = Values.type(typeNames.get(i), loader);
                Object json = values != null && i < values.size() ? values.get(i) : null;
                arguments[i] = values != null && i < values.size()
                        ? Values.toJava(json, parameterTypes[i], loader)
                        : synthetic(parameterTypes[i]);
            }
            if (factoryName != null) {
                Method factory = type.getMethod(factoryName, parameterTypes);
                return tryInvoke(null, factory, arguments, result);
            }
            Constructor<?> constructor = type.getConstructor(parameterTypes);
            return tryInvoke(constructor, null, arguments, result);
        } catch (Throwable error) {
            result.attempts.add("Construtor escolhido " + typeNames + ": " + describe(error));
            return false;
        }
    }

    private boolean tryInvoke(Constructor<?> constructor, Method factory, Object[] arguments,
                              Result result) {
        Class<?>[] parameterTypes = constructor != null ? constructor.getParameterTypes()
                : factory.getParameterTypes();
        String label = constructor != null ? signature(constructor.getDeclaringClass().getSimpleName(), parameterTypes)
                : signature(factory.getName(), parameterTypes);
        try {
            Object instance = constructor != null ? constructor.newInstance(arguments)
                    : factory.invoke(null, arguments);
            if (instance == null) {
                result.attempts.add(label + ": retornou null");
                return false;
            }
            result.instance = instance;
            result.factory = factory == null ? null : factory.getName();
            for (Class<?> parameterType : parameterTypes) {
                result.parameterTypes.add(typeName(parameterType));
            }
            for (Object argument : arguments) {
                result.argumentValues.add(Values.toJson(argument));
            }
            return true;
        } catch (Throwable error) {
            result.attempts.add(label + ": " + describe(error) + Lifecycle.location(rootCause(error)));
            result.failure = rootCause(error);
            return false;
        }
    }

    private Object[] synthesize(Class<?>[] types) {
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            arguments[i] = synthetic(types[i]);
        }
        return arguments;
    }

    private Object synthetic(Class<?> type) {
        Object value = Values.syntheticDefault(type, loader, 0);
        if (value == null && stubs && DesignStubs.canStub(type)) {
            return DesignStubs.create(type, loader);
        }
        return value;
    }

    private static List<Constructor<?>> constructors(Class<?> type) {
        List<Constructor<?>> constructors = new ArrayList<Constructor<?>>(Arrays.asList(type.getConstructors()));
        Collections.sort(constructors, new Comparator<Constructor<?>>() {
            @Override
            public int compare(Constructor<?> left, Constructor<?> right) {
                return Integer.compare(left.getParameterTypes().length, right.getParameterTypes().length);
            }
        });
        return constructors;
    }

    private static List<Method> factories(Class<?> type) {
        List<Method> factories = new ArrayList<Method>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && type.isAssignableFrom(method.getReturnType())
                    && method.getDeclaringClass() == type) {
                factories.add(method);
            }
        }
        Collections.sort(factories, new Comparator<Method>() {
            @Override
            public int compare(Method left, Method right) {
                return Integer.compare(left.getParameterTypes().length, right.getParameterTypes().length);
            }
        });
        return factories;
    }

    static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    private static String signature(String name, Class<?>[] types) {
        StringBuilder out = new StringBuilder(name).append('(');
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(types[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ExceptionInInitializerError && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String describe(Throwable error) {
        Throwable cause = rootCause(error);
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null ? "" : ": " + message);
    }

    static final class Result {
        Object instance;
        String factory;
        Throwable failure;
        final List<String> parameterTypes = new ArrayList<String>();
        final List<Object> argumentValues = new ArrayList<Object>();
        final List<String> attempts = new ArrayList<String>();
    }
}
