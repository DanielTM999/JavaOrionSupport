package dtm.ide.swingdesigner.host;

import javax.swing.JRootPane;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

final class Lifecycle {

    private Lifecycle() {
    }

    static void run(Object instance, List<String> methods, List<String> warnings) {
        for (String name : methods) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            Method method = find(instance.getClass(), name.trim());
            if (method == null) {
                warnings.add("designInit: metodo " + name + "() nao encontrado em "
                        + instance.getClass().getName());
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(instance);
            } catch (InvocationTargetException error) {
                Throwable cause = Instantiator.rootCause(error);
                warnings.add(name + "() lancou " + Instantiator.describe(cause) + location(cause)
                        + ". A tela foi renderizada ate esse ponto.");
            } catch (Throwable error) {
                warnings.add("designInit: nao foi possivel chamar " + name + "(): "
                        + Instantiator.describe(error));
            }
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
            if (owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("sun.")
                    || owner.startsWith("jdk.") || owner.startsWith("com.sun.")
                    || owner.startsWith("dtm.ide.swingdesigner.host.")) {
                continue;
            }
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            String file = frame.getFileName() == null ? "" : frame.getFileName()
                    + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
            return " em " + simple + "." + frame.getMethodName() + (file.isEmpty() ? "" : "(" + file + ")");
        }
        return "";
    }

    private static Method find(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
