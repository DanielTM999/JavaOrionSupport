package dtm.ide.deps;

import java.util.Locale;
import java.util.Objects;

public record DependencyCoordinate(String groupId, String artifactId, String version, String scope) {

    public static final String SCOPE_COMPILE = "compile";

    public DependencyCoordinate {
        groupId = groupId == null ? "" : groupId.trim();
        artifactId = artifactId == null ? "" : artifactId.trim();
        version = version == null ? "" : version.trim();
        scope = scope == null || scope.isBlank() ? SCOPE_COMPILE : scope.trim();
    }

    public static DependencyCoordinate of(String groupId, String artifactId, String version) {
        return new DependencyCoordinate(groupId, artifactId, version, SCOPE_COMPILE);
    }

    public static DependencyCoordinate parse(String notation) {
        if (notation == null || notation.isBlank()) {
            return null;
        }
        String[] parts = notation.trim().split(":");
        if (parts.length < 2) {
            return null;
        }
        return new DependencyCoordinate(parts[0], parts[1],
                parts.length > 2 ? parts[2] : "", SCOPE_COMPILE);
    }

    public String key() {
        return groupId + ":" + artifactId;
    }

    public String notation() {
        return version.isBlank() ? key() : key() + ":" + version;
    }

    public boolean hasVersion() {
        return !version.isBlank();
    }

    public boolean isTestScope() {
        return scope.toLowerCase(Locale.ROOT).startsWith("test");
    }

    public boolean sameArtifact(DependencyCoordinate other) {
        return other != null
                && Objects.equals(groupId, other.groupId)
                && Objects.equals(artifactId, other.artifactId);
    }

    public DependencyCoordinate withVersion(String newVersion) {
        return new DependencyCoordinate(groupId, artifactId, newVersion, scope);
    }

    public DependencyCoordinate withScope(String newScope) {
        return new DependencyCoordinate(groupId, artifactId, version, newScope);
    }

    public boolean isValid() {
        return !groupId.isBlank() && !artifactId.isBlank();
    }

    @Override
    public String toString() {
        return notation();
    }
}
