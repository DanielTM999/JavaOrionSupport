package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Locale;
import java.util.Set;

final class InspectorValues {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> INTEGRAL = Set.of("int", "java.lang.Integer", "long", "java.lang.Long",
            "short", "java.lang.Short", "byte", "java.lang.Byte");
    private static final Set<String> DECIMAL = Set.of("double", "java.lang.Double", "float", "java.lang.Float");
    private static final Set<String> TEXTUAL = Set.of("java.lang.String", "java.lang.CharSequence",
            "java.lang.Object", "char", "java.lang.Character");
    private static final Set<String> STRUCTURED = Set.of("java.awt.Color", "java.awt.Font",
            "java.awt.Dimension", "java.awt.Insets", "java.awt.Point", "java.awt.Rectangle",
            "javax.swing.Icon", "javax.swing.ImageIcon");

    private InspectorValues() {
    }

    static boolean isEditable(String type, boolean hasChoices) {
        if (type == null) {
            return false;
        }
        return hasChoices || isBoolean(type) || INTEGRAL.contains(type) || DECIMAL.contains(type)
                || TEXTUAL.contains(type) || STRUCTURED.contains(type);
    }

    static boolean isBoolean(String type) {
        return "boolean".equals(type) || "java.lang.Boolean".equals(type);
    }

    static JsonNode parse(String text, String type) {
        String value = text == null ? "" : text.trim();
        if (type == null) {
            return NODES.textNode(text);
        }
        if (isBoolean(type)) {
            return NODES.booleanNode(Boolean.parseBoolean(value));
        }
        if (value.equals("null") && type.contains(".") && !TEXTUAL.contains(type)) {
            return NODES.nullNode();
        }
        if (INTEGRAL.contains(type) && isConstantReference(value)) {
            return NODES.textNode(value);
        }
        if (INTEGRAL.contains(type)) {
            return NODES.numberNode(Long.parseLong(value));
        }
        if (DECIMAL.contains(type)) {
            return NODES.numberNode(Double.parseDouble(value.replace(',', '.')));
        }
        if ("java.awt.Dimension".equals(type)) {
            int[] parts = numbers(value, 2);
            return NODES.objectNode().put("width", parts[0]).put("height", parts[1]);
        }
        if ("java.awt.Point".equals(type)) {
            int[] parts = numbers(value, 2);
            return NODES.objectNode().put("x", parts[0]).put("y", parts[1]);
        }
        if ("java.awt.Insets".equals(type)) {
            int[] parts = numbers(value, 4);
            return NODES.objectNode().put("top", parts[0]).put("left", parts[1])
                    .put("bottom", parts[2]).put("right", parts[3]);
        }
        if ("java.awt.Rectangle".equals(type)) {
            int[] parts = numbers(value, 4);
            return NODES.objectNode().put("x", parts[0]).put("y", parts[1])
                    .put("width", parts[2]).put("height", parts[3]);
        }
        if ("java.awt.Font".equals(type)) {
            String[] parts = value.split(",");
            String name = parts[0].trim();
            int style = parts.length > 1 ? style(parts[1].trim()) : 0;
            int size = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 12;
            return NODES.objectNode().put("name", name).put("style", style).put("size", size);
        }
        return NODES.textNode(text == null ? "" : text);
    }

    static String display(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        if (value.has("text") && value.has("type")) {
            return value.get("text").asText();
        }
        if (value.has("width") && value.has("height") && value.has("x")) {
            return value.get("x").asInt() + ", " + value.get("y").asInt() + ", "
                    + value.get("width").asInt() + ", " + value.get("height").asInt();
        }
        if (value.has("width") && value.has("height")) {
            return value.get("width").asInt() + ", " + value.get("height").asInt();
        }
        if (value.has("top")) {
            return value.get("top").asInt() + ", " + value.get("left").asInt() + ", "
                    + value.get("bottom").asInt() + ", " + value.get("right").asInt();
        }
        if (value.has("x") && value.has("y")) {
            return value.get("x").asInt() + ", " + value.get("y").asInt();
        }
        if (value.has("name") && value.has("size")) {
            return value.get("name").asText() + ", " + styleName(value.path("style").asInt())
                    + ", " + value.get("size").asInt();
        }
        return value.toString();
    }

    static String shortConstant(String reference) {
        int dot = reference.lastIndexOf('.');
        return dot < 0 ? reference : reference.substring(dot + 1);
    }

    private static boolean isConstantReference(String value) {
        return !value.isEmpty() && (Character.isLetter(value.charAt(0)) || value.charAt(0) == '_');
    }

    private static int[] numbers(String value, int count) {
        String[] parts = value.split("[,;x\\s]+");
        int[] numbers = new int[count];
        int index = 0;
        for (String part : parts) {
            if (part.isBlank() || index >= count) {
                continue;
            }
            numbers[index++] = Integer.parseInt(part.trim());
        }
        if (index < count) {
            throw new IllegalArgumentException("Informe " + count + " numeros separados por virgula");
        }
        return numbers;
    }

    private static int style(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("\\d+")) {
            return Integer.parseInt(lower);
        }
        int style = 0;
        if (lower.contains("bold") || lower.contains("negrito")) {
            style |= 1;
        }
        if (lower.contains("italic") || lower.contains("italico")) {
            style |= 2;
        }
        return style;
    }

    private static String styleName(int style) {
        return switch (style) {
            case 1 -> "bold";
            case 2 -> "italic";
            case 3 -> "bold italic";
            default -> "plain";
        };
    }
}
