package dtm.ide.sdk;

import java.util.Locale;

public enum Platform {

    WINDOWS_X64("windows", "x64"),
    WINDOWS_ARM64("windows", "aarch64"),
    LINUX_X64("linux", "x64"),
    LINUX_ARM64("linux", "aarch64"),
    MACOS_X64("mac", "x64"),
    MACOS_ARM64("mac", "aarch64");

    private final String adoptiumOs;
    private final String adoptiumArch;

    Platform(String adoptiumOs, String adoptiumArch) {
        this.adoptiumOs = adoptiumOs;
        this.adoptiumArch = adoptiumArch;
    }

    public String adoptiumOs() {
        return adoptiumOs;
    }

    public String adoptiumArch() {
        return adoptiumArch;
    }

    public String jdtLsConfig() {
        return switch (this) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "win";
            case MACOS_X64 -> "mac";
            case MACOS_ARM64 -> "mac_arm";
            case LINUX_ARM64 -> "linux_arm";
            default -> "linux";
        };
    }

    public boolean isWindows() {
        return this == WINDOWS_X64 || this == WINDOWS_ARM64;
    }

    public boolean isMac() {
        return this == MACOS_X64 || this == MACOS_ARM64;
    }

    public boolean isLinux() {
        return this == LINUX_X64 || this == LINUX_ARM64;
    }

    public boolean isArm64() {
        return this == WINDOWS_ARM64 || this == LINUX_ARM64 || this == MACOS_ARM64;
    }

    public String executableSuffix() {
        return isWindows() ? ".exe" : "";
    }

    public String archiveExtension() {
        return isWindows() ? "zip" : "tar.gz";
    }

    public static Platform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            return arm64 ? WINDOWS_ARM64 : WINDOWS_X64;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? MACOS_ARM64 : MACOS_X64;
        }
        return arm64 ? LINUX_ARM64 : LINUX_X64;
    }

    public static boolean isWindowsHost() {
        return current().isWindows();
    }
}
