package dtm.ide.swingdesigner.form;

import java.util.Locale;

public final class FormNames {

    private FormNames() {
    }

    public static String component(FormModel model, String className) {
        String simple = simpleName(className);
        if (simple.length() > 1 && simple.charAt(0) == 'J' && Character.isUpperCase(simple.charAt(1))) {
            simple = simple.substring(1);
        }
        String base = decapitalize(simple);
        int counter = 1;
        while (model.hasMember(base + counter)) {
            counter++;
        }
        return base + counter;
    }

    public static String handler(FormModel model, FormComponent component, String listenerMethod) {
        String owner = component == null || component.isRoot() || component.kind() == FormComponent.Kind.CONTENT
                ? "form" : component.name();
        String base = owner + capitalize(listenerMethod);
        if (!model.hasMember(base)) {
            return base;
        }
        int counter = 2;
        while (model.hasMember(base + counter)) {
            counter++;
        }
        return base + counter;
    }

    public static String defaultText(String className) {
        String simple = simpleName(className);
        if (simple.length() > 1 && simple.charAt(0) == 'J' && Character.isUpperCase(simple.charAt(1))) {
            simple = simple.substring(1);
        }
        return simple;
    }

    public static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !javax.lang.model.SourceVersion.isKeyword(name);
    }

    public static String simpleName(String className) {
        String clean = className.replace('$', '.');
        return clean.substring(clean.lastIndexOf('.') + 1);
    }

    public static String capitalize(String text) {
        return text == null || text.isEmpty() ? "" : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    public static String decapitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int upper = 0;
        while (upper < text.length() && Character.isUpperCase(text.charAt(upper))) {
            upper++;
        }
        if (upper <= 1) {
            return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
        }
        int cut = upper == text.length() ? upper : upper - 1;
        return text.substring(0, cut).toLowerCase(Locale.ROOT) + text.substring(cut);
    }
}
