package dtm.ide.editor;

public final class JavaSourceText {

    private JavaSourceText() {
    }

    public static String blankComments(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        char[] result = source.toCharArray();
        int i = 0;
        while (i < result.length) {
            char c = result[i];
            if (c == '/' && i + 1 < result.length && result[i + 1] == '/') {
                while (i < result.length && result[i] != '\n') {
                    result[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < result.length && result[i + 1] == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? result.length : end + 2;
                blank(result, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                i = literalEnd(source, i, c);
            } else {
                i++;
            }
        }
        return new String(result);
    }

    public static String blankStringContents(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        char[] result = source.toCharArray();
        int i = 0;
        while (i < result.length) {
            char c = result[i];
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? result.length : end + 3;
                blank(result, i + 3, Math.max(i + 3, end - 3));
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(source, i, c);
                blank(result, i + 1, Math.max(i + 1, end - 1));
                i = end;
            } else {
                i++;
            }
        }
        return new String(result);
    }

    private static void blank(char[] target, int from, int to) {
        for (int i = from; i < to && i < target.length; i++) {
            if (target[i] != '\n' && target[i] != '\r') {
                target[i] = ' ';
            }
        }
    }

    private static int literalEnd(String source, int at, char quote) {
        int i = at + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote || c == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }
}
