package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

@Slf4j
public final class ProjectLayout {

    public enum Role {
        SOURCE,
        TEST,
        RESOURCE,
        TEST_RESOURCE,
        EXCLUDED;

        public static Role parse(String value) {
            if (value == null || value.isBlank()) {
                return SOURCE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SOURCE;
            }
        }

        public boolean isProduction() {
            return this == SOURCE || this == RESOURCE;
        }

        public boolean isTest() {
            return this == TEST || this == TEST_RESOURCE;
        }
    }

    private static final String KEY_PREFIX = "layout.";

    private final Path projectRoot;
    private final Properties properties;

    private ProjectLayout(Path projectRoot, Properties properties) {
        this.projectRoot = projectRoot;
        this.properties = properties;
    }

    public static ProjectLayout of(Path projectRoot) {
        Properties properties = new Properties();
        if (projectRoot == null) {
            return new ProjectLayout(null, properties);
        }
        Path file = settingsFile(projectRoot);
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (Exception e) {
                log.debug("Falha ao ler o layout de {}: {}", projectRoot, e.getMessage());
            }
        }
        return new ProjectLayout(projectRoot, properties);
    }

    public boolean isEmpty() {
        return properties.stringPropertyNames().stream().noneMatch(key -> key.startsWith(KEY_PREFIX));
    }

    public Role roleOf(Path folder) {
        String key = keyOf(folder);
        if (key == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : Role.parse(value);
    }

    public void setRole(Path folder, Role role) {
        String key = keyOf(folder);
        if (key == null) {
            return;
        }
        if (role == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, role.name());
        }
    }

    public List<Path> foldersWith(Role role) {
        List<Path> folders = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(KEY_PREFIX) || Role.parse(properties.getProperty(key)) != role) {
                continue;
            }
            folders.add(projectRoot.resolve(key.substring(KEY_PREFIX.length())));
        }
        folders.sort(Path::compareTo);
        return folders;
    }

    public List<Path> merge(List<Path> conventional, Role role) {
        Set<Path> merged = new LinkedHashSet<>();
        for (Path folder : conventional) {
            Role override = roleOf(folder);
            if (override == null ? isDefaultRole(role) : override == role) {
                merged.add(folder);
            }
        }
        merged.addAll(foldersWith(role));
        return List.copyOf(merged);
    }

    private static boolean isDefaultRole(Role role) {
        return role != Role.EXCLUDED;
    }

    public void save() {
        if (projectRoot == null) {
            return;
        }
        Path file = settingsFile(projectRoot);
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                properties.store(out, "JavaOrionSupport");
            }
        } catch (Exception e) {
            log.warn("Falha ao gravar o layout de {}: {}", projectRoot, e.getMessage());
        }
    }

    private static Path settingsFile(Path projectRoot) {
        return projectRoot.resolve(JavaProjectConventions.ORION_SETTINGS_DIR)
                .resolve(JavaProjectConventions.ORION_JAVA_PROPERTIES);
    }

    private String keyOf(Path folder) {
        if (projectRoot == null || folder == null) {
            return null;
        }
        Path normalized = folder.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot.toAbsolutePath().normalize())) {
            return null;
        }
        try {
            String relative = projectRoot.relativize(normalized).toString()
                    .replace(java.io.File.separatorChar, '/');
            return relative.isBlank() ? null : KEY_PREFIX + relative;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
