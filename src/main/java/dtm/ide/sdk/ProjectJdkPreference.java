package dtm.ide.sdk;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class ProjectJdkPreference {

    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+(?:[+_]\\d+)*)");

    private ProjectJdkPreference() {
    }

    public static Optional<String> exactVersion(Path projectRoot) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        return fromJavaVersionFile(projectRoot)
                .or(() -> fromSdkmanrc(projectRoot))
                .or(() -> fromToolVersions(projectRoot));
    }

    public static String normalize(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(version.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean matches(String installed, String requested) {
        String left = baseOf(installed);
        String right = baseOf(requested);
        return left != null && left.equals(right);
    }

    private static String baseOf(String version) {
        String normalized = normalize(version);
        if (normalized == null) {
            return null;
        }
        int cut = normalized.length();
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '+' || current == '_') {
                cut = index;
                break;
            }
        }
        return normalized.substring(0, cut);
    }

    private static Optional<String> fromJavaVersionFile(Path projectRoot) {
        return firstMeaningfulLine(projectRoot.resolve(".java-version"))
                .map(ProjectJdkPreference::normalize)
                .filter(value -> value != null && !value.isBlank());
    }

    private static Optional<String> fromSdkmanrc(Path projectRoot) {
        return lines(projectRoot.resolve(".sdkmanrc")).stream()
                .map(String::trim)
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("java="))
                .map(line -> line.substring("java=".length()))
                .map(ProjectJdkPreference::normalize)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static Optional<String> fromToolVersions(Path projectRoot) {
        return lines(projectRoot.resolve(".tool-versions")).stream()
                .map(String::trim)
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("java "))
                .map(line -> line.substring("java ".length()))
                .map(ProjectJdkPreference::normalize)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static Optional<String> firstMeaningfulLine(Path file) {
        return lines(file).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .findFirst();
    }

    private static List<String> lines(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Falha ao ler {}: {}", file, e.getMessage());
            return List.of();
        }
    }
}
