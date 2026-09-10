package dtm.ide.deps;

import java.nio.file.Path;

public record ManagedDependency(
        DependencyCoordinate declared,
        DependencyCoordinate effective,
        DependencyVersionOrigin versionOrigin,
        Path sourceFile,
        String propertyName
) {

    public ManagedDependency {
        versionOrigin = versionOrigin == null
                ? DependencyVersionOrigin.UNRESOLVED : versionOrigin;
        sourceFile = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        propertyName = propertyName == null ? "" : propertyName.trim();
    }

    public String key() {
        return declared == null ? effective == null ? "" : effective.key() : declared.key();
    }

    public String resolvedVersion() {
        return effective == null ? "" : effective.version();
    }

    public boolean versionResolved() {
        return !resolvedVersion().isBlank() && !isPlaceholder(resolvedVersion());
    }

    public boolean versionEditable() {
        return versionOrigin.editable() && sourceFile != null;
    }

    public boolean managed() {
        return versionOrigin != DependencyVersionOrigin.DIRECT;
    }

    public static boolean isPlaceholder(String value) {
        return value != null && (value.contains("${") || value.matches(".*\\$[A-Za-z_].*"));
    }
}
