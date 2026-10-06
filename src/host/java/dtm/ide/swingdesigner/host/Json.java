package dtm.ide.swingdesigner.host;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote((String) value, out);
        } else if (value instanceof Number) {
            writeNumber((Number) value, out);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = ((Map<?, ?>) value).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            Iterator<?> iterator = ((Iterable<?>) value).iterator();
            while (iterator.hasNext()) {
                write(iterator.next(), out);
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append(']');
        } else {
            quote(value.toString(), out);
        }
    }

    private static void writeNumber(Number number, StringBuilder out) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                out.append("null");
                return;
            }
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                out.append((long) value).append(".0");
                return;
            }
        }
        out.append(number.toString());
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            position++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<Object>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        if (peek() != '"') {
            throw error("expected string");
        }
        position++;
        StringBuilder out = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                    break;
                default:
                    out.append(escaped);
            }
        }
        throw error("unterminated string");
    }

    private Number readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String number = text.substring(start, position);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
            return Double.valueOf(number);
        }
        long value = Long.parseLong(number);
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) value);
        }
        return Long.valueOf(value);
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, position)) {
            throw error("expected " + literal);
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + position);
    }
}
