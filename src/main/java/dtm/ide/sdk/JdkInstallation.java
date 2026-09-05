package dtm.ide.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record JdkInstallation(
        Path home,
        JdkVendor vendor,
        int major,
        String fullVersion,
        JdkOrigin origin
) implements Comparable<JdkInstallation> {

    public enum JdkOrigin {
        MANAGED,
        JAVA_HOME,
        PATH,
        SYSTEM,
        VERSION_MANAGER
    }

    public JdkInstallation {
        Objects.requireNonNull(home, "home");
        home = home.toAbsolutePath().normalize();
        vendor = vendor == null ? JdkVendor.UNKNOWN : vendor;
        fullVersion = fullVersion == null || fullVersion.isBlank() ? String.valueOf(major) : fullVersion.trim();
        origin = origin == null ? JdkOrigin.SYSTEM : origin;
    }

    public Path javaExecutable() {
        return home.resolve("bin").resolve("java" + Platform.current().executableSuffix());
    }

    public Path javacExecutable() {
        return home.resolve("bin").resolve("javac" + Platform.current().executableSuffix());
    }

    public boolean isJdk() {
        return Files.isRegularFile(javacExecutable());
    }

    public boolean isUsable() {
        return Files.isRegularFile(javaExecutable());
    }

    public boolean isManaged() {
        return origin == JdkOrigin.MANAGED;
    }

    public String displayName() {
        return vendor.displayName() + " " + fullVersion;
    }

    @Override
    public int compareTo(JdkInstallation other) {
        int byMajor = Integer.compare(other.major, major);
        return byMajor != 0 ? byMajor : other.fullVersion.compareTo(fullVersion);
    }
}
