package dtm.ide.swingdesigner.host;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class DesignStubs {

    private static final int MAX_DEPTH = 3;

    private DesignStubs() {
    }

    static boolean canStub(Class<?> type) {
        return type.isInterface() && !type.isAnnotation();
    }

    static Object create(Class<?> type, ClassLoader loader) {
        return create(type, loader, 0);
    }

    private static Object create(final Class<?> type, final ClassLoader loader, final int depth) {
        Object collection = emptyCollection(type);
        if (collection != null) {
            return collection;
        }
        if (!canStub(type)) {
            return null;
        }
        ClassLoader owner = type.getClassLoader() == null ? loader : type.getClassLoader();
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                int arity = arguments == null ? 0 : arguments.length;
                if ("equals".equals(name) && arity == 1) {
                    return proxy == arguments[0];
                }
                if ("hashCode".equals(name) && arity == 0) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name) && arity == 0) {
                    return "DesignStub[" + type.getSimpleName() + "]";
                }
                return defaultFor(method.getReturnType(), loader, depth + 1);
            }
        };
        try {
            return Proxy.newProxyInstance(owner, new Class<?>[]{type}, handler);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object defaultFor(Class<?> type, ClassLoader loader, int depth) {
        if (type == void.class) {
            return null;
        }
        if (type.isPrimitive()) {
            return Values.syntheticDefault(type, loader, 2);
        }
        if (type == String.class || type == CharSequence.class) {
            return "";
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        Object collection = emptyCollection(type);
        if (collection != null) {
            return collection;
        }
        if (type.getName().equals("java.util.stream.Stream")) {
            return java.util.stream.Stream.empty();
        }
        if (canStub(type) && depth < MAX_DEPTH) {
            return create(type, loader, depth);
        }
        return null;
    }

    private static Object emptyCollection(Class<?> type) {
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            return new ArrayList<Object>();
        }
        if (type == Set.class) {
            return new LinkedHashSet<Object>();
        }
        if (type == Map.class) {
            return new LinkedHashMap<Object, Object>();
        }
        if (type == java.util.Iterator.class) {
            return Collections.emptyIterator();
        }
        return null;
    }
}
