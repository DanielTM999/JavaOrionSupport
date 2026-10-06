package dtm.ide.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClasspathValidation {

    private ClasspathValidation() {
    }

    public static boolean hasMissingJar(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return false;
        }
        for (String rawEntry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String entry = rawEntry.trim();
            if (entry.isBlank() || !entry.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            try {
                if (!Files.isRegularFile(Path.of(entry))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                return true;
            }

        }
        return false;
    }

    public static String fingerprint(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return "";
        }
        java.util.List<String> stamps = new java.util.ArrayList<>();
        for (String rawEntry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String entry = rawEntry.trim();
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry).toAbsolutePath().normalize();
                if (isOutputDirectory(path)) {
                    continue;
                }
                if (Files.exists(path)) {
                    stamps.add(path + ":" + Files.size(path) + ":"
                            + Files.getLastModifiedTime(path).toMillis());
                } else {
                    stamps.add(path + ":missing");
                }
            } catch (Exception e) {
                stamps.add(entry + ":invalid");
            }
        }
        java.util.Collections.sort(stamps);
        return dtm.ide.build.incremental.ModuleBuildState.fingerprintOf(
                stamps.toArray(String[]::new));
    }

    private static boolean isOutputDirectory(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        return !Files.exists(path) && !name.endsWith(".jar") && !name.endsWith(".zip");
    }
}
