package dtm.ide.spring;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record SpringEndpoint(
        String method,
        String path,
        String handlerType,
        String handlerName,
        Path file,
        int line,
        List<String> produces
) implements Comparable<SpringEndpoint> {

    public static final String ANY_METHOD = "ANY";

    public SpringEndpoint {
        method = method == null || method.isBlank() ? ANY_METHOD : method.toUpperCase(Locale.ROOT);
        path = normalizePath(path);
        handlerType = handlerType == null ? "" : handlerType;
        handlerName = handlerName == null ? "" : handlerName;
        produces = produces == null ? List.of() : List.copyOf(produces);
        line = Math.max(1, line);
    }

    public static String join(String classPath, String methodPath) {
        String base = normalizePath(classPath);
        String suffix = normalizePath(methodPath);
        if (base.equals("/")) {
            return suffix;
        }
        if (suffix.equals("/")) {
            return base;
        }
        return base + suffix;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String value = path.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.replaceAll("/{2,}", "/");
    }

    public String handlerSimpleType() {
        return SpringBean.simpleNameOf(handlerType);
    }

    public boolean hasPathVariables() {
        return path.contains("{");
    }

    public String urlOn(String baseUrl) {
        String host = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl.trim();
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host + path;
    }

    @Override
    public int compareTo(SpringEndpoint other) {
        int byPath = path.compareTo(other.path);
        return byPath != 0 ? byPath : method.compareTo(other.method);
    }
}
