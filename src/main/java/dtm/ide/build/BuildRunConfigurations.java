package dtm.ide.build;

import dtm.ide.project.JavaProjectConventions;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Slf4j
public final class BuildRunConfigurations {

    public record Entry(String name, List<String> goals) {

        public Entry {
            name = name == null ? "" : name.trim();
            goals = goals == null ? List.of() : List.copyOf(goals);
        }

        public String display() {
            return goals.isEmpty() ? name : name + " (" + String.join(" ", goals) + ")";
        }

        public boolean isValid() {
            return !name.isBlank() && !goals.isEmpty();
        }
    }

    private static final String KEY_PREFIX = "buildRun.";
    private static final String KEY_ACTIVE_PROFILES = "buildProfiles";
    private static final String KEY_SKIP_TESTS = "buildSkipTests";
    private static final String KEY_OFFLINE = "buildOffline";

    public record ToolOptions(boolean skipTests, boolean offline) {

        public static ToolOptions none() {
            return new ToolOptions(false, false);
        }
    }

    private final Path projectRoot;

    public BuildRunConfigurations(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public List<Entry> all() {
        Properties properties = load();
        List<Entry> entries = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            String name = key.substring(KEY_PREFIX.length());
            List<String> goals = splitGoals(properties.getProperty(key));
            Entry entry = new Entry(name, goals);
            if (entry.isValid()) {
                entries.add(entry);
            }
        }
        entries.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return List.copyOf(entries);
    }

    public boolean save(Entry entry) {
        if (entry == null || !entry.isValid() || projectRoot == null) {
            return false;
        }
        Properties properties = load();
        properties.setProperty(KEY_PREFIX + entry.name(), BuildCommand.joinArguments(entry.goals()));
        return store(properties);
    }

    public boolean remove(String name) {
        if (name == null || name.isBlank() || projectRoot == null) {
            return false;
        }
        Properties properties = load();
        if (properties.remove(KEY_PREFIX + name) == null) {
            return false;
        }
        return store(properties);
    }

    public Set<String> activeProfiles() {
        Properties properties = load();
        Set<String> profiles = new LinkedHashSet<>();
        String raw = properties.getProperty(KEY_ACTIVE_PROFILES, "");
        for (String part : raw.split(",")) {
            String profile = part.trim();
            if (!profile.isEmpty()) {
                profiles.add(profile);
            }
        }
        return Set.copyOf(profiles);
    }

    public boolean saveActiveProfiles(Set<String> activeProfiles) {
        if (projectRoot == null) {
            return false;
        }
        Properties properties = load();
        Set<String> profiles = activeProfiles == null ? Set.of() : activeProfiles;
        if (profiles.isEmpty()) {
            properties.remove(KEY_ACTIVE_PROFILES);
        } else {
            properties.setProperty(KEY_ACTIVE_PROFILES, String.join(",", profiles));
        }
        return store(properties);
    }

    public ToolOptions toolOptions() {
        Properties properties = load();
        return new ToolOptions(Boolean.parseBoolean(properties.getProperty(KEY_SKIP_TESTS)),
                Boolean.parseBoolean(properties.getProperty(KEY_OFFLINE)));
    }

    public boolean saveToolOptions(ToolOptions options) {
        if (projectRoot == null) {
            return false;
        }
        ToolOptions resolved = options == null ? ToolOptions.none() : options;
        Properties properties = load();
        store(properties, KEY_SKIP_TESTS, resolved.skipTests());
        store(properties, KEY_OFFLINE, resolved.offline());
        return store(properties);
    }

    private static void store(Properties properties, String key, boolean value) {
        if (value) {
            properties.setProperty(key, Boolean.TRUE.toString());
        } else {
            properties.remove(key);
        }
    }

    public static List<String> splitGoals(String raw) {
        List<String> goals = new ArrayList<>();
        for (String token : BuildCommand.parseArguments(raw)) {
            if (token.startsWith("-")) {
                goals.add(token);
                continue;
            }
            for (String part : token.split("[,;]+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    goals.add(trimmed);
                }
            }
        }
        return List.copyOf(goals);
    }

    private Properties load() {
        Properties properties = new Properties();
        if (projectRoot == null) {
            return properties;
        }
        Path file = settingsFile();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (Exception e) {
                log.debug("Configuracoes de build ilegiveis em {}: {}", file, e.getMessage());
            }
        }
        return properties;
    }

    private boolean store(Properties properties) {
        Path file = settingsFile();
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                properties.store(out, "JavaOrionSupport");
            }
            return true;
        } catch (Exception e) {
            log.warn("Falha ao gravar as configuracoes de build em {}: {}", file, e.getMessage());
            return false;
        }
    }

    private Path settingsFile() {
        return projectRoot.resolve(JavaProjectConventions.ORION_SETTINGS_DIR)
                .resolve(JavaProjectConventions.ORION_JAVA_PROPERTIES);
    }
}
