package dtm.ide.swingdesigner.host;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Values {

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<String, Class<?>>();

    static {
        PRIMITIVES.put("boolean", boolean.class);
        PRIMITIVES.put("byte", byte.class);
        PRIMITIVES.put("char", char.class);
        PRIMITIVES.put("short", short.class);
        PRIMITIVES.put("int", int.class);
        PRIMITIVES.put("long", long.class);
        PRIMITIVES.put("float", float.class);
        PRIMITIVES.put("double", double.class);
    }

    private Values() {
    }

    static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null) {
            return primitive;
        }
        if (name.endsWith("[]")) {
            Class<?> component = type(name.substring(0, name.length() - 2), loader);
            return Array.newInstance(component, 0).getClass();
        }
        return Class.forName(name, false, loader);
    }

    static Object toJava(Object json, Class<?> type, ClassLoader loader) throws Exception {
        if (json == null) {
            return type.isPrimitive() ? primitiveDefault(type) : null;
        }
        if (json instanceof String && looksLikeConstant((String) json, type)) {
            Object constant = constant((String) json, loader);
            if (constant != null) {
                return constant;
            }
        }
        if (type == String.class || type == Object.class || type == CharSequence.class) {
            return String.valueOf(json);
        }
        if (type == boolean.class || type == Boolean.class) {
            return json instanceof Boolean ? json : Boolean.valueOf(String.valueOf(json));
        }
        if (type == char.class || type == Character.class) {
            String text = String.valueOf(json);
            return text.isEmpty() ? Character.valueOf('\0') : Character.valueOf(text.charAt(0));
        }
        if (isNumeric(type)) {
            return number(json, type);
        }
        if (type.isEnum()) {
            return enumValue(type, String.valueOf(json));
        }
        if (type == Color.class) {
            return color(json);
        }
        if (type == Font.class) {
            Map<String, Object> map = Json.object(json);
            return new Font(stringOr(map, "name", Font.DIALOG), Json.integer(map, "style", Font.PLAIN),
                    Json.integer(map, "size", 12));
        }
        if (type == Dimension.class) {
            Map<String, Object> map = Json.object(json);
            return new Dimension(Json.integer(map, "width", 0), Json.integer(map, "height", 0));
        }
        if (type == Point.class) {
            Map<String, Object> map = Json.object(json);
            return new Point(Json.integer(map, "x", 0), Json.integer(map, "y", 0));
        }
        if (type == Insets.class) {
            Map<String, Object> map = Json.object(json);
            return new Insets(Json.integer(map, "top", 0), Json.integer(map, "left", 0),
                    Json.integer(map, "bottom", 0), Json.integer(map, "right", 0));
        }
        if (type == Rectangle.class) {
            Map<String, Object> map = Json.object(json);
            return new Rectangle(Json.integer(map, "x", 0), Json.integer(map, "y", 0),
                    Json.integer(map, "width", 0), Json.integer(map, "height", 0));
        }
        if (type == Icon.class || type == ImageIcon.class) {
            return icon(String.valueOf(json), loader);
        }
        if (type.isInstance(json)) {
            return json;
        }
        throw new IllegalArgumentException("Tipo sem conversao: " + type.getName());
    }

    static Object toJson(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Character) {
            return String.valueOf(value);
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Color) {
            Color color = (Color) value;
            String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            return color.getAlpha() == 255 ? hex : hex + String.format("%02x", color.getAlpha());
        }
        if (value instanceof Font) {
            Font font = (Font) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("name", font.getName());
            map.put("style", font.getStyle());
            map.put("size", font.getSize());
            return map;
        }
        if (value instanceof Dimension) {
            Dimension dimension = (Dimension) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("width", dimension.width);
            map.put("height", dimension.height);
            return map;
        }
        if (value instanceof Rectangle) {
            Rectangle rectangle = (Rectangle) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("x", rectangle.x);
            map.put("y", rectangle.y);
            map.put("width", rectangle.width);
            map.put("height", rectangle.height);
            return map;
        }
        if (value instanceof Point) {
            Point point = (Point) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("x", point.x);
            map.put("y", point.y);
            return map;
        }
        if (value instanceof Insets) {
            Insets insets = (Insets) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("top", insets.top);
            map.put("left", insets.left);
            map.put("bottom", insets.bottom);
            map.put("right", insets.right);
            return map;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("type", value.getClass().getName());
        map.put("text", safeToString(value));
        return map;
    }

    static Object syntheticDefault(Class<?> type, ClassLoader loader, int depth) {
        if (type.isPrimitive()) {
            return primitiveDefault(type);
        }
        if (type == String.class || type == CharSequence.class) {
            return "";
        }
        if (type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == Character.class) {
            return Character.valueOf('\0');
        }
        if (isNumeric(type)) {
            return number(Integer.valueOf(0), type);
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants == null || constants.length == 0 ? null : constants[0];
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (type == List.class || type == java.util.Collection.class || type == Iterable.class) {
            return new java.util.ArrayList<Object>();
        }
        if (type == java.util.Set.class) {
            return new java.util.LinkedHashSet<Object>();
        }
        if (type == Map.class) {
            return new LinkedHashMap<Object, Object>();
        }
        if (type == Color.class) {
            return Color.GRAY;
        }
        if (type == Dimension.class) {
            return new Dimension(100, 30);
        }
        if (type == Insets.class) {
            return new Insets(0, 0, 0, 0);
        }
        if (depth > 1 || Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> constructor = type.getConstructor();
            return constructor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeConstant(String text, Class<?> type) {
        if (type == String.class && !text.startsWith("java.") && !text.startsWith("javax.")) {
            return false;
        }
        int dot = text.lastIndexOf('.');
        return dot > 0 && dot < text.length() - 1 && text.indexOf(' ') < 0
                && Character.isJavaIdentifierStart(text.charAt(dot + 1))
                && Character.isUpperCase(text.charAt(dot + 1));
    }

    static Object constant(String reference, ClassLoader loader) {
        int dot = reference.lastIndexOf('.');
        try {
            Class<?> owner = Class.forName(reference.substring(0, dot), true, loader);
            Field field = owner.getField(reference.substring(dot + 1));
            return Modifier.isStatic(field.getModifiers()) ? field.get(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Icon icon(String path, ClassLoader loader) {
        if (path.isEmpty()) {
            return null;
        }
        String resource = path.startsWith("/") ? path.substring(1) : path;
        URL url = loader.getResource(resource);
        if (url != null) {
            return new ImageIcon(url);
        }
        File file = new File(path);
        return file.isFile() ? new ImageIcon(file.getAbsolutePath()) : null;
    }

    private static Color color(Object json) {
        if (json instanceof Map) {
            Map<String, Object> map = Json.object(json);
            return new Color(Json.integer(map, "r", 0), Json.integer(map, "g", 0),
                    Json.integer(map, "b", 0), Json.integer(map, "a", 255));
        }
        String text = String.valueOf(json).trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        long value = Long.parseLong(text, 16);
        if (text.length() == 8) {
            int rgb = (int) (value >> 8);
            return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, (int) (value & 0xff));
        }
        return new Color((int) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        int dot = name.lastIndexOf('.');
        String constant = dot >= 0 ? name.substring(dot + 1) : name;
        return Enum.valueOf((Class) type, constant);
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == double.class || type == Double.class || type == float.class
                || type == Float.class || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class;
    }

    private static Object number(Object json, Class<?> type) {
        Number number = json instanceof Number ? (Number) json : Double.valueOf(String.valueOf(json).trim());
        if (type == int.class || type == Integer.class) {
            return number.intValue();
        }
        if (type == long.class || type == Long.class) {
            return number.longValue();
        }
        if (type == double.class || type == Double.class) {
            return number.doubleValue();
        }
        if (type == float.class || type == Float.class) {
            return number.floatValue();
        }
        if (type == short.class || type == Short.class) {
            return number.shortValue();
        }
        return number.byteValue();
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return number(Integer.valueOf(0), type);
    }

    private static String stringOr(Map<String, Object> map, String key, String fallback) {
        String value = Json.string(map, key);
        return value == null ? fallback : value;
    }

    static String safeToString(Object value) {
        try {
            String text = String.valueOf(value);
            return text.length() > 200 ? text.substring(0, 200) + "..." : text;
        } catch (Throwable error) {
            return value.getClass().getName();
        }
    }
}
