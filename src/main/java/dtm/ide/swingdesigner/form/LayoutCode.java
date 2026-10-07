package dtm.ide.swingdesigner.form;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.swingdesigner.catalog.LayoutDescriptor;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LayoutCode {

    public static final String ABSOLUTE = "null";
    public static final String GRID_BAG = "java.awt.GridBagLayout";
    public static final String BORDER = "java.awt.BorderLayout";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([\\w.]+)}");
    private static final Pattern QUALIFIED = Pattern.compile(
            "(?<![\\w.])((?:[a-z_][\\w]*\\.)+[A-Z][\\w$]*)");

    private LayoutCode() {
    }

    public static String constructor(LayoutDescriptor layout, Map<String, String> values, String target,
                                     UnaryOperator<String> use) {
        if (layout == null) {
            return "null";
        }
        com.fasterxml.jackson.databind.node.ObjectNode current =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        Map<String, String> literal = new java.util.LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                literal.put(name, value.trim());
            }
        });
        String template = layout.constructorTemplate() == null ? "new " + layout.className() + "()"
                : layout.constructorTemplate();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = name.equals("target") ? target
                    : literal.containsKey(name) ? literal.get(name) : valueOf(layout, name, current);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return shorten(expanded.toString(), use);
    }

    public static String constructor(LayoutDescriptor layout, JsonNode current, String target,
                                     UnaryOperator<String> use) {
        if (layout == null) {
            return "null";
        }
        String template = layout.constructorTemplate() == null ? "new " + layout.className() + "()"
                : layout.constructorTemplate();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value;
            if (name.equals("target")) {
                value = target;
            } else {
                value = valueOf(layout, name, current);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return shorten(expanded.toString(), use);
    }

    public static String gridBag(Map<String, String> values, UnaryOperator<String> use) {
        String constraints = use.apply("java.awt.GridBagConstraints");
        String insets = values.getOrDefault("insets", "new java.awt.Insets(0, 0, 0, 0)");
        String code = "new " + constraints + "("
                + values.getOrDefault("gridx", "java.awt.GridBagConstraints.RELATIVE") + ", "
                + values.getOrDefault("gridy", "java.awt.GridBagConstraints.RELATIVE") + ", "
                + values.getOrDefault("gridwidth", "1") + ", "
                + values.getOrDefault("gridheight", "1") + ", "
                + decimal(values.getOrDefault("weightx", "0.0")) + ", "
                + decimal(values.getOrDefault("weighty", "0.0")) + ", "
                + values.getOrDefault("anchor", "java.awt.GridBagConstraints.CENTER") + ", "
                + values.getOrDefault("fill", "java.awt.GridBagConstraints.NONE") + ", "
                + insets + ", "
                + values.getOrDefault("ipadx", "0") + ", "
                + values.getOrDefault("ipady", "0") + ")";
        return shorten(code, use);
    }

    public static String shorten(String code, UnaryOperator<String> use) {
        Matcher matcher = QUALIFIED.matcher(code);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String qualified = matcher.group(1);
            int dot = qualified.lastIndexOf('.');
            String owner = qualified.substring(0, dot);
            String member = qualified.substring(dot + 1);
            String replacement;
            if (Character.isUpperCase(member.charAt(0)) && lastSegmentIsClass(owner)) {
                replacement = use.apply(owner) + "." + member;
            } else {
                replacement = use.apply(qualified);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean lastSegmentIsClass(String owner) {
        int dot = owner.lastIndexOf('.');
        String last = dot < 0 ? owner : owner.substring(dot + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0)) && !last.equals(last.toUpperCase());
    }

    private static String valueOf(LayoutDescriptor layout, String name, JsonNode current) {
        if (current != null && current.has(name) && current.get(name).isNumber()) {
            return current.get(name).asText();
        }
        PropertyDescriptor property = layout.properties() == null ? null : layout.properties().get(name);
        if (property != null && property.defaultValue() != null) {
            return property.defaultValue();
        }
        return "0";
    }

    private static String decimal(String value) {
        return value.matches("-?\\d+") ? value + ".0" : value;
    }
}
