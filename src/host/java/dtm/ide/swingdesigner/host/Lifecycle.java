package dtm.ide.swingdesigner.host;

import javax.swing.JRootPane;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

final class Lifecycle {

    private Lifecycle() {
    }

    static void run(Object instance, List<String> methods, List<Object> warnings) {
        for (String name : methods) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            Method method = find(instance.getClass(), name.trim(), 0);
            if (method == null) {
                warnings.add(Warnings.info("designInit: metodo " + name + "() nao encontrado em "
                        + instance.getClass().getName()));
                continue;
            }
            invoke(instance, method, new Object[0], "designInit", warnings);
        }
    }

    static boolean looksEmpty(Component root) {
        if (!(root instanceof JRootPane)) {
            return false;
        }
        JRootPane rootPane = (JRootPane) root;
        return rootPane.getJMenuBar() == null && rootPane.getContentPane().getComponentCount() == 0;
    }

    static String location(Throwable error) {
        if (error == null) {
            return "";
        }
        for (StackTraceElement frame : error.getStackTrace()) {
            String owner = frame.getClassName();
            if (Warnings.isInfrastructure(owner)) {
                continue;
            }
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            String file = frame.getFileName() == null ? "" : frame.getFileName()
                    + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
            return " em " + simple + "." + frame.getMethodName() + (file.isEmpty() ? "" : "(" + file + ")");
        }
        return "";
    }

    private static boolean invoke(Object instance, Method method, Object[] arguments, String origin,
                                  List<Object> warnings) {
        try {
            method.setAccessible(true);
            method.invoke(instance, arguments);
            return true;
        } catch (InvocationTargetException error) {
            Throwable cause = Instantiator.rootCause(error);
            Map<String, Object> warning = Warnings.error(origin, method.getName() + "() lancou "
                    + Instantiator.describe(cause), cause, instance);
            warning.put("method", method.getName());
            warnings.add(warning);
            return false;
        } catch (Throwable error) {
            warnings.add(Warnings.info("Nao foi possivel chamar " + method.getName() + "(): "
                    + Instantiator.describe(error)));
            return false;
        }
    }

    private static Method find(Class<?> type, String name, int arity) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == arity
                        && !method.isBridge()) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
