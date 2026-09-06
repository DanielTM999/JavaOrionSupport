package dtm.ide.sdk;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class JdkDetector {

    private static final Pattern RELEASE_ENTRY = Pattern.compile("^([A-Z_]+)=\"?([^\"]*)\"?$");
    private static final Pattern JAVA_VERSION_OUTPUT =
            Pattern.compile("version\\s+\"([^\"]+)\"");
    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private JdkDetector() {
    }

    public static List<JdkInstallation> detect(Path managedRoot) {
        Map<Path, JdkInstallation> found = new LinkedHashMap<>();

        for (Map.Entry<Path, JdkInstallation.JdkOrigin> candidate : candidates(managedRoot).entrySet()) {
            inspect(candidate.getKey(), candidate.getValue())
                    .ifPresent(installation -> found.putIfAbsent(installation.home(), installation));
        }

        List<JdkInstallation> installations = new ArrayList<>(found.values());
        installations.sort(null);
        return List.copyOf(installations);
    }

    public static Optional<JdkInstallation> inspect(Path candidate, JdkInstallation.JdkOrigin origin) {
        Path home = normalizeHome(candidate);
        if (home == null) {
            return Optional.empty();
        }

        Map<String, String> release = readRelease(home);
        String version = release.getOrDefault("JAVA_VERSION", "");
        if (version.isBlank()) {
            version = probeVersion(home);
        }
        Integer major = majorOf(version);
        if (major == null) {
            return Optional.empty();
        }

        JdkVendor vendor = JdkVendor.fromText(
                release.get("IMPLEMENTOR"),
                release.get("IMPLEMENTOR_VERSION"),
                home.toString());

        return Optional.of(new JdkInstallation(home, vendor, major, version, origin));
    }

    private static Map<Path, JdkInstallation.JdkOrigin> candidates(Path managedRoot) {
        Map<Path, JdkInstallation.JdkOrigin> candidates = new LinkedHashMap<>();

        for (Path managed : childrenOf(managedRoot)) {
            candidates.putIfAbsent(managed, JdkInstallation.JdkOrigin.MANAGED);
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.putIfAbsent(Path.of(javaHome.trim()).toAbsolutePath().normalize(),
                    JdkInstallation.JdkOrigin.JAVA_HOME);
        }

        javaOnPath().ifPresent(home ->
                candidates.putIfAbsent(home, JdkInstallation.JdkOrigin.PATH));

        for (Path root : versionManagerRoots()) {
            for (Path child : childrenOf(root)) {
                candidates.putIfAbsent(child, JdkInstallation.JdkOrigin.VERSION_MANAGER);
            }
        }

        for (Path root : systemRoots()) {
            for (Path child : childrenOf(root)) {
                candidates.putIfAbsent(child, JdkInstallation.JdkOrigin.SYSTEM);
            }
        }

        return candidates;
    }

    private static List<Path> versionManagerRoots() {
        Path userHome = userHome();
        if (userHome == null) {
            return List.of();
        }
        return List.of(
                userHome.resolve(".jdks"),
                userHome.resolve(".sdkman/candidates/java"),
                userHome.resolve(".gradle/jdks"),
                userHome.resolve(".jenv/versions"));
    }

    private static List<Path> systemRoots() {
        Platform platform = Platform.current();
        if (platform.isWindows()) {
            return Stream.of(
                            System.getenv("ProgramFiles"),
                            System.getenv("ProgramFiles(x86)"),
                            "C:\\Program Files")
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .flatMap(programFiles -> Stream.of(
                            Path.of(programFiles, "Java"),
                            Path.of(programFiles, "Eclipse Adoptium"),
                            Path.of(programFiles, "Microsoft"),
                            Path.of(programFiles, "Amazon Corretto"),
                            Path.of(programFiles, "Zulu"),
                            Path.of(programFiles, "BellSoft"),
                            Path.of(programFiles, "Semeru")))
                    .toList();
        }
        if (platform.isMac()) {
            return List.of(
                    Path.of("/Library/Java/JavaVirtualMachines"),
                    Path.of("/System/Library/Java/JavaVirtualMachines"),
                    Path.of("/opt/homebrew/opt"));
        }
        return List.of(
                Path.of("/usr/lib/jvm"),
                Path.of("/usr/java"),
                Path.of("/opt/java"),
                Path.of("/opt/jdk"));
    }

    private static Path userHome() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return null;
        }
        try {
            return Path.of(home).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> childrenOf(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Optional<Path> javaOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        String executable = "java" + Platform.current().executableSuffix();
        Set<String> seen = new LinkedHashSet<>();
        for (String directory : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (directory == null || directory.isBlank() || !seen.add(directory)) {
                continue;
            }
            try {
                Path candidate = Path.of(directory.trim()).resolve(executable);
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                Path binDir = candidate.toRealPath().getParent();
                if (binDir != null && binDir.getParent() != null) {
                    return Optional.of(binDir.getParent());
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    private static Path normalizeHome(Path candidate) {
        if (candidate == null || !Files.isDirectory(candidate)) {
            return null;
        }
        Path home = candidate.toAbsolutePath().normalize();
        if (hasJavaExecutable(home)) {
            return home;
        }
        Path macHome = home.resolve("Contents").resolve("Home");
        if (hasJavaExecutable(macHome)) {
            return macHome;
        }
        return nestedHome(home);
    }

    private static Path nestedHome(Path home) {
        for (Path child : childrenOf(home)) {
            if (hasJavaExecutable(child)) {
                return child;
            }
            Path macChild = child.resolve("Contents").resolve("Home");
            if (hasJavaExecutable(macChild)) {
                return macChild;
            }
        }
        return null;
    }

    private static boolean hasJavaExecutable(Path home) {
        return Files.isRegularFile(home.resolve("bin")
                .resolve("java" + Platform.current().executableSuffix()));
    }

    private static Map<String, String> readRelease(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return Map.of();
        }
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                Matcher matcher = RELEASE_ENTRY.matcher(line.trim());
                if (matcher.matches()) {
                    entries.put(matcher.group(1), matcher.group(2).trim());
                }
            }
        } catch (Exception e) {
            log.debug("Falha ao ler {}: {}", release, e.getMessage());
        }
        return entries;
    }

    private static String probeVersion(Path home) {
        Path java = home.resolve("bin").resolve("java" + Platform.current().executableSuffix());
        if (!Files.isRegularFile(java)) {
            return "";
        }
        Process process = null;
        try {
            process = new ProcessBuilder(java.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String version = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = JAVA_VERSION_OUTPUT.matcher(line);
                    if (matcher.find()) {
                        version = matcher.group(1);
                        break;
                    }
                }
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return version;
        } catch (Exception e) {
            log.debug("Falha ao consultar a versao de {}: {}", java, e.getMessage());
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static Integer majorOf(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String value = version.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int cut = 0;
        while (cut < value.length() && Character.isDigit(value.charAt(cut))) {
            cut++;
        }
        if (cut == 0) {
            return null;
        }
        try {
            int major = Integer.parseInt(value.substring(0, cut));
            return major > 0 && major < 100 ? major : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
