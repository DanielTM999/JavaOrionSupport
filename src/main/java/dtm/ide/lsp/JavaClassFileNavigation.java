package dtm.ide.lsp;

import java.net.URI;

public final class JavaClassFileNavigation {

    private static final String FALLBACK_NAME = "Decompiled.java";

    private JavaClassFileNavigation() {
    }

    public static boolean isClassFileUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return "jdt".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String sourceFileName(String value) {
        if (!isClassFileUri(value)) {
            return FALLBACK_NAME;
        }
        try {
            String path = URI.create(value).getPath();
            int slash = path == null ? -1 : Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = path == null ? "" : path.substring(slash + 1);
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".class")) {
                name = name.substring(0, name.length() - ".class".length()) + ".java";
            }
            name = name.replaceAll("[<>:\"/\\\\|?*]", "_");
            return name.isBlank() ? FALLBACK_NAME : name;
        } catch (Exception ignored) {
            return FALLBACK_NAME;
        }
    }

    public static String tabKey(String uri) {
        return "java.class-file:" + Integer.toUnsignedString(
                uri == null ? 0 : uri.hashCode(), 36);
    }
}
