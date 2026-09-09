package dtm.ide.inspection;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

@Slf4j
public final class InspectionSuppressionStore {

    private static final String FILE_NAME = "java-inspection-suppressions.properties";

    private static final String SEPARATOR = "|";

    public record Entry(String projectRoot, String file, String inspectionId, String anchor) {

        public Entry {
            projectRoot = projectRoot == null ? "" : projectRoot;
            file = file == null ? "" : file;
            inspectionId = inspectionId == null ? "" : inspectionId;
            anchor = anchor == null ? "" : anchor;
        }

        public String key() {
            return projectRoot + SEPARATOR + file + SEPARATOR + inspectionId + SEPARATOR + anchor;
        }

        static Entry parse(String key) {
            String[] parts = key.split("\\" + SEPARATOR, 4);
            if (parts.length != 4) {
                return null;
            }
            return new Entry(parts[0], parts[1], parts[2], parts[3]);
        }

        public String describe() {
            return file + "  ->  " + anchor;
        }
    }

    private final Path storeFile;
    private final Set<String> keys = new LinkedHashSet<>();

    public InspectionSuppressionStore(Path directory) {
        this.storeFile = directory == null ? null : directory.resolve(FILE_NAME);
        load();
    }

    public boolean isSuppressed(Path projectRoot, Path file, String inspectionId, String anchor) {
        Entry entry = entryOf(projectRoot, file, inspectionId, anchor);
        return entry != null && keys.contains(entry.key());
    }

    public boolean suppress(Path projectRoot, Path file, String inspectionId, String anchor) {
        Entry entry = entryOf(projectRoot, file, inspectionId, anchor);
        if (entry == null || !keys.add(entry.key())) {
            return false;
        }
        save();
        return true;
    }

    public void restore(String key) {
        if (key != null && keys.remove(key)) {
            save();
        }
    }

    public void restoreAll(Path projectRoot) {
        String prefix = normalizeRoot(projectRoot) + SEPARATOR;
        if (keys.removeIf(key -> key.startsWith(prefix))) {
            save();
        }
    }

    public List<Entry> entriesOf(Path projectRoot) {
        String root = normalizeRoot(projectRoot);
        List<Entry> entries = new ArrayList<>();
        for (String key : keys) {
            Entry entry = Entry.parse(key);
            if (entry != null && entry.projectRoot().equals(root)) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    public int size() {
        return keys.size();
    }

    private Entry entryOf(Path projectRoot, Path file, String inspectionId, String anchor) {
        if (file == null || inspectionId == null || inspectionId.isBlank()
                || anchor == null || anchor.isBlank()) {
            return null;
        }
        return new Entry(normalizeRoot(projectRoot), relativize(projectRoot, file),
                inspectionId, anchor);
    }

    static String normalizeRoot(Path projectRoot) {
        return projectRoot == null ? ""
                : projectRoot.toAbsolutePath().normalize().toString()
                        .replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static String relativize(Path projectRoot, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (projectRoot == null) {
            return absolute.toString().replace('\\', '/');
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            return root.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolute.toString().replace('\\', '/');
        }
    }

    private void load() {
        if (storeFile == null || !Files.isRegularFile(storeFile)) {
            return;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(storeFile)) {
            properties.load(in);
        } catch (IOException e) {
            log.debug("Supressoes de inspecao ilegiveis: {}", e.getMessage());
            return;
        }
        properties.stringPropertyNames().forEach(keys::add);
    }

    private void save() {
        if (storeFile == null) {
            return;
        }
        Properties properties = new Properties();
        keys.forEach(key -> properties.setProperty(key, "1"));
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var out = Files.newOutputStream(storeFile)) {
                properties.store(out, "JavaOrionSupport - supressoes de inspecao");
            }
        } catch (IOException e) {
            log.warn("Falha ao gravar as supressoes de inspecao: {}", e.getMessage());
        }
    }
}
