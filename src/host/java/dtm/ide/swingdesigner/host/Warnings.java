package dtm.ide.swingdesigner.host;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Warnings {

    static final String ERROR = "error";
    static final String INFO = "info";

    private static final Pattern NULL_FIELD = Pattern.compile("\"this\\.([A-Za-z_$][\\w$]*)\" is null");
    private static final int MAX_LISTED = 8;

    private Warnings() {
    }

    static Map<String, Object> info(String message) {
        Map<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("kind", INFO);
        warning.put("message", message);
        return warning;
    }

    static Map<String, Object> error(String origin, String message, Throwable failure, Object instance) {
        Throwable cause = Instantiator.rootCause(failure);
        Map<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("kind", ERROR);
        warning.put("origin", origin);
        warning.put("message", message);
        if (cause != null) {
            warning.put("exception", Instantiator.describe(cause));
            warning.put("frames", frames(cause));
            warning.put("stack", stackTrace(cause));
            String hint = hint(cause, instance);
            if (hint != null) {
                warning.put("hint", hint);
            }
        }
        return warning;
    }

    static Map<String, Object> listing(String prefix, List<String> items) {
        StringBuilder text = new StringBuilder(prefix);
        int shown = Math.min(items.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            text.append(i == 0 ? " " : ", ").append(items.get(i));
        }
        if (items.size() > shown) {
            text.append(" e mais ").append(items.size() - shown);
        }
        return info(text.toString());
    }

    static List<Object> frames(Throwable error) {
        List<Object> frames = new ArrayList<Object>();
        for (StackTraceElement frame : error.getStackTrace()) {
            String owner = frame.getClassName();
            if (isInfrastructure(owner)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("className", owner);
            entry.put("method", frame.getMethodName());
            if (frame.getFileName() != null) {
                entry.put("file", frame.getFileName());
            }
            entry.put("line", frame.getLineNumber());
            frames.add(entry);
        }
        return frames;
    }

    static boolean isInfrastructure(String owner) {
        return owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("sun.")
                || owner.startsWith("jdk.") || owner.startsWith("com.sun.")
                || owner.startsWith("dtm.ide.swingdesigner.host.");
    }

    private static String hint(Throwable cause, Object instance) {
        if (!(cause instanceof NullPointerException) || cause.getMessage() == null || instance == null) {
            return null;
        }
        Matcher matcher = NULL_FIELD.matcher(cause.getMessage());
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1);
        Field field = DesignInjector.field(instance.getClass(), name);
        if (field == null) {
            return null;
        }
        String type = field.getType().getSimpleName();
        if (field.getType().isInterface()) {
            return "O campo " + name + " (" + type + ") esta null no design-time. Ative os stubs ou informe um"
                    + " valor em designValues no .orion/swing-components.json.";
        }
        return "O campo " + name + " (" + type + ") esta null no design-time e so interfaces podem ser simuladas."
                + " Informe um valor em designValues ou proteja o trecho com java.beans.Beans.isDesignTime().";
    }

    private static String stackTrace(Throwable error) {
        java.io.StringWriter out = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
