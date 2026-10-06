package dtm.ide.swingdesigner.catalog;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ClasspathEntry(Path path, ComponentOrigin origin, String label) {

    private static final Pattern VERSION_SUFFIX = Pattern.compile("-\\d[\\w.\\-]*$");

    public ClasspathEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(origin, "origin");
        if (label == null || label.isBlank()) {
            label = defaultLabel(path);
        }
    }

    public static ClasspathEntry library(Path jar) {
        return new ClasspathEntry(jar, ComponentOrigin.LIBRARY, null);
    }

    public static ClasspathEntry workspace(Path outputDir, String moduleName) {
        return new ClasspathEntry(outputDir, ComponentOrigin.WORKSPACE, moduleName);
    }

    public boolean isJar() {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    static String defaultLabel(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        String name = fileName.toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
            return VERSION_SUFFIX.matcher(name).replaceFirst("");
        }
        if (name.equals("classes") || name.equals("main")) {
            Path parent = path.getParent();
            while (parent != null && parent.getFileName() != null) {
                String parentName = parent.getFileName().toString();
                if (!parentName.equals("target") && !parentName.equals("build")
                        && !parentName.equals("java") && !parentName.equals("classes")) {
                    return parentName;
                }
                parent = parent.getParent();
            }
        }
        return name;
    }
}
