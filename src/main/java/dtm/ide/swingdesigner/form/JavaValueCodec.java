package dtm.ide.swingdesigner.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class JavaValueCodec {

    private static final Set<String> INTEGRAL = Set.of("int", "java.lang.Integer", "short", "java.lang.Short",
            "byte", "java.lang.Byte");
    private static final Set<String> LONG = Set.of("long", "java.lang.Long");
    private static final Set<String> FLOAT = Set.of("float", "java.lang.Float");
    private static final Set<String> DOUBLE = Set.of("double", "java.lang.Double");
    private static final Set<String> BOOLEAN = Set.of("boolean", "java.lang.Boolean");
    private static final Set<String> CHAR = Set.of("char", "java.lang.Character");
    private static final Set<String> TEXT = Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.Object");
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("#000000", "BLACK"), Map.entry("#ffffff", "WHITE"), Map.entry("#ff0000", "RED"),
            Map.entry("#00ff00", "GREEN"), Map.entry("#0000ff", "BLUE"), Map.entry("#ffff00", "YELLOW"),
            Map.entry("#ff00ff", "MAGENTA"), Map.entry("#00ffff", "CYAN"), Map.entry("#808080", "GRAY"),
            Map.entry("#404040", "DARK_GRAY"), Map.entry("#c0c0c0", "LIGHT_GRAY"), Map.entry("#ffc800", "ORANGE"),
            Map.entry("#ffafaf", "PINK"));

    private JavaValueCodec() {
    }

    public static String encode(JsonNode value, String type, boolean enumType, UnaryOperator<String> use) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isObject() && value.has("expr")) {
            return value.get("expr").asText();
        }
        if (type == null) {
            throw new IllegalArgumentException("Tipo da propriedade desconhecido");
        }
        if (value.isTextual() && !TEXT.contains(type) && !CHAR.contains(type) && isConstantReference(value.asText())
                && !enumType && !isIcon(type) && !"java.awt.Color".equals(type)) {
            return constant(value.asText(), use);
        }
        if (TEXT.contains(type)) {
            return quote(value.asText());
        }
        if (BOOLEAN.contains(type)) {
            return String.valueOf(value.isBoolean() ? value.booleanValue() : Boolean.parseBoolean(value.asText()));
        }
        if (CHAR.contains(type)) {
            String text = value.asText();
            return text.isEmpty() ? "'\\0'" : "'" + escape(text.substring(0, 1), '\'') + "'";
        }
        if (INTEGRAL.contains(type)) {
            return String.valueOf(value.asLong());
        }
        if (LONG.contains(type)) {
            return value.asLong() + "L";
        }
        if (FLOAT.contains(type)) {
            return trimDecimal(value.asDouble()) + "f";
        }
        if (DOUBLE.contains(type)) {
            String text = trimDecimal(value.asDouble());
            return text.contains(".") ? text : text + ".0";
        }
        if (enumType) {
            String name = value.asText();
            int dot = name.lastIndexOf('.');
            return use.apply(type) + "." + (dot < 0 ? name : name.substring(dot + 1));
        }
        switch (type) {
            case "java.awt.Color" -> {
                return color(value.asText(), use);
            }
            case "java.awt.Font" -> {
                String font = use.apply("java.awt.Font");
                return "new " + font + "(" + quote(value.path("name").asText("Dialog")) + ", "
                        + fontStyle(value.path("style").asInt(), font) + ", " + value.path("size").asInt(12) + ")";
            }
            case "java.awt.Dimension" -> {
                return "new " + use.apply(type) + "(" + value.path("width").asInt() + ", "
                        + value.path("height").asInt() + ")";
            }
            case "java.awt.Point" -> {
                return "new " + use.apply(type) + "(" + value.path("x").asInt() + ", " + value.path("y").asInt() + ")";
            }
            case "java.awt.Insets" -> {
                return "new " + use.apply(type) + "(" + value.path("top").asInt() + ", " + value.path("left").asInt()
                        + ", " + value.path("bottom").asInt() + ", " + value.path("right").asInt() + ")";
            }
            case "java.awt.Rectangle" -> {
                return "new " + use.apply(type) + "(" + value.path("x").asInt() + ", " + value.path("y").asInt()
                        + ", " + value.path("width").asInt() + ", " + value.path("height").asInt() + ")";
            }
            default -> {
            }
        }
        if (isIcon(type)) {
            String path = value.asText().trim();
            if (path.isEmpty()) {
                return "null";
            }
            return "new " + use.apply("javax.swing.ImageIcon") + "(getClass().getResource("
                    + quote(path.startsWith("/") ? path : "/" + path) + "))";
        }
        throw new IllegalArgumentException("Sem conversao para codigo: " + type);
    }

    public static String quote(String text) {
        return "\"" + escape(text, '"') + "\"";
    }

    public static String constant(String reference, UnaryOperator<String> use) {
        int dot = reference.lastIndexOf('.');
        if (dot < 0) {
            return reference;
        }
        String owner = reference.substring(0, dot);
        String name = reference.substring(dot + 1);
        if (owner.contains(".") && Character.isLowerCase(owner.charAt(0))) {
            return use.apply(owner) + "." + name;
        }
        return reference;
    }

    private static boolean isIcon(String type) {
        return "javax.swing.Icon".equals(type) || "javax.swing.ImageIcon".equals(type);
    }

    private static boolean isConstantReference(String value) {
        return !value.isEmpty() && (Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')
                && value.matches("[\\w.$]+");
    }

    private static String color(String text, UnaryOperator<String> use) {
        String color = use.apply("java.awt.Color");
        String hex = text.trim().toLowerCase(Locale.ROOT);
        if (!hex.matches("#[0-9a-f]{6}([0-9a-f]{2})?")) {
            if (isConstantReference(text.trim())) {
                return constant(text.trim(), use);
            }
            throw new IllegalArgumentException("Cor invalida: " + text);
        }
        String named = hex.length() == 7 ? NAMED_COLORS.get(hex) : null;
        if (named != null) {
            return color + "." + named;
        }
        int red = Integer.parseInt(hex.substring(1, 3), 16);
        int green = Integer.parseInt(hex.substring(3, 5), 16);
        int blue = Integer.parseInt(hex.substring(5, 7), 16);
        if (hex.length() == 9) {
            int alpha = Integer.parseInt(hex.substring(7, 9), 16);
            return "new " + color + "(" + red + ", " + green + ", " + blue + ", " + alpha + ")";
        }
        return "new " + color + "(" + red + ", " + green + ", " + blue + ")";
    }

    private static String fontStyle(int style, String font) {
        return switch (style) {
            case 1 -> font + ".BOLD";
            case 2 -> font + ".ITALIC";
            case 3 -> font + ".BOLD | " + font + ".ITALIC";
            default -> font + ".PLAIN";
        };
    }

    private static String trimDecimal(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String escape(String text, char quote) {
        StringBuilder builder = new StringBuilder();
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch == quote) {
                        builder.append('\\').append(ch);
                    } else if (ch < 0x20 || ch > 0x7e) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }
}
